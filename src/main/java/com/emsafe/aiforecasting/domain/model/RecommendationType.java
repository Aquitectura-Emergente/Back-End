package com.emsafe.aiforecasting.domain.model;

public enum RecommendationType {
    /** Intercambio de puestos con un candidato viable. */
    ROTATION,
    /** Sin candidato viable: reprogramar la tarea expuesta. */
    RESCHEDULE_TASK
}
