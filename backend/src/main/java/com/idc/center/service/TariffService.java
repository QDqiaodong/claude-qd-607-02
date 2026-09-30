package com.idc.center.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.idc.center.common.BizException;
import com.idc.center.domain.EnergyRecord;
import com.idc.center.domain.TariffVersion;
import com.idc.center.dto.Dtos;
import com.idc.center.dto.Views;
import com.idc.center.repository.CabinetRepository;
import com.idc.center.repository.EnergyRecordRepository;
import com.idc.center.repository.EnergyTariffSegmentRepository;
import com.idc.center.repository.TariffVersionRepository;

/**
 * 电价版本链维护（全站一套）。
 *
 * 不变量：
 *   1. 生效日唯一（同日只能有一个生效版本，DB 唯一索引兜底）；
 *   2. 版本按生效日排序后区间天然连续不交叠：[v_i.date, v_{i+1}.date − 1]，末版开放；
 *   3. 新版本生效日一旦落入「已形成费用的跨价段抄表区间」就会重算历史账单 —— 拒绝，
 *      并指出受影响日期与抄表记录，原版本链与费用保持不变；
 *   4. 已开始生效或已被分段快照引用的版本，价格 / 生效日锁定，只能由新版本接续；
 *      仅未来生效、且没有任何抄表引用的版本允许调整或删除。
 *
 * 并发：所有写操作先对版本链上悲观行锁（SELECT ... FOR UPDATE），两名运维几乎同时
 * 启用相邻版本也只能串行提交，最终版本链唯一，不会出现交叠或半完成状态。
 */
@Service
public class TariffService {

    private final TariffVersionRepository tariffRepository;
    private final EnergyRecordRepository energyRepository;
    private final EnergyTariffSegmentRepository segmentRepository;
    private final CabinetRepository cabinetRepository;

    public TariffService(TariffVersionRepository tariffRepository,
                         EnergyRecordRepository energyRepository,
                         EnergyTariffSegmentRepository segmentRepository,
                         CabinetRepository cabinetRepository) {
        this.tariffRepository = tariffRepository;
        this.energyRepository = energyRepository;
        this.segmentRepository = segmentRepository;
        this.cabinetRepository = cabinetRepository;
    }

    /** 版本列表 + 生效时间轴（当前 / 未来 / 历史） */
    public Views.TariffTimeline timeline() {
        List<TariffVersion> chain = tariffRepository.findAllByOrderByEffectiveDateAsc();
        LocalDate today = LocalDate.now();

        List<Views.TariffVersionView> views = new ArrayList<>();
        Long currentId = null;
        for (int i = 0; i < chain.size(); i++) {
            TariffVersion v = chain.get(i);
            LocalDate endDate = i + 1 < chain.size() ? chain.get(i + 1).getEffectiveDate().minusDays(1) : null;
            String phase;
            if (v.getEffectiveDate().isAfter(today)) {
                phase = "FUTURE";
            } else if (endDate != null && endDate.isBefore(today)) {
                phase = "HISTORY";
            } else {
                phase = "CURRENT";
                currentId = v.getId();
            }
            boolean referenced = segmentRepository.countByTariffVersionId(v.getId()) > 0;
            Views.TariffVersionView view = Views.TariffVersionView.of(v, phase, referenced);
            view.setEndDate(endDate);
            views.add(view);
        }

        Views.TariffTimeline timeline = new Views.TariffTimeline();
        timeline.setToday(today);
        timeline.setCurrentVersionId(currentId);
        timeline.setVersions(views);
        return timeline;
    }

    /** 登记新版本（可提前登记未来版本） */
    @Transactional
    public TariffVersion create(Dtos.TariffVersionReq req) {
        tariffRepository.lockChain();
        validate(req);

        LocalDate date = req.getEffectiveDate();
        if (tariffRepository.existsByEffectiveDate(date)) {
            throw new BizException(buildOverlapMessage(tariffRepository.findByEffectiveDate(date).orElse(null), date));
        }
        rejectIfInsideBilledPeriods(date, date);

        TariffVersion version = new TariffVersion();
        applyFields(version, req);
        version.setCreatedAt(LocalDateTime.now());
        return save(version);
    }

