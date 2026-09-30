package com.idc.center.bootstrap;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.idc.center.domain.ServerAsset;
import com.idc.center.repository.ServerAssetRepository;
import com.idc.center.service.LegacyPlacementRepair;

import jakarta.annotation.PostConstruct;

/**
 * 启动期数据兼容（幂等，可安全反复执行 / 服务重启后再跑）：
 *   1. 老库补历史位置快照列 src_cabinet_id / src_start_u；
 *   2. 用【已完成下架工单】的 cabinet_id / target_u 回填快照列；
 *   3. 仅对「能由历史下架工单明确确认」的遗留脏位置（OFFLINE 但仍挂原柜原 U）做安全纠正，
 *      维修中 / 待拆解 / 没有完成下架记录的服务器一律不动（判定在 LegacyPlacementRepair）。
 *   4. 电价版本化计费升级：补 idc_tariff_version / idc_energy_tariff_segment 两张表、
 *      补 idc_energy 的 billing_type / period_start 列；已有抄表全部标记 LEGACY_FIXED
 *      （保留当时单价与费用，不反推价段）；电价版本表为空时按老默认价补一个首版本，
 *      版本功能从该版本生效日起对新抄表启用。
 *
 * 新库由 schema.sql 直接建好表、列与种子数据，这套逻辑跑过去什么都不改。
 */
