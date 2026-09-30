package com.idc.center.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.EnergyRecord;

/**
 * 能耗记录：Query by Example（机柜 + 抄表月份区间 + 用电量区间）。
 */
@Repository
public interface EnergyRecordRepository extends JpaRepository<EnergyRecord, Long> {

    List<EnergyRecord> findByCabinetIdOrderByMeterDateAscIdAsc(Long cabinetId);

    /**
     * 已按跨价段计费形成费用的记录中，计费区间与 [from, to] 有交叠的记录。
     * 电价版本调整若改到这些区间内部，会改变历史账单，必须拒绝并指出受影响记录。
     * 区间口径：period_start（含）~ meter_date（含）；period_start 为空的首条不参与。
     */
    List<EnergyRecord> findByBillingTypeAndPeriodStartLessThanEqualAndMeterDateGreaterThanEqual(
            String billingType, LocalDate to, LocalDate from);
}
