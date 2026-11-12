package com.emsafe.aiforecasting.application;

import java.util.Optional;

/**
 * Traduce el usuario autenticado a un {@link ForecastingActor}. Hoy se implementa leyendo IAM
 * de {@code develop}; cuando llegue {@code feature/iam} se cambia solo este adaptador.
 */
@FunctionalInterface
public interface ForecastingActorDirectory {

    Optional<ForecastingActor> findByUsername(String username);
}
