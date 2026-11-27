package com.emsafe.iam;

import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.personaldosimetry.domain.model.MembershipRole;
import com.emsafe.personaldosimetry.infrastructure.persistence.DosimetryMembershipRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El responsable de SSO da de alta y gestiona a los operarios de su empresa, y
 * cada cuenta industrial recibe su membership de dosimetría sin pasos manuales.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ssoworkers;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "jwt.expiration=3600000",
        "spring.flyway.enabled=false",
        // las cuentas sembradas (sso@quantumdyn.com) reciben su membership al arrancar
        "emsafe.personal-dosimetry.membership-backfill-enabled=true"
})
class SsoWorkerManagementTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired DosimetryMembershipRepository memberships;

    Long quantumId;
    String ssoToken;

    @BeforeEach
    void setUp() throws Exception {
        quantumId = users.findByEmail("ops@quantumdyn.com").orElseThrow().getId();
        ssoToken = token("sso@quantumdyn.com", "sso12345");
    }

    @Test
    void ssoCreatesAWorkerOfItsOwnCompanyWithItsMembership() throws Exception {
        String email = UUID.randomUUID() + "@quantumdyn.com";
        JsonNode created = send(post("/api/sso/workers"), ssoToken,
                "{\"name\":\"Ana Operaria\",\"email\":\"" + email + "\",\"password\":\"segura123\","
                        + "\"department\":\"Mantenimiento\"}", 201);

        Long id = created.get("data").get("id").asLong();
        var worker = users.findById(id).orElseThrow();
        assertThat(worker.getRole()).isEqualTo(Role.WORKER);
        assertThat(worker.getOrganizationId()).isEqualTo(quantumId);
        var membership = memberships.findById(id).orElseThrow();
        assertThat(membership.getRole()).isEqualTo(MembershipRole.OPERATOR);
        assertThat(membership.getOrganizationId()).isEqualTo(quantumId);

        // la cuenta entra con su clave y su SSO ya puede consultar sus jornadas
        token(email, "segura123");
        assertThat(statusOf("/api/v1/personal-dosimetry/workers/" + id + "/sessions", ssoToken)).isEqualTo(200);
    }

    @Test
    void ssoEditsAndDeactivatesOnlyItsOwnWorkers() throws Exception {
        Long own = users.findByEmail("operario1@quantumdyn.com").orElseThrow().getId();
        Long foreign = users.findByEmail("operario2@harbormed.com").orElseThrow().getId();

        JsonNode updated = send(put("/api/sso/workers/" + own), ssoToken,
                "{\"phone\":\"+51 999 111 222\",\"status\":\"inactive\"}", 200);
        assertThat(updated.get("data").get("status").asText()).isEqualTo("inactive");
        send(put("/api/sso/workers/" + own), ssoToken, "{\"status\":\"active\"}", 200);

        send(put("/api/sso/workers/" + foreign), ssoToken, "{\"status\":\"inactive\"}", 403);
        send(put("/api/sso/workers/" + own), ssoToken, "{\"status\":\"pending\"}", 400);
    }

    @Test
    void workersCannotManageWorkers() throws Exception {
        String worker = token("operario1@quantumdyn.com", "worker123");
        send(post("/api/sso/workers"), worker,
                "{\"name\":\"X\",\"email\":\"x@t.com\",\"password\":\"segura123\"}", 403);
    }

    @Test
    void anSsoCreatedByTheAdminCanConfigureDosimetryRightAway() throws Exception {
        String admin = token("admin@emsafe.com", "admin123");
        String email = UUID.randomUUID() + "@quantumdyn.com";
        send(post("/api/users"), admin, "{\"name\":\"Nuevo SSO\",\"email\":\"" + email + "\","
                + "\"password\":\"segura123\",\"role\":\"SSO_RESPONSIBLE\",\"organizationId\":" + quantumId + "}", 201);

        String sso = token(email, "segura123");
        assertThat(statusOf("/api/v1/personal-dosimetry/organizations/" + quantumId + "/limits", sso)).isEqualTo(200);
    }

    @Test
    void ssoSetsTheUsualPositionOfItsOwnWorkersOnly() throws Exception {
        Long own = users.findByEmail("operario1@quantumdyn.com").orElseThrow().getId();
        Long foreign = users.findByEmail("operario2@harbormed.com").orElseThrow().getId();
        String path = "/api/v1/personal-dosimetry/workers/";

        JsonNode saved = send(put(path + own + "/defaults"), ssoToken, "{\"jobPositionId\":31}", 200);
        assertThat(saved.get("data").get("jobPositionId").asLong()).isEqualTo(31L);
        assertThat(saved.get("data").path("deviceId").isMissingNode() || saved.get("data").get("deviceId").isNull()).isTrue();
        assertThat(statusOf(path + own + "/defaults", ssoToken)).isEqualTo(200);

        send(put(path + foreign + "/defaults"), ssoToken, "{\"jobPositionId\":31}", 403);
    }

    @Test
    void ssoListsOnlyTheDosimetersOfItsCompany() throws Exception {
        Long harborId = users.findByEmail("facilities@harbormed.com").orElseThrow().getId();
        assertThat(statusOf("/api/v1/device-management/organizations/" + quantumId + "/dosimeters", ssoToken))
                .isEqualTo(200);
        assertThat(statusOf("/api/v1/device-management/organizations/" + harborId + "/dosimeters", ssoToken))
                .isEqualTo(403);
    }

    private JsonNode send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          String token, String body, int expected) throws Exception {
        MvcResult r = mvc.perform(request.header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
        String content = r.getResponse().getContentAsString();
        return content.isEmpty() ? null : json.readTree(content);
    }

    private int statusOf(String path, String token) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    private String token(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("data").get("token").asText();
    }
}
