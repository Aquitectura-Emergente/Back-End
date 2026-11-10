package com.emsafe.iam.domain.model;

public enum Role {
    ADMIN,
    TECHNICIAN,
    CLIENT,

    // ── Segmento industrial ───────────────────────────────────────────────────
    // Responsable de Seguridad y Salud Ocupacional: el único que puede decidir
    // una medida de mitigación sobre la operación (informe §5.6.1, regla 1).
    SSO,
    // Operario de planta cuya exposición se vigila. Solo consulta sus propios
    // turnos; no decide medidas.
    WORKER
}
