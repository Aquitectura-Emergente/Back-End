package com.emsafe.personaldosimetry.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

// El reloj de este contexto pasó a ser el primario de shared/config/ClockConfig: aquí declaraba
// un Clock sin cualificar que chocaba con el de aiforecasting y tumbaba el arranque.
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DosimetryRetentionProperties.class)
public class DosimetryConfiguration {
}
