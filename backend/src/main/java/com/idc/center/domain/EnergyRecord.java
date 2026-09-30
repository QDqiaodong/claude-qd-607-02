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
 */
@Entity
@Table(name = "idc_energy")
@DynamicUpdate
@Data
public class EnergyRecord {

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

    /**
     * 计费方式：
     * LEGACY 历史固定计费（旧数据保留手填单价和费用）
     * VERSIONED 版本化分段计费（账单形成时固化分段快照）
     */
    @Column(name = "billing_type", length = 20, nullable = false)
    private String billingType = "LEGACY";

    /** 拆分区间起点：上次抄表日次日 */
    @Column(name = "period_start")
    private LocalDate periodStart;

    /** 电价（元/kWh）；版本化记录为兼容列表的主展示价，真实费用以分段为准 */
    @Column(name = "unit_price", precision = 10, scale = 4)
    private BigDecimal unitPrice;

    /** 电费（元）= 历史：用电量 × 电价；版本化：各分段费用汇总 */
    @Column(name = "fee", precision = 12, scale = 2)
    private BigDecimal fee;

    /** 抄表人 */
    @Column(name = "meter_reader", length = 30)
    private String meterReader;

    @Column(name = "remark", length = 200)
    private String remark;

    /** 版本化计费展开明细 */
    @Transient
    private List<EnergyPriceSegment> priceSegments;
}
