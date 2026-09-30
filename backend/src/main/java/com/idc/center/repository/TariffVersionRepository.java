package com.idc.center.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.idc.center.domain.TariffVersion;

import jakarta.persistence.LockModeType;

/**
 * 电价版本：全站一套版本链。生效日唯一（uk_tariff_effective_date）。
 */
@Repository
public interface TariffVersionRepository extends JpaRepository<TariffVersion, Long> {

    List<TariffVersion> findAllByOrderByEffectiveDateAsc();

    Optional<TariffVersion> findByEffectiveDate(LocalDate effectiveDate);

    boolean existsByEffectiveDate(LocalDate effectiveDate);

    /**
     * 版本链串行锁：所有「改版本」与「抄表落账」事务先锁版本链，
     * 保证两名运维几乎同时启用相邻版本时最终版本链唯一且一致。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TariffVersion t order by t.effectiveDate asc")
    List<TariffVersion> lockChain();

    /** 生效日早于指定日期、且生效日严格最近的版本（区间拆分时取价段） */
    @Query("select t from TariffVersion t where t.effectiveDate <= :date order by t.effectiveDate desc")
    List<TariffVersion> findEffectiveOnOrBefore(@Param("date") LocalDate date);
}
