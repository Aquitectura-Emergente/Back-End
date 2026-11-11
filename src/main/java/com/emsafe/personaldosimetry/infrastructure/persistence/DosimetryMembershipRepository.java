package com.emsafe.personaldosimetry.infrastructure.persistence;

import com.emsafe.personaldosimetry.domain.model.DosimetryMembership;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DosimetryMembershipRepository extends JpaRepository<DosimetryMembership, Long> {}
