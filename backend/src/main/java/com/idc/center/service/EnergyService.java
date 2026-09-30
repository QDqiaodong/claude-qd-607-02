package com.idc.center.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.idc.center.common.BizException;
import com.idc.center.domain.EnergyPriceSegment;
import com.idc.center.domain.EnergyPriceVersion;
import com.idc.center.domain.EnergyRecord;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.repository.CabinetRepository;
import com.idc.center.repository.EnergyPriceSegmentRepository;
import com.idc.center.repository.EnergyPriceVersionRepository;
import com.idc.center.repository.EnergyRecordRepository;

/**
 * 模块五：能耗记录。旧记录保留历史固定计费；新记录按全站电价版本在自然日上拆分并固化快照。
 */
@Service
public class EnergyService {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final String VERSIONED = "VERSIONED";
    private static final String PRICE_LOCK = "idc_energy_price_version_lock";

    private final EnergyRecordRepository energyRepository;
    private final EnergyPriceVersionRepository priceVersionRepository;
    private final EnergyPriceSegmentRepository segmentRepository;
    private final CabinetRepository cabinetRepository;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public EnergyService(EnergyRecordRepository energyRepository,
                         EnergyPriceVersionRepository priceVersionRepository,
                         EnergyPriceSegmentRepository segmentRepository,
                         CabinetRepository cabinetRepository,
                         DataSource dataSource,
                         JdbcTemplate jdbcTemplate) {
        this.energyRepository = energyRepository;
        this.priceVersionRepository = priceVersionRepository;
        this.segmentRepository = segmentRepository;
        this.cabinetRepository = cabinetRepository;
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询：机柜精确匹配 + 抄表人 contains 忽略大小写（Example）；
     * 月份与用电量区间是范围条件，在 Example 结果上再过一道。
     */
    @Transactional(readOnly = true)
    public Views.PageResult<Views.EnergyRecordView> search(Dtos.EnergyQuery query) {
        EnergyRecord probe = new EnergyRecord();
        probe.setCabinetId(query.getCabinetId());
        probe.setMeterReader(ExamplePage.blankToNull(query.getMeterReader()));

        ExampleMatcher matcher = ExampleMatcher.matchingAll()
                .withIgnoreNullValues()
                .withIgnoreCase(true)
                .withStringMatcher(ExampleMatcher.StringMatcher.CONTAINING);

        List<EnergyRecord> rows = ExamplePage.allDesc(energyRepository, Example.of(probe, matcher), "id");
        Views.PageResult<EnergyRecord> page = ExamplePage.page(rows, matchRange(query), query.getPage(), query.getSize());

        List<Long> ids = page.getContent().stream().map(EnergyRecord::getId).toList();
        Map<Long, List<EnergyPriceSegment>> segmentMap = segmentRepository
                .findByEnergyRecordIdInOrderByEnergyRecordIdAscSegmentOrderAsc(ids)
                .stream()
                .collect(Collectors.groupingBy(EnergyPriceSegment::getEnergyRecordId));

        List<Views.EnergyRecordView> content = page.getContent().stream()
                .map(row -> Views.EnergyRecordView.of(row, segmentMap.getOrDefault(row.getId(), List.of())))
                .toList();
        return Views.PageResult.of(content, page.getTotal(), page.getPage(), page.getSize());
    }

    private Predicate<EnergyRecord> matchRange(Dtos.EnergyQuery query) {
        LocalDate[] range = monthRange(query.getMonth());
        return row -> {
            if (range[0] != null && !ExamplePage.inRange(row.getMeterDate(), range[0], range[1])) {
                return false;
            }
            if (query.getMinUsage() != null || query.getMaxUsage() != null) {
                return ExamplePage.between(row.getUsageKwh(), query.getMinUsage(), query.getMaxUsage());
            }
            return true;
        };
    }

    @Transactional(readOnly = true)
    public List<Views.EnergyPriceVersionView> priceVersions() {
        List<EnergyPriceVersion> versions = priceVersionRepository.findAllByOrderByEffectiveDateAsc();
        if (versions.isEmpty()) {
            return List.of();
        }
        LocalDate today = LocalDate.now();
        List<Views.EnergyPriceVersionView> views = new ArrayList<>();
        for (int i = 0; i < versions.size(); i++) {
            EnergyPriceVersion version = versions.get(i);
            LocalDate endDate = i + 1 < versions.size()
                    ? versions.get(i + 1).getEffectiveDate().minusDays(1)
                    : null;
            LocalDate nextEffectiveDate = i + 1 < versions.size()
                    ? versions.get(i + 1).getEffectiveDate()
                    : null;
            String status;
            if (version.getEffectiveDate().isAfter(today)) {
                status = "FUTURE";
            } else if (nextEffectiveDate == null || nextEffectiveDate.isAfter(today)) {
                status = "CURRENT";
            } else {
                status = "HISTORY";
            }
            views.add(Views.EnergyPriceVersionView.of(version, endDate, status));
        }
        return views;
    }

    @Transactional
    public EnergyPriceVersion createPriceVersion(Dtos.EnergyPriceVersionReq req) {
        acquireGlobalEnergyLock();

        LocalDate effectiveDate = req.getEffectiveDate();
        BigDecimal price = req.getBaseUnitPrice();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("基础单价必须大于 0");
        }
        if (priceVersionRepository.existsByEffectiveDate(effectiveDate)) {
            throw new BizException("生效日期 " + effectiveDate + " 已存在电价版本，同一日期全站只能有一个生效版本");
        }

        List<EnergyPriceVersion> versions = priceVersionRepository.findAllByOrderByEffectiveDateAsc();
        validateVersionTimeline(effectiveDate, versions);
        validateVersionNotInFormedPeriod(effectiveDate);

        EnergyPriceVersion version = new EnergyPriceVersion();
        version.setName(req.getName().trim());
        version.setEffectiveDate(effectiveDate);
        version.setBaseUnitPrice(price.setScale(4, RoundingMode.HALF_UP));
        version.setBillingNote(req.getBillingNote().trim());
        version.setEnabledBy(req.getEnabledBy().trim());
        version.setCreatedAt(LocalDateTime.now());
        return priceVersionRepository.save(version);
    }

