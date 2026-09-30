package com.idc.center.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;

/**
 * 模块五：能耗记录。抄表日期 + 电表读数 + 用电量 + 电价 → 电费。
 *
 * 计费口径 billing_type：
 *   LEGACY_FIXED 历史固定计费：功能启用前的老记录，保留当时手填单价与费用，不反推价段；
 *   VERSIONED    跨价段计费：功能启用后的新记录，按版本生效日把区间用电量拆段，
 *                分段明细固化在 {@link EnergyTariffSegment}，本表 fee 始终等于分段费用之和。
 */
@Entity
@Table(name = "idc_energy")
@DynamicUpdate
@Data
public class EnergyRecord {

    /** 历史固定计费（老数据，单价费用冻结，不做价段拆分） */
    public static final String BILLING_LEGACY_FIXED = "LEGACY_FIXED";
    /** 跨价段计费（新数据，费用由分段快照汇总） */
    public static final String BILLING_VERSIONED = "VERSIONED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cabinet_id")
    private Long cabinetId;

    /** 抄表日期 */
    @Column(name = "meter_date")
    private LocalDate meterDate;

    /** 电表读数（kWh，累计值） */
    @Column(name = "meter_reading", precision = 12, scale = 2)
    private BigDecimal meterReading;

    /** 本期用电量（kWh） */
    @Column(name = "usage_kwh", precision = 12, scale = 2)
    private BigDecimal usageKwh;

    /** 电价（元/kWh）：历史记录为当时手填单价；分段记录为按费用反算的综合均价，仅作展示 */
    @Column(name = "unit_price", precision = 6, scale = 3)
    private BigDecimal unitPrice;

    /** 电费（元）：历史 = 用电量 × 单价；分段 = 各价段费用之和 */
    @Column(name = "fee", precision = 12, scale = 2)
    private BigDecimal fee;

    /** 抄表人 */
    @Column(name = "meter_reader", length = 30)
    private String meterReader;

    @Column(name = "remark", length = 200)
    private String remark;

    /** 计费口径：LEGACY_FIXED 历史固定 / VERSIONED 跨价段 */
    @Column(name = "billing_type", length = 20)
    private String billingType;

    /** 计费区间起始日（前一次抄表日次日）；历史首条无上期读数时为空 */
    @Column(name = "period_start")
    private LocalDate periodStart;

    /** 跨价段计费的分段明细（非表字段，查询时按 id 批量挂载） */
    @Transient
    private List<EnergyTariffSegment> tariffSegments;
}
