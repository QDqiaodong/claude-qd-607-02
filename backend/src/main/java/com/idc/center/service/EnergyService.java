package com.idc.center.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.idc.center.common.BizException;
import com.idc.center.domain.Cabinet;
import com.idc.center.domain.EnergyRecord;
import com.idc.center.domain.EnergyTariffSegment;
import com.idc.center.domain.TariffVersion;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.repository.CabinetRepository;
import com.idc.center.repository.EnergyRecordRepository;
import com.idc.center.repository.EnergyTariffSegmentRepository;
import com.idc.center.repository.TariffVersionRepository;

/**
 * 模块五：能耗记录。
 *
 * 两种计费口径（billing_type）：
 *   LEGACY_FIXED 历史固定计费 —— 电价版本功能启用前的老记录，保留当时手填单价与费用，
 *                 不反推价段，任何后续操作都不重算；
 *   VERSIONED    跨价段计费 —— 功能启用后的新记录：用电量 = 本次读数 − 上次读数，
 *                 区间 [上次抄表日次日, 本次抄表日] 按电价版本生效日拆成自然日价段，
 *                 分段费用汇总到本条，分段快照随记录同事务固化。
 *
 * 月度总用电 / 总电费 / 机柜排行全部直接对「记录上的 usageKwh / fee」求和，
 * 而 fee 对 VERSIONED 记录恒等于分段费用之和，所以明细、分段、汇总必然同一套数字。
 *
 * 并发口径：落账事务先锁电价版本链再锁机柜行，与电价版本调整共用同一把版本链锁，
 * 抄表人与运维在切换日同时操作也会被串行化，不会产生「版本已启用但明细仍旧价」
 * 或「明细已分段而汇总仍旧价」的半完成状态。
 */