    private void validateVersionTimeline(LocalDate effectiveDate, List<EnergyPriceVersion> versions) {
        LocalDate today = LocalDate.now();
        if (versions.isEmpty()) {
            if (effectiveDate.isAfter(today)) {
                throw new BizException("首个电价版本必须从 " + today + " 或更早日期生效，否则当前日期存在计费空档");
            }
            return;
        }

        EnergyPriceVersion first = versions.get(0);
        EnergyPriceVersion previous = versions.stream()
                .filter(version -> !version.getEffectiveDate().isAfter(effectiveDate))
                .reduce((a, b) -> b)
                .orElse(null);
        EnergyPriceVersion next = versions.stream()
                .filter(version -> version.getEffectiveDate().isAfter(effectiveDate))
                .findFirst()
                .orElse(null);

        if (previous == null) {
            throw new BizException("不能在首个版本 " + first.getEffectiveDate()
                    + " 之前补登版本：" + effectiveDate + " 已经是过去日期，已开始生效的价格不能补改，"
                    + "请由新的版本在今天或未来日期接续");
        }
        if (effectiveDate.isBefore(today)) {
            throw new BizException("生效日期 " + effectiveDate
                    + " 已过去，已开始生效的价格不能补改；请选择今天或未来日期由新版本接续");
        }
        if (next != null && effectiveDate.isAfter(next.getEffectiveDate())) {
            throw new BizException("生效日期与已有版本交叠：新版本结束于 "
                    + next.getEffectiveDate().minusDays(1) + "，下一版本 " + next.getEffectiveDate() + " 已生效");
        }
    }

