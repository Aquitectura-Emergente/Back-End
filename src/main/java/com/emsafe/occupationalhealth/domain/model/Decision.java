package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * La decisión humana sobre una medida (informe §5.6.1).
 *
 * Su existencia es la prueba de que alguien decidió: ninguna acción se aplica
 * sin ella (regla 1). Que el autor sea realmente un SSO de la organización se
 * comprueba antes, en MitigationAccessPolicy — el dominio no conoce IAM.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Decision {

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String reason;

    /** Decisión a favor. El motivo es opcional: aprobar no exige justificarse. */
    public static Decision approvedBy(Long ssoUserId, String reason) {
        return new Decision(requireAuthor(ssoUserId), LocalDateTime.now(), blankToNull(reason));
    }

    /**
     * Decisión en contra. El motivo es obligatorio: un rechazo sin razón no
     * sirve como evidencia en una auditoría.
     */
    public static Decision rejectedBy(Long ssoUserId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new MitigationRuleException("A reason is required to reject a mitigation action");
        }
        return new Decision(requireAuthor(ssoUserId), LocalDateTime.now(), reason.trim());
    }

    private static Long requireAuthor(Long ssoUserId) {
        if (ssoUserId == null) {
            throw new MitigationRuleException("The decision author is required");
        }
        return ssoUserId;
    }

    private static String blankToNull(String reason) {
        return (reason == null || reason.isBlank()) ? null : reason.trim();
    }
}