@Component
public class DataMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationRunner.class);

    private final JdbcTemplate jdbcTemplate;
    private final ServerAssetRepository serverRepository;
    private final LegacyPlacementRepair legacyPlacementRepair;

    public DataMigrationRunner(JdbcTemplate jdbcTemplate,
                               ServerAssetRepository serverRepository,
                               LegacyPlacementRepair legacyPlacementRepair) {
        this.jdbcTemplate = jdbcTemplate;
        this.serverRepository = serverRepository;
        this.legacyPlacementRepair = legacyPlacementRepair;
    }

    @PostConstruct
    public void migrate() {
        addColumnIfMissing("idc_work_order", "src_cabinet_id",
                "ALTER TABLE idc_work_order ADD COLUMN src_cabinet_id BIGINT NULL COMMENT '历史位置快照：作业发起时所在机柜' AFTER cabinet_id");
        addColumnIfMissing("idc_work_order", "src_start_u",
                "ALTER TABLE idc_work_order ADD COLUMN src_start_u INT NULL COMMENT '历史位置快照：作业发起时起始 U 位' AFTER target_u");

        // 只回填下架工单：它的 cabinet_id / target_u 语义就是下架前位置；迁移老单的原位置无从得知，留空不猜
        int backfilled = jdbcTemplate.update("""
                UPDATE idc_work_order
                   SET src_cabinet_id = cabinet_id, src_start_u = target_u
                 WHERE order_type = 'OFF' AND src_cabinet_id IS NULL
                """);
        if (backfilled > 0) {
            log.info("历史下架工单位置快照回填完成：{} 张", backfilled);
        }

        int repaired = 0;
        List<ServerAsset> candidates = serverRepository.findAll().stream()
                .filter(server -> "OFFLINE".equals(server.getStatus())
                        && server.getCabinetId() != null && server.getStartU() != null)
                .toList();
        for (ServerAsset candidate : candidates) {
            try {
                repaired += legacyPlacementRepair.repairOne(candidate.getId());
            } catch (RuntimeException e) {
                log.warn("遗留脏位置修复跳过 {}：{}", candidate.getAssetNo(), e.getMessage());
            }
        }
        if (!candidates.isEmpty()) {
            log.info("遗留脏位置检查完成：OFFLINE 但仍带位置的服务器 {} 台，其中凭历史下架工单安全纠正 {} 台，其余保留不动",
                    candidates.size(), repaired);
        }

        migrateTariffBilling();
    }

    /** 电价版本化计费的老库兼容升级（幂等） */
    private void migrateTariffBilling() {
        createTableIfMissing("idc_tariff_version", """
                CREATE TABLE idc_tariff_version (
                    id             BIGINT       NOT NULL AUTO_INCREMENT,
                    name           VARCHAR(50)  NOT NULL COMMENT '版本名称',
                    effective_date DATE         NOT NULL COMMENT '生效日期（当天起按本版本计价）',
                    base_price     DECIMAL(6,3) NOT NULL COMMENT '基础单价（元/kWh）',
                    billing_note   VARCHAR(300) NOT NULL COMMENT '计费说明',
                    enabled_by     VARCHAR(30)  NOT NULL COMMENT '启用人',
                    created_at     DATETIME              COMMENT '登记时间',
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_tariff_effective_date (effective_date)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='电价版本'
                """);
        createTableIfMissing("idc_energy_tariff_segment", """
                CREATE TABLE idc_energy_tariff_segment (
                    id                BIGINT        NOT NULL AUTO_INCREMENT,
                    energy_id         BIGINT        NOT NULL COMMENT '所属抄表记录',
                    tariff_version_id BIGINT        NOT NULL COMMENT '采用的电价版本',
                    tariff_name       VARCHAR(50)            COMMENT '版本名称快照',
                    unit_price        DECIMAL(6,3)           COMMENT '单价快照（元/kWh）',
                    seg_start         DATE                   COMMENT '本价段覆盖起始日（含）',
                    seg_end           DATE                   COMMENT '本价段覆盖结束日（含）',
                    day_count         INT                    COMMENT '本价段天数（自然日，含首尾）',
                    alloc_kwh         DECIMAL(12,2)          COMMENT '分摊用电量（kWh）',
                    seg_fee           DECIMAL(12,2)          COMMENT '分段费用（元）= 分摊用电量 × 单价',
                    seg_no            INT                    COMMENT '段内行序（按日期升序）',
                    PRIMARY KEY (id),
                    KEY idx_seg_energy (energy_id),
                    KEY idx_seg_version (tariff_version_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='抄表分段计费快照'
                """);

        addColumnIfMissing("idc_energy", "billing_type",
                "ALTER TABLE idc_energy ADD COLUMN billing_type VARCHAR(20) NULL COMMENT 'LEGACY_FIXED 历史固定计费 / VERSIONED 跨价段计费' AFTER remark");
        addColumnIfMissing("idc_energy", "period_start",
                "ALTER TABLE idc_energy ADD COLUMN period_start DATE NULL COMMENT '计费区间起始日（前一次抄表日次日），首条为空' AFTER billing_type");

        // 老抄表一律标记历史固定计费：金额、单价原样保留，不反推价段
        Integer marked = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idc_energy WHERE billing_type IS NULL OR billing_type = ''",
                Integer.class);
        if (marked != null && marked > 0) {
            int updated = jdbcTemplate.update(
                    "UPDATE idc_energy SET billing_type = 'LEGACY_FIXED' WHERE billing_type IS NULL OR billing_type = ''");
            log.info("电价版本化升级：{} 条历史抄表标记为 LEGACY_FIXED（历史固定计费，金额不变）", updated);
        }

        // 老库首次升级、且还没有任何电价版本时：沿用老默认价 0.68 补一个首版本（今天生效），
        // 之后新抄表从首版本起走 VERSIONED；历史记录因 LEGACY_FIXED 标记不受任何影响。
        Integer versionCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM idc_tariff_version", Integer.class);
        if (versionCount != null && versionCount == 0) {
            jdbcTemplate.update("""
                    INSERT INTO idc_tariff_version (name, effective_date, base_price, billing_note, enabled_by, created_at)
                    VALUES (?, ?, ?, ?, ?, NOW())
                    """, "默认基础电价（升级前电价）", LocalDate.now(),
                    new java.math.BigDecimal("0.680"),
                    "系统升级电价版本化计费时按升级前默认电价自动建立，沿用 0.68 元/kWh；后续价格调整请登记新版本接续",
                    "系统升级");
            log.info("电价版本化升级：已按升级前默认电价 0.68 建立首个电价版本（{} 生效）", LocalDate.now());
        }
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        if (exists != null && exists == 0) {
            jdbcTemplate.execute(ddl);
            log.info("已为 {} 补列：{}", table, column);
        }
    }

    private void createTableIfMissing(String table, String ddl) {
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name = ?
                """, Integer.class, table);
        if (exists != null && exists == 0) {
            jdbcTemplate.execute(ddl);
            log.info("已创建升级所需表：{}", table);
        }
    }
}
