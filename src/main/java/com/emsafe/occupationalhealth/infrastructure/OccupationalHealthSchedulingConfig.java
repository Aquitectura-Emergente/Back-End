package com.emsafe.occupationalhealth.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita las tareas programadas, que este contexto es el primero en
 * necesitar (RetentionJob).
 *
 * Vive aquí y no en EmsafeApplication para que el motivo quede junto a su único
 * usuario: si algún día desaparece la retención, se ve claro que esta
 * configuración se puede ir con ella. @EnableScheduling es global, así que
 * cualquier @Scheduled que se añada en otro contexto también empezará a correr.
 */
@Configuration
@EnableScheduling
public class OccupationalHealthSchedulingConfig {
}
