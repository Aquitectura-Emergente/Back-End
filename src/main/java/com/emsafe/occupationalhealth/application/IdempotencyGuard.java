package com.emsafe.occupationalhealth.application;

import com.emsafe.occupationalhealth.domain.model.IdempotencyRecord;
import com.emsafe.occupationalhealth.infrastructure.persistence.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Hace idempotentes las mutaciones que llegan con Idempotency-Key
 * (informe §5.6.2).
 *
 * El caso que resuelve es concreto: un doble toque en la aplicación móvil del
 * SSO no debe aprobar dos veces la misma medida ni generar dos reportes. Sin
 * esto, la segunda llamada daría 409 —correcto pero confuso— o, en los
 * endpoints que crean, un recurso duplicado.
 *
 * Sin cabecera no hay garantía y la operación se ejecuta tal cual: la
 * idempotencia es opt-in, como en el informe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyGuard {

    private final IdempotencyRecordRepository records;

    /**
     * Ejecuta la operación una sola vez por clave.
     *
     * @param key      cabecera Idempotency-Key, o null si no vino
     * @param endpoint identifica la operación: la misma clave en dos endpoints
     *                 distintos son dos intenciones distintas
     * @param operation lo que hay que hacer, y que devuelve el id del recurso
     * @return el id del recurso, el de la primera llamada si se repitió
     */
    public Long runOnce(Long organizationId, String key, String endpoint,
                        Supplier<Long> operation) {
        if (key == null || key.isBlank()) {
            return operation.get();
        }
        Optional<IdempotencyRecord> seen = records
                .findByOrganizationIdAndIdempotencyKeyAndEndpoint(organizationId, key, endpoint);
        if (seen.isPresent()) {
            log.info("Idempotency-Key {} already handled on {} → resource {}",
                    key, endpoint, seen.get().getResourceId());
            return seen.get().getResourceId();
        }

        Long resourceId = operation.get();
        remember(organizationId, key, endpoint, resourceId);
        return resourceId;
    }

    /**
     * Apunta la clave una vez ejecutada la operación.
     *
     * Sin @Transactional a propósito: se llama desde runOnce() en la misma
     * clase, y una auto-invocación no pasa por el proxy de Spring, así que la
     * anotación sería decorativa y engañosa. Como quien invoca runOnce() es un
     * controller —que no es transaccional— el save() del repositorio abre su
     * propia transacción, que es justo lo que hace falta: el registro de la
     * clave no puede deshacer el trabajo ya confirmado.
     *
     * Si dos peticiones con la misma clave corren a la vez, la segunda choca
     * con el UNIQUE y se trata como repetida, que es lo que es.
     */
    private void remember(Long organizationId, String key, String endpoint, Long resourceId) {
        try {
            records.save(IdempotencyRecord.builder()
                    .organizationId(organizationId)
                    .idempotencyKey(key)
                    .endpoint(endpoint)
                    .resourceId(resourceId)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            log.debug("Idempotency-Key {} on {} was recorded concurrently", key, endpoint);
        }
    }
}