    private void validateVersionNotInFormedPeriod(LocalDate effectiveDate) {
        List<EnergyRecord> affected = energyRepository
                .findByBillingTypeAndPeriodStartLessThanEqualAndMeterDateGreaterThanEqual(
                        VERSIONED, effectiveDate, effectiveDate)
                .stream()
                .sorted(Comparator.comparing(EnergyRecord::getMeterDate)
                        .thenComparing(EnergyRecord::getCabinetId))
                .limit(10)
                .toList();
        if (!affected.isEmpty()) {
            String detail = affected.stream()
                    .map(record -> cabinetRepository.findById(record.getCabinetId())
                            .map(cabinet -> cabinet.getCode())
                            .orElse("机柜#" + record.getCabinetId())
                            + "（区间 " + record.getPeriodStart() + " ~ " + record.getMeterDate()
                            + "，抄表记录 #" + record.getId() + "）")
                    .collect(Collectors.joining("、"));
            throw new BizException("电价生效日 " + effectiveDate
                    + " 已落入已形成费用的抄表区间，已有版本链和费用保持不变。受影响记录：" + detail
                    + "。请选择未形成后续读数的日期，由新版本接续");
        }
    }

    @Transactional(readOnly = true)
    public Views.EnergyBillingPreview preview(Dtos.EnergyCreateReq req) {
        cabinetRepository.findById(req.getCabinetId()).orElseThrow(() -> new BizException("机柜不存在"));
        if (req.getMeterReading() == null || req.getMeterReading().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("电表读数不能为空或小于 0");
        }
        if (energyRepository.existsByCabinetIdAndMeterDate(req.getCabinetId(), req.getMeterDate())) {
            throw new BizException("该机柜在 " + req.getMeterDate() + " 已存在抄表记录");
        }

        EnergyRecord previous = energyRepository
                .findFirstByCabinetIdAndMeterDateLessThanOrderByMeterDateDescIdDesc(
                        req.getCabinetId(), req.getMeterDate())
                .orElse(null);
        if (previous != null && req.getMeterReading().compareTo(previous.getMeterReading()) < 0) {
            throw new BizException("电表读数不能小于上一次抄表读数（" + previous.getMeterReading() + " kWh）");
        }

        List<EnergyPriceVersion> versions = priceVersionRepository.findAllByOrderByEffectiveDateAsc();
        BillingPlan plan = calculateBillingPlan(req, previous, versions);
        return plan.toPreview(req.getCabinetId(), req.getMeterDate(), req.getMeterReading(), previous);
    }

    @Transactional
    public Views.EnergyRecordView create(Dtos.EnergyCreateReq req) {
        acquireGlobalEnergyLock();

        cabinetRepository.findById(req.getCabinetId()).orElseThrow(() -> new BizException("机柜不存在"));
        if (req.getMeterReading() == null || req.getMeterReading().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("电表读数不能为空或小于 0");
        }
        if (energyRepository.existsByCabinetIdAndMeterDate(req.getCabinetId(), req.getMeterDate())) {
            throw new BizException("该机柜在 " + req.getMeterDate() + " 已存在抄表记录");
        }

        EnergyRecord previous = energyRepository
                .findFirstByCabinetIdAndMeterDateLessThanOrderByMeterDateDescIdDesc(
                        req.getCabinetId(), req.getMeterDate())
                .orElse(null);
        if (previous != null && req.getMeterReading().compareTo(previous.getMeterReading()) < 0) {
            throw new BizException("电表读数不能小于上一次抄表读数（" + previous.getMeterReading() + " kWh）");
        }

        List<EnergyPriceVersion> versions = priceVersionRepository.findAllByOrderByEffectiveDateAsc();
        BillingPlan plan = calculateBillingPlan(req, previous, versions);

        EnergyRecord record = new EnergyRecord();
        record.setCabinetId(req.getCabinetId());
        record.setMeterDate(req.getMeterDate());
        record.setMeterReading(req.getMeterReading().setScale(2, RoundingMode.HALF_UP));
        record.setPeriodStart(plan.periodStart());
        record.setUsageKwh(plan.usage());
        record.setUnitPrice(plan.displayPrice());
        record.setFee(plan.totalFee());
        record.setMeterReader(req.getMeterReader());
        record.setRemark(req.getRemark());
        record.setBillingType(VERSIONED);
        EnergyRecord saved = energyRepository.save(record);

        List<EnergyPriceSegment> segments = new ArrayList<>();
        int order = 1;
        for (SegmentPlan item : plan.segments()) {
            EnergyPriceSegment segment = new EnergyPriceSegment();
            segment.setEnergyRecordId(saved.getId());
            segment.setSegmentOrder(order++);
            segment.setStartDate(item.startDate());
            segment.setEndDate(item.endDate());
            segment.setDayCount(item.dayCount());
            segment.setUsageKwh(item.usage());
            segment.setPriceVersionId(item.version().getId());
            segment.setVersionName(item.version().getName());
            segment.setEffectiveDate(item.version().getEffectiveDate());
            segment.setUnitPrice(item.version().getBaseUnitPrice());
            segment.setSegmentFee(item.fee());
            segments.add(segment);
        }
        List<EnergyPriceSegment> savedSegments = segmentRepository.saveAll(segments);
        saved.setPriceSegments(savedSegments);
        return Views.EnergyRecordView.of(saved, savedSegments);
    }

