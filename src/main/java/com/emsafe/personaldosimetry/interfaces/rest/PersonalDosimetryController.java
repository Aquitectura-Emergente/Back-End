package com.emsafe.personaldosimetry.interfaces.rest;

import com.emsafe.personaldosimetry.application.*;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.*;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryResources.*;
import com.emsafe.shared.dto.ApiResponse;
import com.emsafe.shared.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/personal-dosimetry")
@Tag(name = "Personal Dosimetry & Exposure",
        description = "Membresías, límites ocupacionales versionados, jornadas de exposición (uT*s), ingesta idempotente, reconciliación y auditoría de acceso.")
@RequiredArgsConstructor
public class PersonalDosimetryController {
    private final PersonalDosimetryService service;
    private final DosimetryAccessPolicy access;
    private final DosimetryAuditService audit;

    @PostMapping("/memberships")
    @Operation(summary = "Conceder membresía de dosimetría",
            description = "Solo ADMIN. Habilita una cuenta WORKER/CLIENT como OPERATOR o SSO_RESPONSIBLE/CLIENT como SSO en una empresa activa.")
    public ResponseEntity<ApiResponse<Membership>> grant(Authentication auth, @Valid @RequestBody GrantMembership body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.grantMembership(actor(auth), body)));
    }

    @PostMapping("/organizations/{organizationId}/limits")
    @Operation(summary = "Configurar versión de límite ocupacional por puesto",
            description = "SSO de la empresa o ADMIN. Crea una nueva versión en uT*s sin alterar jornadas cerradas.")
    public ResponseEntity<ApiResponse<Limit>> configureLimit(Authentication auth, @PathVariable Long organizationId,
                                                            @Valid @RequestBody ConfigureLimit body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.configureLimit(actor(auth), organizationId, body)));
    }

    @GetMapping("/organizations/{organizationId}/limits")
    @Operation(summary = "Listar versiones de límites ocupacionales de la organización",
            description = "SSO de la empresa o ADMIN. Ordenadas por puesto ascendente y versión descendente.")
    public ApiResponse<PageResponse<Limit>> limits(Authentication auth, @PathVariable Long organizationId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.listLimits(actor(auth), organizationId, page, size));
    }

    @PostMapping("/sessions")
    @Operation(summary = "Abrir jornada de exposición personal",
            description = "SSO de la empresa o ADMIN. Asigna operario, dosímetro y límite vigente del puesto.")
    public ResponseEntity<ApiResponse<Session>> open(Authentication auth, @Valid @RequestBody OpenSession body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.openSession(actor(auth), body)));
    }

    @GetMapping("/sessions/{id}")
    @Operation(summary = "Consultar detalle de una jornada de exposición",
            description = "Operario titular, SSO de la empresa o ADMIN.")
    public ApiResponse<Session> session(Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(service.getSession(actor(auth), id));
    }

    @PostMapping("/sessions/{id}/readings")
    @Operation(summary = "Registrar lectura de dosimetría en una jornada",
            description = "SSO de la empresa o ADMIN. Deduplica por identidad de captura y rechaza intervalos solapados.")
    public ApiResponse<Ingestion> ingest(Authentication auth, @PathVariable String id, @Valid @RequestBody IngestReading body) {
        return ApiResponse.ok(service.ingest(actor(auth), id, body));
    }

    @GetMapping("/sessions/{id}/readings")
    @Operation(summary = "Listar lecturas registradas en una jornada",
            description = "Operario titular, SSO de la empresa o ADMIN. Ordenadas por capturedFrom ascendente.")
    public ApiResponse<PageResponse<Reading>> readings(Authentication auth, @PathVariable String id,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.listReadings(actor(auth), id, page, size));
    }

    @PostMapping("/sessions/{id}/close")
    @Operation(summary = "Solicitar cierre de jornada (pasa a CLOSING)",
            description = "SSO de la empresa o ADMIN. Libera el dosímetro y abre ventana de reconciliación de 30 minutos.")
    public ApiResponse<Session> close(Authentication auth, @PathVariable String id,
                                       @RequestBody(required = false) CloseSession body) {
        return ApiResponse.ok(service.requestClose(actor(auth), id, body));
    }

    @PostMapping("/sessions/{id}/finalize")
    @Operation(summary = "Finalizar y consolidar jornada (pasa a CLOSED)",
            description = "SSO de la empresa o ADMIN. Congela acumulado y cobertura y emite ExposureSessionClosed.")
    public ApiResponse<Session> finalizeSession(Authentication auth, @PathVariable String id,
                                               @RequestBody(required = false) FinalizeSession body) {
        return ApiResponse.ok(service.finalizeSession(actor(auth), id, body));
    }

    @GetMapping("/workers/me/exposure")
    @Operation(summary = "Exposición de la última jornada del operario autenticado",
            description = "Cuenta con membresía activa en dosimetría.")
    public ApiResponse<Session> current(Authentication auth) {
        return ApiResponse.ok(service.currentExposure(actor(auth)));
    }

    @GetMapping("/workers/{workerId}/defaults")
    @Operation(summary = "Puesto y dosímetro habituales de un operario",
            description = "Operario titular, SSO de la empresa o ADMIN. Solo sugieren valores al iniciar la jornada.")
    public ApiResponse<WorkerDefaults> defaults(Authentication auth, @PathVariable Long workerId) {
        return ApiResponse.ok(service.workerDefaults(actor(auth), workerId));
    }

    @PutMapping("/workers/{workerId}/defaults")
    @Operation(summary = "Fijar el puesto y el dosímetro habituales de un operario",
            description = "SSO de la empresa o ADMIN. El dosímetro tiene que ser de la misma empresa; null los quita.")
    public ApiResponse<WorkerDefaults> changeDefaults(Authentication auth, @PathVariable Long workerId,
                                                      @Valid @RequestBody WorkerDefaults body) {
        return ApiResponse.ok(service.changeWorkerDefaults(actor(auth), workerId, body));
    }

    @GetMapping("/workers/{workerId}/sessions")
    @Operation(summary = "Historial paginado de jornadas de un operario",
            description = "Operario titular, SSO de la empresa o ADMIN.")
    public ApiResponse<PageResponse<Session>> history(Authentication auth, @PathVariable Long workerId,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.history(actor(auth), workerId, page, size));
    }

    @GetMapping("/outbox")
    @Operation(summary = "Listar eventos pendientes en el outbox de dosimetría",
            description = "Solo ADMIN.")
    public ApiResponse<PageResponse<Event>> events(Authentication auth,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(service.pendingEvents(actor(auth), page, size));
    }

    @PostMapping("/outbox/{id}/ack")
    @Operation(summary = "Confirmar entrega de un evento del outbox",
            description = "Solo ADMIN. Idempotente.")
    public ResponseEntity<Void> acknowledge(Authentication auth, @PathVariable String id) {
        service.acknowledgeEvent(actor(auth), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    @Operation(summary = "Consultar bitácora de accesos denegados (401/403)",
            description = "Solo ADMIN.")
    public ApiResponse<PageResponse<AccessAudit>> audit(Authentication auth,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(audit.list(actor(auth), page, size));
    }

    private DosimetryActor actor(Authentication auth) {
        return access.actor(auth.getName());
    }
}
