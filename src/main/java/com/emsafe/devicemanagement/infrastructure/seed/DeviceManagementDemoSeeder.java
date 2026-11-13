package com.emsafe.devicemanagement.infrastructure.seed;

import com.emsafe.devicemanagement.application.ports.AccountDirectory;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.DeviceSerial;
import com.emsafe.devicemanagement.domain.model.DeviceType;
import com.emsafe.devicemanagement.domain.model.EdgeNode;
import com.emsafe.devicemanagement.domain.model.EdgeSegment;
import com.emsafe.devicemanagement.domain.model.OrganizationCode;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import com.emsafe.devicemanagement.domain.repositories.EdgeNodeRepository;
import com.emsafe.devicemanagement.domain.repositories.OrganizationCodeRepository;
import com.emsafe.iam.application.UserService;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.interfaces.rest.dto.CreateUserRequest;
import com.emsafe.personaldosimetry.application.DosimetryActor;
import com.emsafe.personaldosimetry.application.PersonalDosimetryService;
import com.emsafe.personaldosimetry.domain.model.MembershipRole;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.ConfigureLimit;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.GrantMembership;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.OpenSession;
import com.emsafe.plantconfiguration.application.JobPositionService;
import com.emsafe.plantconfiguration.application.PlantEdgeService;
import com.emsafe.plantconfiguration.application.PlantZoneService;
import com.emsafe.plantconfiguration.interfaces.rest.dto.JobPositionRequest;
import com.emsafe.plantconfiguration.interfaces.rest.dto.PlantEdgeRequest;
import com.emsafe.plantconfiguration.interfaces.rest.dto.PlantZoneRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Datos de la demo de punta a punta con los dos Edges: los identificadores que
 * envían por defecto ya existen y no hay que registrar nada a mano.
 *
 * <p>Corre al terminar el arranque (después de las demás semillas), es
 * idempotente y usa los servicios de aplicación de cada contexto. Con
 * {@code seed-demo-session} además abre una jornada para el dosímetro, así las
 * lecturas industriales llegan asignadas.
 */
@Component
@ConditionalOnProperty(name = "emsafe.device-management.seed-demo-data", havingValue = "true")
public class DeviceManagementDemoSeeder {
    public static final String ORG_CODE = "org-demo";
    public static final String HOME_CODE = "home-demo";
    public static final String DOSIMETER = "EMSafe-DOS-01";
    public static final String PLUG = "EMSafe-PLUG-01";
    public static final String PLANT_EDGE = "EDGE-PLANTA-01";
    public static final String HOME_EDGE = "EDGE-HOGAR-01";
    public static final String COMPANY_EMAIL = "ops@quantumdyn.com";
    public static final String HOME_EMAIL = "hogar.demo@emsafe.com";
    public static final String OPERATOR_EMAIL = "operario.demo@emsafe.com";
    static final String ADMIN_EMAIL = "admin@emsafe.com";
    static final String ZONE = "Planta demo";
    static final String POSITION = "Operación planta demo";
    private static final String ACTOR = "demo-seed";
    private static final Logger log = LoggerFactory.getLogger(DeviceManagementDemoSeeder.class);

    private final AccountDirectory accounts;
    private final UserService users;
    private final OrganizationCodeRepository codes;
    private final EdgeNodeRepository edges;
    private final DeviceRepository devices;
    private final PlantZoneService zones;
    private final PlantEdgeService plantEdges;
    private final JobPositionService positions;
    private final PersonalDosimetryService dosimetry;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean seedSession;

