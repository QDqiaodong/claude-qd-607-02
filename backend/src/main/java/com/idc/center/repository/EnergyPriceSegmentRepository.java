package com.idc.center.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.EnergyPriceSegment;

@Repository
public interface EnergyPriceSegmentRepository extends JpaRepository<EnergyPriceSegment, Long> {

    List<EnergyPriceSegment> findByEnergyRecordIdInOrderByEnergyRecordIdAscSegmentOrderAsc(Collection<Long> recordIds);

    List<EnergyPriceSegment> findByEnergyRecordIdOrderBySegmentOrderAsc(Long recordId);
}
