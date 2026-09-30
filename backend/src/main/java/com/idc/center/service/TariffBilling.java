package com.idc.center.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.idc.center.common.BizException;
import com.idc.center.domain.EnergyTariffSegment;
import com.idc.center.domain.TariffVersion;

/**
 * 跨价段计费引擎（无状态，可被「预览」与「落账」共用，保证页面预览与确认后明细完全一致）。
 *
 * 区间口径：[periodStart, meterDate] 闭区间，periodStart = 前一次抄表日次日。
 * 按区间内自然日归属价段：某一天落在哪个版本的生效区间 [v.effectiveDate, 下一版本生效日 - 1]，
 * 当天用电就归哪个版本。切换日整天归新版本，不切小时，因此同一度电不会跨段重复或遗漏。
 *
 * 开放区间兜底：功能刚启用 / 老库升级时，版本链可能晚于「最后一条老抄表之后的开放区间」
 * 才建立。启用后第一条新抄表的区间起点若早于首版本生效日，早于的天数（尚无版本登记的
 * 开放区间）按首个（即启用时最新有效）版本计价 —— 与「开放区间按最新有效版本计算」一致；
 * 这些天一经抄表闭合即固化，之后不能再往首版之前插入版本（TariffService 会拒绝）。
 *
 * 分摊：总用电量 × 段天数 / 区间总天数。前 n−1 段每段四舍五入到分，最后一段用
 * 「总用电量 − 前面各段」兜底，保证 Σ 分摊电量 == 本期用电量（不丢不重）。
 * 费用：各段分摊电量 × 版本单价后各自四舍五入，记录总费用 = 分段费用之和。
 */
public final class TariffBilling {

    private TariffBilling() {
    }

    /** 一个价段的计算中间结果（预览与落账共用） */
    public static class Slice {
        public TariffVersion version;
        public LocalDate start;
        public LocalDate end;
        public int days;
        public BigDecimal allocKwh;
        public BigDecimal fee;
    }

    /**
     * 计算区间拆分。
     *
     * @param chain       版本链（按生效日升序）
     * @param periodStart 区间起始日（含）
     * @param meterDate   区间结束日 / 本次抄表日（含）
     * @param usageKwh    区间总用电量
     */
    public static List<Slice> split(List<TariffVersion> chain, LocalDate periodStart,
                                    LocalDate meterDate, BigDecimal usageKwh) {
        if (periodStart == null) {
            throw new BizException("缺少计费区间起始日，无法进行跨价段计费");
        }
        if (meterDate.isBefore(periodStart)) {
            throw new BizException("抄表日期不能早于计费区间起始日（" + periodStart + "）");
        }
        if (chain.isEmpty()) {
            throw new BizException("尚未登记任何电价版本，该区间没有可适用的电价版本，请先登记版本");
        }

        long totalDays = ChronoUnit.DAYS.between(periodStart, meterDate) + 1;

        // 与区间相交的版本段：版本 i 的生效区间为 [v_i.date, v_{i+1}.date − 1]，末个版本开放。
        // 首版本对「早于其生效日」的开放区间天数向前兜底：启用后的第一条新抄表可能跨在
        // 「最后一条老抄表 ~ 首版本生效日」之间，这些天按启用的首版本（最新有效版本）计价。
        List<Slice> slices = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            TariffVersion version = chain.get(i);
            LocalDate segFrom;
            if (i == 0) {
                segFrom = periodStart;
            } else {
                segFrom = version.getEffectiveDate().isAfter(periodStart)
                        ? version.getEffectiveDate() : periodStart;
            }
            LocalDate nextDate = i + 1 < chain.size() ? chain.get(i + 1).getEffectiveDate() : null;
            LocalDate segTo = nextDate == null ? meterDate : nextDate.minusDays(1);
            if (segTo.isAfter(meterDate)) {
                segTo = meterDate;
            }
            if (segFrom.isAfter(segTo)) {
                continue;
            }
            int days = (int) (ChronoUnit.DAYS.between(segFrom, segTo) + 1);
            Slice slice = new Slice();
            slice.version = version;
            slice.start = segFrom;
            slice.end = segTo;
            slice.days = days;
            slices.add(slice);
            if (!segTo.isBefore(meterDate)) {
                break;
            }
        }

        if (slices.isEmpty()) {
            throw new BizException("区间 " + periodStart + " ~ " + meterDate + " 未命中任何电价版本");
        }

        long covered = slices.stream().mapToLong(s -> s.days).sum();
        if (covered != totalDays) {
            // 版本链连续不交叠时不可能发生，出现即说明版本链有空档
            throw new BizException("电价版本链在 " + periodStart + " ~ " + meterDate
                    + " 之间存在空档（仅覆盖 " + covered + "/" + totalDays + " 天），请先补齐版本");
        }

        // 按天比例分摊：前 n−1 段独立舍入，末段兜底，保证 Σ 分摊 == 总用电量
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < slices.size(); i++) {
            Slice slice = slices.get(i);
            BigDecimal alloc;
            if (i == slices.size() - 1) {
                alloc = usageKwh.subtract(allocated).setScale(2, RoundingMode.HALF_UP);
            } else {
                alloc = usageKwh.multiply(BigDecimal.valueOf(slice.days))
                        .divide(BigDecimal.valueOf(totalDays), 2, RoundingMode.HALF_UP);
                allocated = allocated.add(alloc);
            }
            slice.allocKwh = alloc;
            slice.fee = alloc.multiply(slice.version.getBasePrice()).setScale(2, RoundingMode.HALF_UP);
        }
        return slices;
    }

    /** 把计算结果落成抄表分段快照（确认抄表时调用） */
    public static List<EnergyTariffSegment> toSegments(Long energyId, List<Slice> slices) {
        List<EnergyTariffSegment> segments = new ArrayList<>();
        int no = 1;
        for (Slice slice : slices) {
            EnergyTariffSegment segment = new EnergyTariffSegment();
            segment.setEnergyId(energyId);
            segment.setTariffVersionId(slice.version.getId());
            segment.setTariffName(slice.version.getName());
            segment.setUnitPrice(slice.version.getBasePrice());
            segment.setSegStart(slice.start);
            segment.setSegEnd(slice.end);
            segment.setDayCount(slice.days);
            segment.setAllocKwh(slice.allocKwh);
            segment.setSegFee(slice.fee);
            segment.setSegNo(no++);
            segments.add(segment);
        }
        return segments;
    }

    /** 分段费用合计 */
    public static BigDecimal totalFee(List<Slice> slices) {
        return slices.stream().map(s -> s.fee).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
