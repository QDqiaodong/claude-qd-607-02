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
 * 抄表账单的电价分段快照。形成账单时固化版本信息，后续电价调整不回改。
 */
@Entity
@Table(name = "idc_energy_price_segment")
@Data
public class EnergyPriceSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "energy_record_id", nullable = false)
    private Long energyRecordId;

    /** 分段顺序，从 1 开始 */
    @Column(name = "segment_order", nullable = false)
    private Integer segmentOrder;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** 该段自然日数 */
    @Column(name = "day_count", nullable = false)
    private Integer dayCount;

    @Column(name = "usage_kwh", precision = 12, scale = 2, nullable = false)
    private BigDecimal usageKwh;

    @Column(name = "price_version_id", nullable = false)
    private Long priceVersionId;

    /** 版本快照 */
    @Column(name = "version_name", length = 50, nullable = false)
    private String versionName;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "unit_price", precision = 10, scale = 4, nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "segment_fee", precision = 12, scale = 2, nullable = false)
    private BigDecimal segmentFee;
}
