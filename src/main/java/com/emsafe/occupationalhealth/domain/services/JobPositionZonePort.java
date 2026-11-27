package com.emsafe.occupationalhealth.domain.services;

import java.util.Optional;

/** Zona de planta a la que pertenece un puesto de trabajo (Plant Configuration). */
public interface JobPositionZonePort {
    Optional<Long> zoneOf(Long organizationId, Long jobPositionId);
}