    private BillingPlan calculateBillingPlan(Dtos.EnergyCreateReq req, EnergyRecord previous,
                                             List<EnergyPriceVersion> versions) {
        BigDecimal reading = req.getMeterReading().setScale(2, RoundingMode.HALF_UP);
        if (versions.isEmpty()) {
            throw new BizException("尚未配置电价版本，不能进行版本化计费");
        }

        if (previous == null) {
            EnergyPriceVersion current = versionAt(versions, req.getMeterDate())
                    .orElseThrow(() -> new BizException("抄表日 " + req.getMeterDate() + " 没有可适用的电价版本"));
            return new BillingPlan(null, BigDecimal.ZERO, BigDecimal.ZERO, current.getBaseUnitPrice(), List.of());
        }

        LocalDate periodStart = previous.getMeterDate().plusDays(1);
        LocalDate periodEnd = req.getMeterDate();
        if (periodStart.isAfter(periodEnd)) {
            throw new BizException("抄表日期必须晚于上一次抄表日期（" + previous.getMeterDate() + "）");
        }
        if (versionAt(versions, periodStart).isEmpty()) {
            throw new BizException("计费区间起点 " + periodStart + " 没有可适用的电价版本，电价链存在空档");
        }
        if (versionAt(versions, periodEnd).isEmpty()) {
            throw new BizException("计费区间终点 " + periodEnd + " 没有可适用的电价版本，电价链存在空档");
        }

        BigDecimal usage = reading.subtract(previous.getMeterReading()).setScale(2, RoundingMode.HALF_UP);
        int totalDays = Math.toIntExact(ChronoUnit.DAYS.between(periodStart, periodEnd)) + 1;
        List<SegmentPlan> segments = buildSegments(periodStart, periodEnd, totalDays, usage, versions);

        BigDecimal totalFee = segments.stream()
                .map(SegmentPlan::fee)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        EnergyPriceVersion endVersion = versionAt(versions, periodEnd).orElseThrow();
        return new BillingPlan(periodStart, usage, totalFee, endVersion.getBaseUnitPrice(), segments);
    }

    private List<SegmentPlan> buildSegments(LocalDate periodStart, LocalDate periodEnd, int totalDays,
                                            BigDecimal usage, List<EnergyPriceVersion> versions) {
        List<SegmentPlan> result = new ArrayList<>();
        for (int i = 0; i < versions.size(); i++) {
            EnergyPriceVersion version = versions.get(i);
            LocalDate versionStart = version.getEffectiveDate();
            LocalDate versionEnd = i + 1 < versions.size()
                    ? versions.get(i + 1).getEffectiveDate().minusDays(1)
                    : null;

            LocalDate start = versionStart.isAfter(periodStart) ? versionStart : periodStart;
            LocalDate end = versionEnd == null || periodEnd.isBefore(versionEnd) ? periodEnd : versionEnd;
            if (start.isAfter(periodEnd) || end.isBefore(periodStart)) {
                continue;
            }

            int days = Math.toIntExact(ChronoUnit.DAYS.between(start, end)) + 1;
            result.add(new SegmentPlan(version, start, end, days, BigDecimal.ZERO, BigDecimal.ZERO));
        }

        if (result.isEmpty()) {
            throw new BizException("计费区间 " + periodStart + " ~ " + periodEnd + " 未命中任何电价版本");
        }
        int countedDays = result.stream().map(SegmentPlan::dayCount).reduce(0, Integer::sum);
        if (countedDays != totalDays) {
            throw new BizException("电价版本分段天数合计为 " + countedDays + "，应为 " + totalDays + "，存在空档或交叠");
        }

        BigDecimal[] quotients = new BigDecimal[result.size()];
        BigDecimal[] floors = new BigDecimal[result.size()];
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < result.size(); i++) {
            SegmentPlan old = result.get(i);
            BigDecimal exact = usage.multiply(BigDecimal.valueOf(old.dayCount()))
                    .divide(BigDecimal.valueOf(totalDays), 6, RoundingMode.HALF_UP);
            BigDecimal floor = exact.setScale(2, RoundingMode.FLOOR);
            quotients[i] = exact;
            floors[i] = floor;
            allocated = allocated.add(floor);
        }

