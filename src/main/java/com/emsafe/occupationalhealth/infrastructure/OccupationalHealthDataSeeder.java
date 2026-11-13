package com.emsafe.occupationalhealth.infrastructure;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.domain.model.UserStatus;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.infrastructure.persistence.DeviceRepository;
import com.emsafe.occupationalhealth.domain.model.*;
import com.emsafe.occupationalhealth.infrastructure.persistence.AlertEvidenceRecordRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.ClosedSessionRecordRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.WorkerShiftRepository;
import com.emsafe.plantconfiguration.domain.model.JobPosition;
import com.emsafe.plantconfiguration.domain.model.PlantEdge;
import com.emsafe.plantconfiguration.domain.model.PlantZone;
import com.emsafe.plantconfiguration.infrastructure.persistence.JobPositionRepository;
import com.emsafe.plantconfiguration.infrastructure.persistence.PlantEdgeRepository;
import com.emsafe.plantconfiguration.infrastructure.persistence.PlantZoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Datos de demostración del segmento industrial.
 *
 * ── Por qué está apagado por defecto ──────────────────────────────────────────
 * DataInitializer siembra siempre que la tabla `users` esté vacía, lo que basta
 * para una base nueva pero no sirve aquí: las cuentas industriales se añaden a
 * una base que ya tiene datos del segmento hogar. Un seeder que corriera solo
 * por estar la base poblada inyectaría datos de prueba en el montaje demo, que
 * apunta a TiDB en la nube.
 *
 * Por eso es explícito:
 *
 *   ./mvnw spring-boot:run --% -Dspring-boot.run.arguments=--emsafe.occupational-health.seed-demo-data=true
 *
 * ── Qué siembra, y por qué eso ────────────────────────────────────────────────
 * Lo que hacía falta para poder ejercitar el contexto completo:
 *
 *  - Un Responsable de SSO y TRES operarios con organización. Tres y no uno
 *    porque **una rotación necesita dos operarios con turno abierto en zonas
 *    distintas**, y sin eso el camino de RotationSuggested no se puede demostrar.
 *  - Turnos abiertos para dos de ellos, en zonas diferentes.
 *  - Jornadas cerradas de los últimos días, una con cobertura incompleta, para
 *    que un reporte de cumplimiento tenga contenido y alguna nota de
 *    trazabilidad.
 *  - Evidencia de una alerta acusada y otra escalada sin acuse, que es la
 *    condición de la regla 10.
 *
 * Es idempotente: cada pieza se crea solo si falta, así que correrlo dos veces
 * no duplica nada ni pisa los datos que ya existan.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "emsafe.occupational-health.seed-demo-data", havingValue = "true")
public class OccupationalHealthDataSeeder implements CommandLineRunner {

    /** Organización única de la demo. */
    private static final Long ORG = 1L;
    private static final String UNIT = "uT.h";
    private static final String METHOD = DosimetryExposureAdapter.PROVISIONAL_METHOD_ID;
    private static final BigDecimal LIMIT = new BigDecimal("800");

    private final UserRepository users;
    private final DeviceRepository devices;
    private final WorkerShiftRepository shifts;
    private final ClosedSessionRecordRepository closedSessions;
    private final AlertEvidenceRecordRepository alertEvidence;
    private final PlantZoneRepository plantZones;
    private final PlantEdgeRepository plantEdges;
    private final JobPositionRepository jobPositions;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Seeding Occupational Health demo data...");

        AppUser sso = ensureUser("sso@emsafe.com", "Lucia Ramos", "LR", Role.SSO_RESPONSIBLE, "sso123");
        AppUser w1 = ensureUser("operario1@emsafe.com", "Pedro Quispe", "PQ", Role.WORKER, "worker123");
        AppUser w2 = ensureUser("operario2@emsafe.com", "Rosa Mamani", "RM", Role.WORKER, "worker123");
        AppUser w3 = ensureUser("operario3@emsafe.com", "Julio Ccahua", "JC", Role.WORKER, "worker123");

