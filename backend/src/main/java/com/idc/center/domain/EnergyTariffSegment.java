package com.idc.center.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 抄表记录的分段计费快照：一条 VERSIONED 抄表记录按区间内自然日拆到各电价版本，
 * 每个价段一行。版本名称 / 单价在抄表确认时一并固化，之后电价版本如何调整都不再影响本行。
 */
@Entity
@Table(name = "idc_energy_tariff_segment")
@Data
public class EnergyTariffSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属抄表记录 */
    @Column(name = "energy_id")
    private Long energyId;

    /** 采用的电价版本（快照后即使版本被删除也保留） */
    @Column(name = "tariff_version_id")
    private Long tariffVersionId;

    /** 版本名称快照 */
    @Column(name = "tariff_name", length = 50)
    private String tariffName;

    /** 单价快照（元/kWh） */
    @Column(name = "unit_price", precision = 6, scale = 3)
    private BigDecimal unitPrice;

    /** 本价段覆盖起始日（含） */
    @Column(name = "seg_start")
    private LocalDate segStart;

    /** 本价段覆盖结束日（含） */
    @Column(name = "seg_end")
    private LocalDate segEnd;

    /** 本价段天数（自然日，含首尾） */
    @Column(name = "day_count")
    private Integer dayCount;

    /** 分摊到本价段的用电量（kWh） */
    @Column(name = "alloc_kwh", precision = 12, scale = 2)
    private BigDecimal allocKwh;

    /** 本价段费用（元）= 分摊用电量 × 版本单价 */
    @Column(name = "seg_fee", precision = 12, scale = 2)
    private BigDecimal segFee;

    /** 段内行序，按日期升序 */
    @Column(name = "seg_no")
    private Integer segNo;
}
