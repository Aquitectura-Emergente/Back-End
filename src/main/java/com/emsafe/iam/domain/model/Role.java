package com.emsafe.iam.domain.model;

public enum Role {
    ADMIN,
    TECHNICIAN,
    CLIENT,
    /** Operario de una organización industrial. Solo ve sus propios datos. */
    WORKER,
    /** Responsable de SSO: consulta a los operarios de SU organización. */
    SSO_RESPONSIBLE;

    /** Cuentas industriales: obligatoriamente ligadas a una organización. */
    public boolean isIndustrial() {
        return this == WORKER || this == SSO_RESPONSIBLE;
    }

    /** Nombre mostrado al front ("Admin", "Technician", "Worker", "SsoResponsible"...). */
    public String displayName() {
        return switch (this) {
            case ADMIN -> "Admin";
            case TECHNICIAN -> "Technician";
            case CLIENT -> "Client";
            case WORKER -> "Worker";
            case SSO_RESPONSIBLE -> "SsoResponsible";
        };
    }
}
