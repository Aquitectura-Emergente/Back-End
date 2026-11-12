package com.emsafe.occupationalhealth.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Rechazo de una medida. El motivo es obligatorio porque un rechazo sin razón
 * no sirve como evidencia; Decision.rejectedBy lo vuelve a exigir en el dominio.
 */
public record RejectMitigationActionRequest(
        @NotBlank(message = "A reason is required to reject a mitigation action") String reason
) {
}
