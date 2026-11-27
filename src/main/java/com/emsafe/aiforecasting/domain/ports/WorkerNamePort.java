package com.emsafe.aiforecasting.domain.ports;

import java.util.Map;
import java.util.Set;

/**
 * Nombres de los operarios de una organización (solo lectura). Se usan únicamente para los textos
 * finales que lee el SSO; nunca se envían al LLM. Los IDs sin nombre, o de otra organización, no
 * aparecen en el resultado.
 */
public interface WorkerNamePort {

    Map<Long, String> namesOf(Long organizationId, Set<Long> workerIds);
}
