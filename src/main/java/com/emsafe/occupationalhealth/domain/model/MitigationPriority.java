package com.emsafe.occupationalhealth.domain.model;

/**
 * Orden en la bandeja de acciones pendientes (informe §5.6.1, regla 10).
 *
 * Una propuesta cuyo aviso se escaló al SSO sin que el operario lo acusara sube
 * a HIGH y se muestra primero. Este contexto no decide el escalamiento: solo
 * reacciona a la evidencia que publica Alerts.
 */
public enum MitigationPriority {
    NORMAL,
    HIGH
}
