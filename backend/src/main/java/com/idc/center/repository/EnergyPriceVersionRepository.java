package com.idc.center.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.EnergyPriceVersion;

@Repository
public interface EnergyPriceVersionRepository extends JpaRepository<EnergyPriceVersion, Long> {

    Optional<EnergyPriceVersion> findByEffectiveDate(LocalDate effectiveDate);

    boolean existsByEffectiveDate(LocalDate effectiveDate);

    List<EnergyPriceVersion> findAllByOrderByEffectiveDateAsc();
}
