package com.idc.center.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.EnergyRecord;

/**
 * 能耗记录：Query by Example（机柜 + 抄表月份区间 + 用电量区间）。
 */
@Repository
public interface EnergyRecordRepository extends JpaRepository<EnergyRecord, Long> {

    List<EnergyRecord> findByCabinetIdOrderByMeterDateAscIdAsc(Long cabinetId);

    Optional<EnergyRecord> findFirstByCabinetIdAndMeterDateLessThanOrderByMeterDateDescIdDesc(
            Long cabinetId, LocalDate meterDate);

    boolean existsByCabinetIdAndMeterDate(Long cabinetId, LocalDate meterDate);

    /** 区间按 [period_start, meter_date] 判定是否包含某个自然日 */
    List<EnergyRecord> findByBillingTypeAndPeriodStartLessThanEqualAndMeterDateGreaterThanEqual(
            String billingType, LocalDate periodStart, LocalDate meterDate);
}
