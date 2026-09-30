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
 * 全站统一电价版本。版本只登记、不改价；价格变动通过新生效日的新版本接续。
 */
@Entity
@Table(name = "idc_energy_price_version")
@DynamicUpdate
@Data
public class EnergyPriceVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 版本名称 */
    @Column(name = "name", length = 50, nullable = false)
    private String name;

    /** 生效日期（当天自然日起适用） */
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /** 基础单价（元/kWh） */
    @Column(name = "base_unit_price", precision = 10, scale = 4, nullable = false)
    private BigDecimal baseUnitPrice;

    /** 计费说明 */
    @Column(name = "billing_note", length = 300, nullable = false)
    private String billingNote;

    /** 启用人 */
    @Column(name = "enabled_by", length = 30, nullable = false)
    private String enabledBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
