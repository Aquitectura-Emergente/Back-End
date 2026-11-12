package com.emsafe.occupationalhealth.infrastructure;

import com.emsafe.occupationalhealth.domain.model.MitigationAuditEntry;
import com.emsafe.occupationalhealth.infrastructure.persistence.MitigationAuditEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra decisiones, exportaciones y accesos denegados
 * (informe §5.6.4, MitigationAuditAdapter; RNF10, RNF11 y Ley 29733).
 *
 * El informe justifica este registro como una de las medidas de seguridad que
 * la ley exige para datos sensibles, y como la prueba que el empleador necesita
 * para demostrar cumplimiento en seguridad y salud. Por eso no copia valores de
 * exposición: anota quién hizo qué sobre qué recurso y con qué resultado.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MitigationAuditAdapter {

    private final MitigationAuditEntryRepository auditLog;

    /**
     * Anota una operación concedida. Va en la transacción del caso de uso a
     * propósito: si la decisión se deshace, su registro también — no debe quedar
     * constancia de una aprobación que nunca ocurrió.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordGranted(Long organizationId, Long actorId, String resourceType,
                              Object resourceId, String action, String detail) {
        save(organizationId, actorId, resourceType, resourceId, action,
                MitigationAuditEntry.GRANTED, detail);
    }

    /**
     * Anota un acceso denegado.
     *
     * REQUIRES_NEW es imprescindible: una denegación termina lanzando una
     * excepción que revienta la transacción del caso de uso, y en la misma
     * transacción este registro se iría con ella. Justo el rastro que más
     * importa conservar sería el único que se perdería.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenied(Long organizationId, Long actorId, String resourceType,
                             Object resourceId, String action, String reason) {
        save(organizationId, actorId, resourceType, resourceId, action,
                MitigationAuditEntry.DENIED, reason);
    }

    private void save(Long organizationId, Long actorId, String resourceType, Object resourceId,
                      String action, String outcome, String detail) {
        auditLog.save(MitigationAuditEntry.builder()
                .organizationId(organizationId)
                .actorId(actorId)
                .resourceType(resourceType)
                .resourceId(resourceId != null ? String.valueOf(resourceId) : null)
                .action(action)
                .outcome(outcome)
                .detail(truncate(detail))
                .build());
    }

    /** La columna admite 300 caracteres; un motivo largo se recorta, no revienta. */
    private String truncate(String detail) {
        if (detail == null) return null;
        return detail.length() <= 300 ? detail : detail.substring(0, 297) + "...";
    }
}
