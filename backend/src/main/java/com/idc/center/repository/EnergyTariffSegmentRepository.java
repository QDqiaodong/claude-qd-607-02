package com.idc.center.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.EnergyTariffSegment;

/**
 * 抄表分段计费快照：随抄表记录同事务写入，之后只读。
 */
@Repository
public interface EnergyTariffSegmentRepository extends JpaRepository<EnergyTariffSegment, Long> {

    List<EnergyTariffSegment> findByEnergyIdInOrderByEnergyIdAscSegNoAsc(List<Long> energyIds);

    List<EnergyTariffSegment> findByEnergyIdOrderBySegNoAsc(Long energyId);

    long countByTariffVersionId(Long tariffVersionId);
}
