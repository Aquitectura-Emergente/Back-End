package com.emsafe.devicemanagement.infrastructure.acl;

import com.emsafe.devicemanagement.application.ports.DosimetrySessionGateway;
import com.emsafe.personaldosimetry.application.DosimetryActor;
import com.emsafe.personaldosimetry.application.PersonalDosimetryService;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.IngestReading;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryResources;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

/**
 * Capa anticorrupción hacia Personal Dosimetry. La lectura se entrega en una
 * transacción propia: si Dosimetry la rechaza, el rechazo no arrastra la
 * ingesta, y la lectura cruda queda guardada con el motivo.
 */
@Component
@RequiredArgsConstructor
public class PersonalDosimetrySessionAdapter implements DosimetrySessionGateway {
    private static final String UNIT = "uT";

    private final PersonalDosimetryService dosimetry;
    private final PlatformTransactionManager transactions;

    @Override
    public Optional<String> sessionCovering(Long deviceId, Instant capturedAt) {
        return dosimetry.sessionCoveringDevice(deviceId, capturedAt);
    }

    @Override
    public DosimetryResult ingest(String sessionId, DosimetryReading r) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            DosimetryResources.Ingestion result = tx.execute(status -> dosimetry.ingest(DosimetryActor.system(),
                    sessionId, new IngestReading(r.deviceId(), r.bootId(), r.sequence(), r.fieldMicrotesla(), UNIT,
                            r.capturedFrom(), r.capturedTo(), r.saturated())));
            if (result == null) return new DosimetryResult(DosimetryResult.Status.REJECTED, null, "no result");
            return new DosimetryResult(result.duplicate() ? DosimetryResult.Status.DUPLICATE
                    : DosimetryResult.Status.ACCEPTED, result.reading().id(), null);
        } catch (RuntimeException e) {
            return new DosimetryResult(DosimetryResult.Status.REJECTED, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
