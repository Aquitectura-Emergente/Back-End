package com.emsafe.personaldosimetry.infrastructure.persistence;

import com.emsafe.personaldosimetry.domain.model.PersonalReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.Optional;

public interface PersonalReadingRepository extends JpaRepository<PersonalReading, String> {
    Optional<PersonalReading> findByOrganizationIdAndDeviceIdAndBootIdAndSequence(
            Long organizationId, Long deviceId, String bootId, long sequence);
    boolean existsBySessionIdAndStatusAndCapturedFromLessThanAndCapturedToGreaterThan(
            String sessionId, PersonalReading.Status status, Instant to, Instant from);
    Page<PersonalReading> findBySessionIdOrderByCapturedFromAsc(String sessionId, Pageable pageable);
}