        int remainderCents = usage.subtract(allocated).movePointRight(2).intValue();
        List<Integer> orderByRemainder = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            orderByRemainder.add(i);
        }
        orderByRemainder.sort(Comparator.comparing((Integer i) ->
                quotients[i].subtract(floors[i]).movePointRight(2)).reversed());
        for (int i = 0; i < remainderCents; i++) {
            floors[orderByRemainder.get(i % floors.length)] =
                    floors[orderByRemainder.get(i % floors.length)].add(BigDecimal.valueOf(0.01));
        }

        for (int i = 0; i < result.size(); i++) {
            SegmentPlan old = result.get(i);
            BigDecimal segmentUsage = floors[i];
            BigDecimal fee = segmentUsage.multiply(old.version().getBaseUnitPrice())
                    .setScale(2, RoundingMode.HALF_UP);
            result.set(i, new SegmentPlan(old.version(), old.startDate(), old.endDate(), old.dayCount(),
                    segmentUsage, fee));
        }
        return result;
    }

    private java.util.Optional<EnergyPriceVersion> versionAt(List<EnergyPriceVersion> versions, LocalDate date) {
        return versions.stream()
                .filter(version -> !version.getEffectiveDate().isAfter(date))
                .reduce((a, b) -> b);
    }

    /** 本月（或指定月份）总用电 / 总电费 + 按机柜用电排行；汇总只累加已固化账单 */
    @Transactional(readOnly = true)
    public Views.EnergySummary summary(String month) {
        LocalDate[] range = monthRange(month);
        List<EnergyRecord> rows = energyRepository.findAll(
                Example.of(new EnergyRecord(), ExampleMatcher.matchingAll().withIgnoreNullValues()));

        BigDecimal totalUsage = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        Map<Long, BigDecimal> usageByCabinet = new HashMap<>();
        Map<Long, BigDecimal> feeByCabinet = new HashMap<>();
        int count = 0;
        for (EnergyRecord row : rows) {
            if (range[0] != null && !ExamplePage.inRange(row.getMeterDate(), range[0], range[1])) {
                continue;
            }
            count += 1;
            BigDecimal usage = row.getUsageKwh() == null ? BigDecimal.ZERO : row.getUsageKwh();
            BigDecimal fee = row.getFee() == null ? BigDecimal.ZERO : row.getFee();
            totalUsage = totalUsage.add(usage);
            totalFee = totalFee.add(fee);
            usageByCabinet.merge(row.getCabinetId(), usage, BigDecimal::add);
            feeByCabinet.merge(row.getCabinetId(), fee, BigDecimal::add);
        }

        List<Views.CabinetUsageRow> ranking = new ArrayList<>();
        usageByCabinet.forEach((cabinetId, usage) -> {
            Views.CabinetUsageRow row = new Views.CabinetUsageRow();
            row.setCabinetId(cabinetId);
            row.setCabinetCode(cabinetRepository.findById(cabinetId).map(cabinet -> cabinet.getCode()).orElse("—"));
            row.setUsageKwh(usage.setScale(2, RoundingMode.HALF_UP));
            row.setFee(feeByCabinet.getOrDefault(cabinetId, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
            ranking.add(row);
        });
        ranking.sort(Comparator.comparing(Views.CabinetUsageRow::getUsageKwh).reversed());

        Views.EnergySummary summary = new Views.EnergySummary();
        summary.setMonth(month == null || month.isBlank()
                ? LocalDate.now().format(MONTH_FORMATTER) : month);
        summary.setTotalUsage(totalUsage.setScale(2, RoundingMode.HALF_UP));
        summary.setTotalFee(totalFee.setScale(2, RoundingMode.HALF_UP));
        summary.setRecordCount(count);
        summary.setCabinetCount(usageByCabinet.size());
        summary.setRanking(ranking.stream().limit(8).toList());
        return summary;
    }

    /** "yyyy-MM" → [月初, 月末]；空则取当前月 */
    private LocalDate[] monthRange(String month) {
        String value = month == null || month.isBlank()
                ? LocalDate.now().format(MONTH_FORMATTER) : month.trim();
        int year;
        int mon;
        try {
            year = Integer.parseInt(value.substring(0, 4));
            mon = Integer.parseInt(value.substring(5, 7));
        } catch (RuntimeException e) {
            throw new BizException("月份格式应为 yyyy-MM");
        }
        LocalDate from = LocalDate.of(year, mon, 1);
        return new LocalDate[]{from, from.withDayOfMonth(from.lengthOfMonth())};
    }

    private void acquireGlobalEnergyLock() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("电价和抄表写入必须在事务中获取全局锁");
        }
        // 确保当前事务绑定一条连接；随后的 JdbcTemplate 查询会复用这条连接。
        DataSourceUtils.getConnection(dataSource);
        Integer locked = jdbcTemplate.queryForObject("SELECT GET_LOCK(?, 5)", Integer.class, PRICE_LOCK);
        if (locked == null || locked != 1) {
            throw new BizException("当前有电价版本或抄表记录正在提交，请稍后重试");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    jdbcTemplate.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, PRICE_LOCK);
                } catch (Exception ignored) {
                    // 连接归还连接池时 MySQL 也会自动释放会话锁。
                }
            }
        });
    }

    private record SegmentPlan(EnergyPriceVersion version, LocalDate startDate, LocalDate endDate,
                               int dayCount, BigDecimal usage, BigDecimal fee) {
    }

    private record BillingPlan(LocalDate periodStart, BigDecimal usage, BigDecimal totalFee,
                               BigDecimal displayPrice, List<SegmentPlan> segments) {

        private Views.EnergyBillingPreview toPreview(Long cabinetId, LocalDate meterDate,
                                                     BigDecimal meterReading, EnergyRecord previous) {
            Views.EnergyBillingPreview preview = new Views.EnergyBillingPreview();
            preview.setCabinetId(cabinetId);
            preview.setPreviousMeterDate(previous == null ? null : previous.getMeterDate());
            preview.setPreviousReading(previous == null ? null : previous.getMeterReading());
            preview.setPeriodStart(periodStart);
            preview.setMeterDate(meterDate);
            preview.setMeterReading(meterReading.setScale(2, RoundingMode.HALF_UP));
            preview.setUsageKwh(usage);
            preview.setTotalFee(totalFee);
            preview.setTotalDays(segments.stream().map(SegmentPlan::dayCount).reduce(0, Integer::sum));
            preview.setSegments(segments.stream().map(item -> {
                EnergyPriceSegment segment = new EnergyPriceSegment();
                segment.setSegmentOrder(segments.indexOf(item) + 1);
                segment.setStartDate(item.startDate());
                segment.setEndDate(item.endDate());
                segment.setDayCount(item.dayCount());
                segment.setUsageKwh(item.usage());
                segment.setPriceVersionId(item.version().getId());
                segment.setVersionName(item.version().getName());
                segment.setEffectiveDate(item.version().getEffectiveDate());
                segment.setUnitPrice(item.version().getBaseUnitPrice());
                segment.setSegmentFee(item.fee());
                return Views.EnergySegmentView.of(segment);
            }).toList());
            return preview;
        }
    }
}
