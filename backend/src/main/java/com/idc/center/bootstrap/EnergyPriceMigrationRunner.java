package com.idc.center.bootstrap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 分段电价功能的启动期兼容：
 *  1. 为旧能耗表补计费方式 / 区间列，旧记录统一标记 LEGACY；
 *  2. 建电价版本和分段快照表；
 *  3. 旧库没有版本时补一条覆盖当前计费的基线版本，供新抄表开始使用版本化计费。
 */
@Component
public class EnergyPriceMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(EnergyPriceMigrationRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public EnergyPriceMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        addEnergyColumn("billing_type",
                "ALTER TABLE idc_energy ADD COLUMN billing_type VARCHAR(20) NULL COMMENT 'LEGACY 历史固定计费 / VERSIONED 版本化分段计费' AFTER usage_kwh");
        addEnergyColumn("period_start",
                "ALTER TABLE idc_energy ADD COLUMN period_start DATE NULL COMMENT '版本化计费区间起点（上次抄表日次日）' AFTER billing_type");
        jdbcTemplate.update("UPDATE idc_energy SET billing_type = 'LEGACY' WHERE billing_type IS NULL");
        jdbcTemplate.update("ALTER TABLE idc_energy MODIFY billing_type VARCHAR(20) NOT NULL COMMENT 'LEGACY 历史固定计费 / VERSIONED 版本化分段计费'");
        jdbcTemplate.update("ALTER TABLE idc_energy MODIFY unit_price DECIMAL(10,4) NULL COMMENT '电价（元/kWh）；版本化记录为兼容列表的主展示价'");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS idc_energy_price_version (
                    id              BIGINT NOT NULL AUTO_INCREMENT,
                    name            VARCHAR(50)  NOT NULL COMMENT '版本名称',
                    effective_date  DATE         NOT NULL COMMENT '生效日期',
                    base_unit_price DECIMAL(10,4) NOT NULL COMMENT '基础单价（元/kWh）',
                    billing_note    VARCHAR(300) NOT NULL COMMENT '计费说明',
                    enabled_by      VARCHAR(30)  NOT NULL COMMENT '启用人',
                    created_at      DATETIME              COMMENT '登记时间',
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_energy_price_date (effective_date)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='全站电价版本'
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS idc_energy_price_segment (
                    id               BIGINT NOT NULL AUTO_INCREMENT,
                    energy_record_id BIGINT NOT NULL COMMENT '抄表记录',
                    segment_order    INT NOT NULL COMMENT '分段顺序',
                    start_date       DATE NOT NULL COMMENT '分段开始日期',
                    end_date         DATE NOT NULL COMMENT '分段结束日期',
                    day_count        INT NOT NULL COMMENT '自然日数',
                    usage_kwh        DECIMAL(12,2) NOT NULL COMMENT '分摊用电量',
                    price_version_id BIGINT NOT NULL COMMENT '电价版本ID快照',
                    version_name     VARCHAR(50) NOT NULL COMMENT '版本名称快照',
                    effective_date   DATE NOT NULL COMMENT '版本生效日快照',
                    unit_price       DECIMAL(10,4) NOT NULL COMMENT '采用单价快照',
                    segment_fee      DECIMAL(12,2) NOT NULL COMMENT '分段费用',
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_energy_segment_order (energy_record_id, segment_order),
                    KEY idx_energy_segment_record (energy_record_id),
                    KEY idx_energy_segment_dates (start_date, end_date)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='能耗账单分段计费快照'
                """);
        jdbcTemplate.update("""
                UPDATE idc_energy e
                   SET e.unit_price = 0.6800
                 WHERE e.billing_type = 'LEGACY' AND e.unit_price IS NULL
                """);
        addUniqueCabinetDateIndexIfClean();
        addIndexIfMissing("idc_energy", "idx_energy_period",
                "ALTER TABLE idc_energy ADD KEY idx_energy_period (billing_type, period_start, meter_date)");

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idc_energy_price_version", Integer.class);
        if (versionCount != null && versionCount == 0) {
            LocalDate baselineDate = jdbcTemplate.query("""
                    SELECT MIN(meter_date) FROM idc_energy
                    """, rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null);
            if (baselineDate == null) {
                baselineDate = LocalDate.now();
            }
            jdbcTemplate.update("""
                    INSERT INTO idc_energy_price_version
                        (name, effective_date, base_unit_price, billing_note, enabled_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    "历史默认电价",
                    baselineDate,
                    BigDecimal.valueOf(0.6800),
                    "兼容历史固定计费后的基线电价；从功能启用后的第一条新抄表开始按版本化分段计费，历史账单不反推价段。",
                    "系统迁移",
                    LocalDateTime.now());
            log.info("已补分段电价基线版本：{} 起 0.6800 元/kWh", baselineDate);
        }
    }

    private void addEnergyColumn(String column, String ddl) {
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'idc_energy' AND column_name = ?
                """, Integer.class, column);
        if (exists != null && exists == 0) {
            jdbcTemplate.execute(ddl);
            log.info("已为 idc_energy 补列：{}", column);
        }
    }

    private void addIndexIfMissing(String table, String index, String ddl) {
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                """, Integer.class, table, index);
        if (exists != null && exists == 0) {
            jdbcTemplate.execute(ddl);
            log.info("已为 {} 补索引：{}", table, index);
        }
    }

    private void addUniqueCabinetDateIndexIfClean() {
        Integer indexExists = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'idc_energy'
                   AND index_name = 'uk_energy_cabinet_date'
                """, Integer.class);
        if (indexExists != null && indexExists > 0) {
            return;
        }
        Integer duplicates = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM (
                    SELECT cabinet_id, meter_date
                      FROM idc_energy
                     WHERE cabinet_id IS NOT NULL AND meter_date IS NOT NULL
                     GROUP BY cabinet_id, meter_date
                    HAVING COUNT(1) > 1
                ) duplicated
                """, Integer.class);
        if (duplicates != null && duplicates > 0) {
            log.warn("idc_energy 存在 {} 组机柜+抄表日期重复数据，跳过唯一索引；业务层仍会阻止新增重复记录", duplicates);
            return;
        }
        jdbcTemplate.execute("ALTER TABLE idc_energy ADD UNIQUE KEY uk_energy_cabinet_date (cabinet_id, meter_date)");
        log.info("已为 idc_energy 补唯一索引：uk_energy_cabinet_date");
    }
}