    /**
     * 调整版本：
     *   已生效（生效日 ≤ 今天）或已被抄表快照引用 —— 价格 / 生效日锁定，只能改名称 / 说明 / 启用人；
     *   未来且无引用 —— 可整体调整，但新生效日同样不能落进已形成费用的区间、不能与他版同日。
     */
    @Transactional
    public TariffVersion update(Long id, Dtos.TariffVersionReq req) {
        tariffRepository.lockChain();
        TariffVersion version = tariffRepository.findById(id)
                .orElseThrow(() -> new BizException("电价版本不存在"));
        validate(req);

        boolean referenced = segmentRepository.countByTariffVersionId(id) > 0;
        boolean started = !version.getEffectiveDate().isAfter(LocalDate.now());
        boolean priceLocked = referenced || started;
        if (priceLocked && req.getBasePrice().compareTo(version.getBasePrice()) != 0) {
            throw new BizException("版本「" + version.getName() + "」"
                    + (referenced ? "已被抄表分段快照引用，" : "已经开始生效，")
                    + "基础单价不能直接改动；请登记一个新版本在指定日期接续（新版本可提前登记）");
        }
        if (priceLocked && !req.getEffectiveDate().equals(version.getEffectiveDate())) {
            throw new BizException("版本「" + version.getName() + "」"
                    + (referenced ? "已被抄表分段快照引用，" : "已经开始生效，")
                    + "生效日期不能移动，否则会改变历史账单；如需调整价格请登记新版本接续");
        }
        if (!req.getEffectiveDate().equals(version.getEffectiveDate())) {
            tariffRepository.findByEffectiveDate(req.getEffectiveDate())
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> {
                        throw new BizException(buildOverlapMessage(other, req.getEffectiveDate()));
                    });
            rejectIfInsideBilledPeriods(req.getEffectiveDate(), req.getEffectiveDate());
        }
        applyFields(version, req);
        return save(version);
    }

    /** 删除：仅允许删除「未来生效且没有任何抄表引用」的版本；已生效 / 已引用一律拒绝 */
    @Transactional
    public void delete(Long id) {
        tariffRepository.lockChain();
        TariffVersion version = tariffRepository.findById(id)
                .orElseThrow(() -> new BizException("电价版本不存在"));
        long refs = segmentRepository.countByTariffVersionId(id);
        if (refs > 0) {
            throw new BizException("版本「" + version.getName() + "」已被 " + refs
                    + " 条抄表记录的分段快照引用，删除会使历史账单失去价格依据，不能删除；"
                    + "如不再执行请保留版本链，由新版本在未来日期接续");
        }
        if (!version.getEffectiveDate().isAfter(LocalDate.now())) {
            throw new BizException("版本「" + version.getName() + "」已经开始生效（"
                    + version.getEffectiveDate() + "），生效版本不能删除，只能由新版本接续");
        }
        tariffRepository.delete(version);
    }

    private void validate(Dtos.TariffVersionReq req) {
        if (req.getBasePrice() == null || req.getBasePrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("基础单价必须大于 0");
        }
    }

    private void applyFields(TariffVersion version, Dtos.TariffVersionReq req) {
        version.setName(req.getName().trim());
        version.setEffectiveDate(req.getEffectiveDate());
        version.setBasePrice(req.getBasePrice().setScale(3, java.math.RoundingMode.HALF_UP));
        version.setBillingNote(req.getBillingNote().trim());
        version.setEnabledBy(req.getEnabledBy().trim());
    }

    private TariffVersion save(TariffVersion version) {
        try {
            return tariffRepository.saveAndFlush(version);
        } catch (DataIntegrityViolationException e) {
            // 并发下两名运维登记了同一生效日：唯一索引兜底，报业务提示，先提交的版本链保持不变
            throw new BizException("生效日期 " + version.getEffectiveDate()
                    + " 已存在电价版本，同一日期全站只能有一个生效版本");
        }
    }

    private String buildOverlapMessage(TariffVersion existing, LocalDate date) {
        String who = existing == null ? "" : "（现有版本：「" + existing.getName() + "」）";
        return "生效日期 " + date + " 已存在电价版本" + who
                + "，同一日期全站只能有一个生效版本；请换一个日期登记新版本";
    }

    /**
     * 若 [from, to] 与任何一条 VERSIONED 抄表记录的计费区间 [periodStart, meterDate] 有交叠，
     * 拒绝并列出受影响的日期范围与记录（机柜 / 抄表日 / 计费区间），原版本链不动。
     */
    private void rejectIfInsideBilledPeriods(LocalDate from, LocalDate to) {
        List<EnergyRecord> affected = energyRepository
                .findByBillingTypeAndPeriodStartLessThanEqualAndMeterDateGreaterThanEqual(
                        EnergyRecord.BILLING_VERSIONED, to, from);
        if (affected.isEmpty()) {
            return;
        }
        affected.sort(Comparator.comparing(EnergyRecord::getMeterDate));
        StringBuilder detail = new StringBuilder();
        int limit = Math.min(affected.size(), 5);
        for (int i = 0; i < limit; i++) {
            EnergyRecord row = affected.get(i);
            String code = cabinetRepository.findById(row.getCabinetId())
                    .map(cabinet -> cabinet.getCode()).orElse("机柜#" + row.getCabinetId());
            if (detail.length() > 0) {
                detail.append("；");
            }
            detail.append(code).append(' ')
                    .append(row.getPeriodStart()).append("~").append(row.getMeterDate())
                    .append("（").append(row.getMeterDate()).append(" 抄表）");
        }
        if (affected.size() > limit) {
            detail.append(" 等 ").append(affected.size()).append(" 条");
        }
        throw new BizException("生效日期 " + from + " 会插入已经形成费用的抄表计费区间，受影响区间："
                + detail + "。已生效费用不能因版本调整重算，版本链与费用保持不变；"
                + "请选择不与上述区间交叠的日期，由新版本在其后接续");
    }
}
