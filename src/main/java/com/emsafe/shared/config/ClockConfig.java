package com.emsafe.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;

/**
 * Reloj por defecto de la aplicación. Los contextos que solo necesitan "la hora"
 * piden un {@code Clock} sin cualificar y reciben este.
 *
 * <p>Va truncado a microsegundos a propósito: todas las columnas de fecha del esquema son
 * {@code DATETIME(6)}, así que un instante devuelto antes de persistir es idéntico al que se
 * lee después. Con {@code LocalDateTime.now()} (nanosegundos) eso no se cumple y comparar la
 * respuesta de una escritura contra una lectura posterior falla.
 *
 * <p>Es {@code @Primary} porque {@code aiforecasting} registra además su propio reloj
 * ({@code aiForecastingClock}) y lo pide siempre con {@code @Qualifier}. Sin un primario, toda
 * inyección sin cualificar se vuelve ambigua y el contexto de Spring no arranca.
 */
@Configuration
public class ClockConfig {

    @Bean
    @Primary
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