    public DeviceManagementDemoSeeder(AccountDirectory accounts, UserService users, OrganizationCodeRepository codes,
                                      EdgeNodeRepository edges, DeviceRepository devices, PlantZoneService zones,
                                      PlantEdgeService plantEdges, JobPositionService positions,
                                      PersonalDosimetryService dosimetry, PlatformTransactionManager transactions,
                                      Clock clock,
                                      @Value("${emsafe.device-management.seed-demo-session:false}") boolean seedSession) {
        this.accounts = accounts;
        this.users = users;
        this.codes = codes;
        this.edges = edges;
        this.devices = devices;
        this.zones = zones;
        this.plantEdges = plantEdges;
        this.positions = positions;
        this.dosimetry = dosimetry;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
        this.seedSession = seedSession;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onReady() {
        seed();
    }

    /** Idempotente: se puede ejecutar en cada arranque. */
    public void seed() {
        try {
            Long zoneId = tx.execute(status -> seedDevices());
            if (zoneId != null && seedSession) tx.executeWithoutResult(status -> seedSession(zoneId));
        } catch (RuntimeException e) {
            log.warn("Device management demo data not seeded: {}", e.toString());
        }
    }

    private Long seedDevices() {
        AppUser company = accounts.findByEmail(COMPANY_EMAIL).orElse(null);
        if (company == null) {
            log.warn("Demo company {} not found; device management demo data skipped", COMPANY_EMAIL);
            return null;
        }
        AppUser home = accounts.findByEmail(HOME_EMAIL).orElseGet(() -> createClient(HOME_EMAIL, "Hogar demo"));
        assignCode(ORG_CODE, company);
        assignCode(HOME_CODE, home);

        Long zoneId = zones.listFor(company.getId()).stream().filter(z -> ZONE.equals(z.name())).findFirst()
                .map(z -> z.id())
                .orElseGet(() -> zones.create(company.getId(),
                        new PlantZoneRequest(ZONE, "Zona de la demo con el Edge industrial")).id());
        if (plantEdges.listFor(company.getId()).stream().noneMatch(e -> PLANT_EDGE.equals(e.serialNumber()))) {
            plantEdges.declare(company.getId(), new PlantEdgeRequest(zoneId, PLANT_EDGE));
        }
        ensureEdge(PLANT_EDGE, company, EdgeSegment.PLANT);
        ensureEdge(HOME_EDGE, home, EdgeSegment.HOME);

        Instant now = clock.instant();
        Device dosimeter = devices.findBySerial(DOSIMETER).orElseGet(() -> Device.register(DeviceSerial.of(DOSIMETER),
                DeviceType.PERSONAL_DOSIMETER, "Dosímetro demo", company, ACTOR, now));
        dosimeter.assignToZone(zoneId, ACTOR, now);
        devices.save(dosimeter);
        if (devices.findBySerial(PLUG).isEmpty()) {
            devices.save(Device.register(DeviceSerial.of(PLUG), DeviceType.SMART_PLUG, "Enchufe demo", home,
                    ACTOR, now));
        }
        log.info("Device management demo data ready: {} → {}, {} → {}, {}, {}, {}, {}", ORG_CODE, COMPANY_EMAIL,
                HOME_CODE, HOME_EMAIL, DOSIMETER, PLUG, PLANT_EDGE, HOME_EDGE);
        return zoneId;
    }

    /** Membresía, puesto, límite y jornada abierta para el dosímetro de la demo. */
    private void seedSession(Long zoneId) {
        Device dosimeter = devices.findBySerial(DOSIMETER).orElseThrow();
        Instant now = clock.instant();
        if (dosimetry.sessionCoveringDevice(dosimeter.getId(), now).isPresent()) return;
        AppUser admin = accounts.findByEmail(ADMIN_EMAIL).orElse(null);
        if (admin == null) return;
        Long organizationId = dosimeter.organizationId();
        DosimetryActor actor = new DosimetryActor(admin.getId(), true, null, null);
        AppUser operator = accounts.findByEmail(OPERATOR_EMAIL)
                .orElseGet(() -> createClient(OPERATOR_EMAIL, "Operario demo"));
        dosimetry.grantMembership(actor, new GrantMembership(operator.getId(), organizationId, MembershipRole.OPERATOR));
        Long positionId = positions.listFor(organizationId, zoneId).stream()
                .filter(p -> POSITION.equals(p.name())).findFirst().map(p -> p.id())
                .orElseGet(() -> positions.create(organizationId,
                        new JobPositionRequest(zoneId, POSITION, "Puesto de la demo del dosímetro")).id());
        boolean hasLimit = dosimetry.listLimits(actor, organizationId, 0, 200).content().stream()
                .anyMatch(l -> positionId.equals(l.jobPositionId()));
        if (!hasLimit) {
            dosimetry.configureLimit(actor, organizationId, new ConfigureLimit(positionId, new BigDecimal("800"),
                    new BigDecimal("0.8"), now.minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS),
                    "DEMO-INDEX"));
        }
        dosimetry.openSession(actor, new OpenSession(operator.getId(), dosimeter.getId(), positionId,
                now.truncatedTo(ChronoUnit.SECONDS).minusSeconds(60)));
        log.info("Demo exposure session opened for {} and {}", DOSIMETER, OPERATOR_EMAIL);
    }

    private void assignCode(String code, AppUser organization) {
        OrganizationCode value = new OrganizationCode(code);
        Long current = codes.resolve(value).orElse(null);
        if (current == null && codes.codeOf(organization.getId()).isEmpty()) {
            codes.assign(value, organization.getId());
        } else if (current != null && !current.equals(organization.getId())) {
            log.warn("Organization code {} already points to account {}", code, current);
        }
    }

    private void ensureEdge(String edgeNodeId, AppUser organization, EdgeSegment segment) {
        if (edges.findByEdgeNodeId(edgeNodeId).isEmpty()) {
            edges.save(EdgeNode.register(edgeNodeId, organization.getId(), segment, clock.instant()));
        }
    }

    private AppUser createClient(String email, String name) {
        users.create(new CreateUserRequest(name, null, email, "demo1234", Role.CLIENT, null, "Lima", null, null,
                null, "Cuenta de la demo de Device Management", null));
        return accounts.findByEmail(email).orElseThrow();
    }
}
