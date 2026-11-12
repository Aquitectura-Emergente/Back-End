package com.emsafe.plantconfiguration;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.domain.model.UserStatus;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.plantconfiguration.infrastructure.persistence.JobPositionRepository;
import com.emsafe.plantconfiguration.infrastructure.persistence.PlantEdgeRepository;
import com.emsafe.plantconfiguration.infrastructure.persistence.PlantZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de la configuración de planta.
 *
 * <p>Se autentica con el post-procesador {@code user(...)} en vez de pedir un
 * JWT: lo que se verifica aquí es la autorización y el aislamiento por
 * organización, no la emisión de tokens.
 *
 * <p>Lo que de verdad importa en este test son los dos casos de aislamiento: un
 * SSO de otra planta debe recibir 404 (no 403), porque un 403 ya confirmaría que
 * el recurso existe.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:plantconfig;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class PlantConfigurationApiIntegrationTest {

    private static final String API = "/api/v1/plant-config";
    private static final String SSO_EMAIL = "sso-pc@emsafe.test";
    private static final String OTHER_SSO_EMAIL = "sso-other@emsafe.test";
    private static final String ORPHAN_EMAIL = "orphan-pc@emsafe.test";

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private PlantZoneRepository zones;
    @Autowired private PlantEdgeRepository edges;
    @Autowired private JobPositionRepository positions;

    @BeforeEach
    void setUp() {
        edges.deleteAll();
        positions.deleteAll();
        zones.deleteAll();

        ensureUser(SSO_EMAIL, Role.SSO_RESPONSIBLE, 100L);
        ensureUser(OTHER_SSO_EMAIL, Role.SSO_RESPONSIBLE, 200L);
        ensureUser(ORPHAN_EMAIL, Role.SSO_RESPONSIBLE, null);
    }

    // ─── Zonas ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("el SSO crea una zona y la recupera")
    void createAndReadZone() throws Exception {
        String id = createZone("Nave de Hornos");

        mvc.perform(get(API + "/zones/" + id).with(asSso()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Nave de Hornos"))
                .andExpect(jsonPath("$.data.organizationId").value(100));
    }

    @Test
    @DisplayName("dos zonas con el mismo nombre en la misma planta: 400")
    void duplicateZoneNameIsRejected() throws Exception {
        createZone("Nave de Hornos");

        mvc.perform(post(API + "/zones").with(asSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nave de Hornos\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("el mismo nombre SI vale en otra planta: el unique es por organizacion")
    void sameNameIsFineInAnotherOrganization() throws Exception {
        createZone("Nave de Hornos");

        mvc.perform(post(API + "/zones").with(asOtherSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nave de Hornos\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("un nombre vacio lo rechaza la validacion: 400")
    void blankNameIsRejected() throws Exception {
        mvc.perform(post(API + "/zones").with(asSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AISLAMIENTO: un SSO de otra planta no ve la zona, y recibe 404 no 403")
    void zoneOfAnotherOrganizationIsNotVisible() throws Exception {
        String id = createZone("Nave de Hornos");

        mvc.perform(get(API + "/zones/" + id).with(asOtherSso()))
                .andExpect(status().isNotFound());

        mvc.perform(put(API + "/zones/" + id).with(asOtherSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Secuestrada\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("la lista solo trae las zonas de la propia planta")
    void listIsScopedToOrganization() throws Exception {
        createZone("Nave de Hornos");
        createZone("Sala de Control");
        mvc.perform(post(API + "/zones").with(asOtherSso())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ajena\"}"));

        mvc.perform(get(API + "/zones").with(asSso()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mvc.perform(get(API + "/zones").with(asOtherSso()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("una cuenta sin organizacion no ve nada industrial")
    void accountWithoutOrganizationIsDenied() throws Exception {
        mvc.perform(get(API + "/zones").with(user(ORPHAN_EMAIL).roles("SSO_RESPONSIBLE")))
                .andExpect(status().isForbidden());
    }

    // ─── Edges ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("una zona tiene exactamente un edge: el segundo da 400")
    void oneEdgePerZone() throws Exception {
        String zoneId = createZone("Nave de Hornos");

        mvc.perform(post(API + "/edges").with(asSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plantZoneId\":" + zoneId + ",\"serialNumber\":\"E-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mvc.perform(post(API + "/edges").with(asSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plantZoneId\":" + zoneId + ",\"serialNumber\":\"E-2\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("el serial del edge es unico en todo el sistema")
    void edgeSerialIsGloballyUnique() throws Exception {
        String a = createZone("Zona A");
        String b = createZone("Zona B");

        mvc.perform(post(API + "/edges").with(asSso())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantZoneId\":" + a + ",\"serialNumber\":\"E-DUP\"}"));

        mvc.perform(post(API + "/edges").with(asSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plantZoneId\":" + b + ",\"serialNumber\":\"E-DUP\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("no se puede declarar un edge en la zona de otra planta")
    void cannotDeclareEdgeInAnotherOrganizationZone() throws Exception {
        String zoneId = createZone("Nave de Hornos");

        mvc.perform(post(API + "/edges").with(asOtherSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plantZoneId\":" + zoneId + ",\"serialNumber\":\"E-X\"}"))
                .andExpect(status().isNotFound());
    }

    // ─── Puestos ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("un puesto se crea dentro de una zona y arrastra su nombre")
    void createJobPosition() throws Exception {
        String zoneId = createZone("Nave de Hornos");

        mvc.perform(post(API + "/job-positions").with(asSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plantZoneId\":" + zoneId + ",\"name\":\"Soldadura\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.plantZoneName").value("Nave de Hornos"));
    }

    @Test
    @DisplayName("dos puestos con el mismo nombre en la misma zona: 400")
    void duplicatePositionNameInZoneIsRejected() throws Exception {
        String zoneId = createZone("Nave de Hornos");
        createPosition(zoneId, "Soldadura");

        mvc.perform(post(API + "/job-positions").with(asSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plantZoneId\":" + zoneId + ",\"name\":\"Soldadura\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("el mismo puesto SI vale en otra zona de la misma planta")
    void samePositionNameIsFineInAnotherZone() throws Exception {
        String a = createZone("Zona A");
        String b = createZone("Zona B");
        createPosition(a, "Soldadura");

        mvc.perform(post(API + "/job-positions").with(asSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plantZoneId\":" + b + ",\"name\":\"Soldadura\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("filtrar puestos por zona")
    void listPositionsByZone() throws Exception {
        String a = createZone("Zona A");
        String b = createZone("Zona B");
        createPosition(a, "Soldadura");
        createPosition(a, "Supervision");
        createPosition(b, "Control");

        mvc.perform(get(API + "/job-positions").param("plantZoneId", a).with(asSso()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mvc.perform(get(API + "/job-positions").with(asSso()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    // ─── Baja de zona ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("una zona con edge no se borra: 400 con el motivo, no un 500 de MySQL")
    void cannotDeleteZoneWithEdge() throws Exception {
        String zoneId = createZone("Nave de Hornos");
        mvc.perform(post(API + "/edges").with(asSso())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantZoneId\":" + zoneId + ",\"serialNumber\":\"E-1\"}"));

        mvc.perform(delete(API + "/zones/" + zoneId).with(asSso()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("edge")));
    }

    @Test
    @DisplayName("una zona con puestos no se borra")
    void cannotDeleteZoneWithPositions() throws Exception {
        String zoneId = createZone("Nave de Hornos");
        createPosition(zoneId, "Soldadura");

        mvc.perform(delete(API + "/zones/" + zoneId).with(asSso()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("job position")));
    }

    @Test
    @DisplayName("una zona vacia si se borra")
    void deletesEmptyZone() throws Exception {
        String zoneId = createZone("Zona Vacia");

        mvc.perform(delete(API + "/zones/" + zoneId).with(asSso()))
                .andExpect(status().isOk());

        assertThat(zones.findById(Long.valueOf(zoneId))).isEmpty();
    }

    // ─── Ayudantes ────────────────────────────────────────────────────────────

    private String createZone(String name) throws Exception {
        String body = mvc.perform(post(API + "/zones").with(asSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idFrom(body);
    }

    private void createPosition(String zoneId, String name) throws Exception {
        mvc.perform(post(API + "/job-positions").with(asSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plantZoneId\":" + zoneId + ",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated());
    }

    private String idFrom(String json) {
        int at = json.indexOf("\"id\":");
        int end = json.indexOf(',', at);
        return json.substring(at + 5, end).trim();
    }

    private Long ensureUser(String email, Role role, Long organizationId) {
        AppUser user = users.findByEmail(email).orElseGet(() -> users.save(AppUser.builder()
                .email(email)
                .name(email)
                .initials("XX")
                .passwordHash("{noop}x")
                .role(role)
                .organizationId(organizationId)
                .status(UserStatus.ACTIVE)
                .joinDate(LocalDate.now())
                .build()));
        if (organizationId != null && user.getOrganizationId() == null) {
            user.setOrganizationId(organizationId);
            users.save(user);
        }
        return organizationId;
    }

    private static RequestPostProcessor asSso() {
        return user(SSO_EMAIL).roles("SSO_RESPONSIBLE");
    }

    private static RequestPostProcessor asOtherSso() {
        return user(OTHER_SSO_EMAIL).roles("SSO_RESPONSIBLE");
    }
}
