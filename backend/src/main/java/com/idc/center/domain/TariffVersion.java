package com.idc.center.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 电价版本（全站一套版本链）。
 * 同一日期只能有一个生效版本（effective_date 唯一）；版本链按生效日排序后连续且不交叠，
 * 每个版本的生效区间 = [effective_date, 下一版本 effective_date - 1]，末个版本开放到未来。
 */
@Entity
@Table(name = "idc_tariff_version")
@DynamicUpdate
@Data
public class TariffVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 版本名称，如「2026 秋季电价」 */
    @Column(name = "name", length = 50)
    private String name;

    /** 生效日期（自然日，当天起按本版本计价），全站唯一 */
    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    /** 基础单价（元/kWh） */
    @Column(name = "base_price", precision = 6, scale = 3)
    private BigDecimal basePrice;

    /** 计费说明 */
    @Column(name = "billing_note", length = 300)
    private String billingNote;

    /** 启用人（登记 / 启用该版本的运维） */
    @Column(name = "enabled_by", length = 30)
    private String enabledBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