@Service
public class EnergyService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final EnergyRecordRepository energyRepository;
    private final EnergyTariffSegmentRepository segmentRepository;
    private final TariffVersionRepository tariffRepository;
    private final CabinetRepository cabinetRepository;

    public EnergyService(EnergyRecordRepository energyRepository,
                         EnergyTariffSegmentRepository segmentRepository,
                         TariffVersionRepository tariffRepository,
                         CabinetRepository cabinetRepository) {
        this.energyRepository = energyRepository;
        this.segmentRepository = segmentRepository;
        this.tariffRepository = tariffRepository;
        this.cabinetRepository = cabinetRepository;
    }

    /**
     * 查询：机柜精确匹配 + 抄表人 contains 忽略大小写（Example）；
     * 月份与用电量区间是范围条件，在 Example 结果上再过一道。VERSIONED 记录批量挂分段明细。
     */
    public Views.PageResult<EnergyRecord> search(Dtos.EnergyQuery query) {
        EnergyRecord probe = new EnergyRecord();
        probe.setCabinetId(query.getCabinetId());
        probe.setMeterReader(ExamplePage.blankToNull(query.getMeterReader()));

        ExampleMatcher matcher = ExampleMatcher.matchingAll()
                .withIgnoreNullValues()
                .withIgnoreCase(true)
                .withStringMatcher(ExampleMatcher.StringMatcher.CONTAINING);

        List<EnergyRecord> rows = ExamplePage.allDesc(energyRepository, Example.of(probe, matcher), "id");
        Views.PageResult<EnergyRecord> page = ExamplePage.page(rows, matchRange(query), query.getPage(), query.getSize());
        attachSegments(page.getContent());
        return page;
    }

    /** 单条详情：含分段明细 */
    public EnergyRecord detail(Long id) {
        EnergyRecord record = energyRepository.findById(id).orElseThrow(() -> new BizException("抄表记录不存在"));
        if (EnergyRecord.BILLING_VERSIONED.equals(record.getBillingType())) {
            record.setTariffSegments(segmentRepository.findByEnergyIdOrderBySegNoAsc(id));
        }
        return record;
    }

    private void attachSegments(List<EnergyRecord> rows) {
        List<Long> versionedIds = rows.stream()
                .filter(row -> EnergyRecord.BILLING_VERSIONED.equals(row.getBillingType()))
                .map(EnergyRecord::getId)
                .toList();
        if (versionedIds.isEmpty()) {
            return;
        }
        Map<Long, List<EnergyTariffSegment>> byRecord = segmentRepository
                .findByEnergyIdInOrderByEnergyIdAscSegNoAsc(versionedIds).stream()
                .collect(Collectors.groupingBy(EnergyTariffSegment::getEnergyId));
        rows.forEach(row -> row.setTariffSegments(byRecord.getOrDefault(row.getId(), List.of())));
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

    /**
     * 新增抄表前的计费预览（不落库）。确认提交时在同一份版本链上重算并连同分段一起落账，
     * 因此预览与最终明细必然一致。
     */
    public Views.EnergyBillingPreview preview(Dtos.EnergyCreateReq req) {
        cabinetRepository.findById(req.getCabinetId()).orElseThrow(() -> new BizException("机柜不存在"));
        List<TariffVersion> chain = tariffRepository.findAllByOrderByEffectiveDateAsc();
        EnergyRecord previous = findPrevious(req.getCabinetId(), req.getMeterDate());
        return buildPreview(chain, previous, req);
    }

    private Views.EnergyBillingPreview buildPreview(List<TariffVersion> chain, EnergyRecord previous,
                                                    Dtos.EnergyCreateReq req) {
        BigDecimal reading = req.getMeterReading();
        if (previous != null && reading.compareTo(previous.getMeterReading()) < 0) {
            throw new BizException("电表读数不能小于上一次抄表读数（" + previous.getMeterReading() + " kWh）");
        }

        Views.EnergyBillingPreview preview = new Views.EnergyBillingPreview();
        preview.setCabinetId(req.getCabinetId());
        preview.setMeterDate(req.getMeterDate());
        preview.setMeterReading(reading);
        if (previous != null) {
            preview.setPreviousReading(previous.getMeterReading());
            preview.setPreviousMeterDate(previous.getMeterDate());
        }

        BigDecimal usage = previous == null ? BigDecimal.ZERO
                : reading.subtract(previous.getMeterReading()).setScale(2, RoundingMode.HALF_UP);
        preview.setUsageKwh(usage);

        if (previous == null) {
            // 首条建账：无上期读数，本期无电量可拆
            preview.setKind("FIRST_RECORD");
            preview.setTotalFee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            preview.setSegments(List.of());
            return preview;
        }

        LocalDate periodStart = previous.getMeterDate().plusDays(1);
        preview.setPeriodStart(periodStart);
        preview.setKind("NORMAL");
        if (usage.compareTo(BigDecimal.ZERO) == 0) {
            preview.setTotalFee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            preview.setSegments(List.of());
            return preview;
        }

        List<TariffBilling.Slice> slices = TariffBilling.split(chain, periodStart, req.getMeterDate(), usage);
        List<Views.TariffSegmentView> segmentViews = new ArrayList<>();
        for (TariffBilling.Slice slice : slices) {
            Views.TariffSegmentView view = new Views.TariffSegmentView();
            view.setTariffVersionId(slice.version.getId());
            view.setTariffName(slice.version.getName());
            view.setUnitPrice(slice.version.getBasePrice());
            view.setSegStart(slice.start);
            view.setSegEnd(slice.end);
            view.setDayCount(slice.days);
            view.setAllocKwh(slice.allocKwh);
            view.setSegFee(slice.fee);
            segmentViews.add(view);
        }
        preview.setSegments(segmentViews);
        preview.setTotalFee(TariffBilling.totalFee(slices));
        return preview;
    }

    /**
     * 新增抄表（事务）：锁版本链 → 锁机柜 → 在锁内重新取上期读数并计费 → 记录与分段快照一起提交。
     */
    @Transactional
    public EnergyRecord create(Dtos.EnergyCreateReq req) {
        // 全局统一加锁顺序：先电价版本链、再机柜行（与 TariffService 一致，杜绝锁环）。
        // 版本链锁串行化「版本调整 ↔ 抄表落账」，柜行锁串行化同柜两次抄表。
        List<TariffVersion> chain = tariffRepository.lockChain();
        Cabinet cabinet = cabinetRepository.lockById(req.getCabinetId())
                .orElseThrow(() -> new BizException("机柜不存在"));

        EnergyRecord previous = findPrevious(req.getCabinetId(), req.getMeterDate());
        if (previous != null && req.getMeterReading().compareTo(previous.getMeterReading()) < 0) {
            throw new BizException("电表读数不能小于上一次抄表读数（" + previous.getMeterReading() + " kWh）");
        }
        if (chain.isEmpty()) {
            throw new BizException("尚未启用任何电价版本，无法按版本化计费；请先在「电价版本」中登记版本");
        }

        BigDecimal reading = req.getMeterReading();
        BigDecimal usage = previous == null ? BigDecimal.ZERO
                : reading.subtract(previous.getMeterReading()).setScale(2, RoundingMode.HALF_UP);

        EnergyRecord record = new EnergyRecord();
        record.setCabinetId(cabinet.getId());
        record.setMeterDate(req.getMeterDate());
        record.setMeterReading(reading);
        record.setUsageKwh(usage);
        record.setMeterReader(req.getMeterReader());
        record.setRemark(req.getRemark());
        // 功能启用后新增的记录一律跨价段计费（含首条建账），老数据不会走到这里
        record.setBillingType(EnergyRecord.BILLING_VERSIONED);

        BigDecimal fee;
        LocalDate periodStart = null;
        List<EnergyTariffSegment> segments = List.of();
        if (previous == null) {
            // 首条建账：无区间无费用，单价记当前生效版本仅作展示
            TariffVersion current = effectiveVersion(chain, req.getMeterDate());
            record.setUnitPrice(current == null ? chain.get(0).getBasePrice() : current.getBasePrice());
            fee = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        } else {
            periodStart = previous.getMeterDate().plusDays(1);
            if (usage.compareTo(BigDecimal.ZERO) == 0) {
                TariffVersion current = effectiveVersion(chain, req.getMeterDate());
                record.setUnitPrice(current == null ? chain.get(0).getBasePrice() : current.getBasePrice());
                fee = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            } else {
                List<TariffBilling.Slice> slices = TariffBilling.split(chain, periodStart, req.getMeterDate(), usage);
                fee = TariffBilling.totalFee(slices);
                // 综合均价仅作列表展示，费用以分段为准
                record.setUnitPrice(fee.divide(usage, 3, RoundingMode.HALF_UP));
                // 先落主记录拿到 id，再写分段快照；二者在同一事务，任一失败整体回滚
                record.setPeriodStart(periodStart);
                record.setFee(fee);
                record = energyRepository.saveAndFlush(record);
                segments = TariffBilling.toSegments(record.getId(), slices);
                segmentRepository.saveAll(segments);
                record.setTariffSegments(segments);
                return record;
            }
        }
        record.setPeriodStart(periodStart);
        record.setFee(fee);
        return energyRepository.saveAndFlush(record);
    }

    /** 当月（或指定月份）总用电 / 总电费 + 按机柜用电排行 —— 全部直接汇总记录上的固化值 */
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
                ? LocalDate.now().format(FORMATTER) : month);
        summary.setTotalUsage(totalUsage.setScale(2, RoundingMode.HALF_UP));
        summary.setTotalFee(totalFee.setScale(2, RoundingMode.HALF_UP));
        summary.setRecordCount(count);
        summary.setCabinetCount(usageByCabinet.size());
        summary.setRanking(ranking.stream().limit(8).toList());
        return summary;
    }

    /** 上锁后在锁内取「同机柜抄表日严格早于本次、最近的一条」上期记录 */
    private EnergyRecord findPrevious(Long cabinetId, LocalDate meterDate) {
        return energyRepository.findByCabinetIdOrderByMeterDateAscIdAsc(cabinetId).stream()
                .filter(row -> row.getMeterDate() != null && row.getMeterDate().isBefore(meterDate))
                .max(Comparator.comparing(EnergyRecord::getMeterDate)
                        .thenComparing(EnergyRecord::getId))
                .orElse(null);
    }

    private TariffVersion effectiveVersion(List<TariffVersion> chain, LocalDate date) {
        TariffVersion hit = null;
        for (TariffVersion v : chain) {
            if (!v.getEffectiveDate().isAfter(date)) {
                hit = v;
            } else {
                break;
            }
        }
        return hit;
    }

    /** "yyyy-MM" → [月初, 月末]；空则取当前月 */
    private LocalDate[] monthRange(String month) {
        String value = month == null || month.isBlank()
                ? LocalDate.now().format(FORMATTER) : month.trim();
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
}
