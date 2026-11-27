package com.emsafe.occupationalhealth.domain.services;

import java.util.Optional;

/**
 * Nombres para presentar un reporte: quién es el trabajador, quién lo generó y
 * cómo se llama cada zona. Si no se conocen, el reporte muestra la referencia.
 */
public interface ReportNamesPort {
    Optional<String> personName(Long userId);

    Optional<String> zoneName(Long zoneId);
}