        List<PlantZone> zones = ensurePlantTopology();
        if (zones.size() < 2) {
            log.warn("Could not create at least two plant zones; skipping shifts");
        } else {
            ensureOpenShift(w2, firstPositionOf(zones.get(0)), zones.get(0).getId());
            ensureOpenShift(w3, firstPositionOf(zones.get(1)), zones.get(1).getId());
        }

        ensureClosedSession("seed-sess-1", w1, "480", "COMPLETE", 4);
        ensureClosedSession("seed-sess-2", w1, "760", "COMPLETE", 3);
        ensureClosedSession("seed-sess-3", w1, "520", "PARTIAL", 2);
        ensureClosedSession("seed-sess-4", w2, "610", "COMPLETE", 2);

        ensureAlertEvidence("seed-alert-ack", w1, "seed-sess-2", "CAUTION", true, false);
        ensureAlertEvidence("seed-alert-esc", w2, "seed-sess-4", "DANGER", false, true);

        log.info("Occupational Health demo data ready. SSO={} workers={},{},{} zones={}",
                sso.getId(), w1.getId(), w2.getId(), w3.getId(),
                zones.stream().map(PlantZone::getName).toList());
    }

    // ─── Piezas ───────────────────────────────────────────────────────────────

    private AppUser ensureUser(String email, String name, String initials, Role role,
                               String password) {
        return users.findByEmail(email)
                .map(existing -> {
                    // Una cuenta sin organización no ve nada industrial: si ya
                    // existía sin ella, se le asigna en vez de crear un duplicado.
                    if (existing.getOrganizationId() == null) {
                        existing.setOrganizationId(ORG);
                        users.save(existing);
                        log.info("Assigned organization {} to existing user {}", ORG, email);
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    AppUser created = users.save(AppUser.builder()
                            .email(email)
                            .name(name)
                            .initials(initials)
                            .passwordHash(passwordEncoder.encode(password))
                            .role(role)
                            .organizationId(ORG)
                            .status(UserStatus.ACTIVE)
                            .joinDate(LocalDate.now())
                            .build());
                    log.info("Created {} {}", role, email);
                    return created;
                });
    }

    /**
     * Topología de la planta de demostración (V22 en adelante).
     *
     * <p>Antes este método devolvía ids de dispositivo, porque una zona se
     * identificaba con el sensor que la cubría. Ahora las zonas existen de
     * verdad, así que siembra la topología completa del segmento industrial:
     *
     * <pre>
     *   Zona ── 1 edge
     *         ├── 2 sensores
     *         └── 2 puestos de trabajo
     * </pre>
     *
     * <p>Reasigna los primeros dispositivos a estas zonas <b>a propósito</b>: la
     * V22 hizo backfill de una zona por cada {@code location} distinta, y como
     * ningún par de sensores compartía texto salió una zona por sensor. Eso no
     * ejercita la topología real, así que la demo agrupa dos sensores por zona
     * para que se pueda ver funcionando. Las zonas del backfill siguen ahí para
     * el resto de los dispositivos.
     *
     * <p>Idempotente: si la zona ya existe por nombre, se reutiliza.
     */
    private List<PlantZone> ensurePlantTopology() {
        List<String> zoneNames = List.of(
                "Nave de Hornos", "Linea de Soldadura", "Sala de Control");
        List<Device> available = devices.findAll().stream()
                .sorted(java.util.Comparator.comparing(Device::getId))
                .toList();

        List<PlantZone> result = new java.util.ArrayList<>();
        int deviceCursor = 0;

        for (int i = 0; i < zoneNames.size(); i++) {
            String zoneName = zoneNames.get(i);
            PlantZone zone = plantZones
                    .findByOrganizationIdOrderByNameAsc(ORG).stream()
                    .filter(z -> zoneName.equals(z.getName()))
                    .findFirst()
                    .orElseGet(() -> plantZones.save(PlantZone.create(
                            ORG, zoneName, "Zona de demostracion del segmento industrial")));
            result.add(zone);

            if (!plantEdges.existsByPlantZoneId(zone.getId())) {
                plantEdges.save(PlantEdge.declare(
                        zone.getId(), "EMSAFE-EDGE-Z" + (i + 1)));
                log.info("Declared edge for zone {}", zone.getName());
            }

            ensureJobPosition(zone, "Operacion " + zoneName);
            ensureJobPosition(zone, "Supervision " + zoneName);

            // Dos sensores por zona, para que la zona agrupe de verdad.
            for (int d = 0; d < 2 && deviceCursor < available.size(); d++, deviceCursor++) {
                Device device = available.get(deviceCursor);
                if (!zone.getId().equals(device.getPlantZoneId())) {
                    device.setPlantZoneId(zone.getId());
                    devices.save(device);
                }
            }
        }
        return result;
    }

    private void ensureJobPosition(PlantZone zone, String name) {
        if (!jobPositions.existsByPlantZoneIdAndName(zone.getId(), name)) {
            jobPositions.save(JobPosition.create(zone.getId(), name, null));
        }
    }

    /** Primer puesto de la zona, que es el que ocupa el operario de la demo. */
    private Long firstPositionOf(PlantZone zone) {
        return jobPositions.findByPlantZoneIdOrderByNameAsc(zone.getId()).stream()
                .map(JobPosition::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Zone " + zone.getName() + " has no job position"));
    }

    private void ensureOpenShift(AppUser worker, Long jobPositionId, Long zoneId) {
        if (shifts.findActiveByWorker(ORG, worker.getId()).isPresent()) {
            log.debug("Worker {} already has an active shift", worker.getId());
            return;
        }
        WorkerShift shift = WorkerShift.start(ORG, worker.getId(), jobPositionId, zoneId,
                LocalDateTime.now().minusHours(2));
        shift.linkExposureSession("seed-live-" + worker.getId());
        shifts.save(shift);
        log.info("Opened shift for worker {} in zone {}", worker.getId(), zoneId);
    }

    private void ensureClosedSession(String sessionId, AppUser worker, String dose,
                                     String coverage, int daysAgo) {
        if (closedSessions.findByOrganizationIdAndSessionId(ORG, sessionId).isPresent()) {
            return;
        }
        closedSessions.save(ClosedSessionRecord.builder()
                .organizationId(ORG)
                .sessionId(sessionId)
                .workerId(worker.getId())
                .finalDose(DoseSnapshot.of(new BigDecimal(dose), UNIT, METHOD, LIMIT))
                .coverage(coverage)
                .finalizedAt(LocalDateTime.now().minusDays(daysAgo).withHour(18).withMinute(0)
                        .withSecond(0).withNano(0))
                .build());
        log.info("Seeded closed session {} for worker {}", sessionId, worker.getId());
    }

    /**
     * Evidencia de alerta. Una escalada sin acuse es la que dispara la regla 10,
     * así que se siembra justamente ese par: una acusada y una escalada a secas.
     */
    private void ensureAlertEvidence(String alertId, AppUser worker, String sessionId,
                                     String alertType, boolean acknowledged, boolean escalated) {
        if (alertEvidence.findByOrganizationIdAndAlertId(ORG, alertId).isPresent()) {
            return;
        }
        AlertEvidenceRecord record = AlertEvidenceRecord.builder()
                .organizationId(ORG)
                .alertId(alertId)
                .workerId(worker.getId())
                .sessionId(sessionId)
                .alertType(alertType)
                .build();
        if (acknowledged) record.acknowledgeAt(LocalDateTime.now().minusDays(2));
        if (escalated) record.escalateAt(LocalDateTime.now().minusDays(1));
        alertEvidence.save(record);
        log.info("Seeded alert evidence {} (ack={}, esc={})", alertId, acknowledged, escalated);
    }
}
