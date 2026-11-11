package com.emsafe.iam;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Casos clave de IAM para WORKER y SSO_RESPONSIBLE (usa los datos que siembra DataInitializer). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:iamtest;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbmVlZHMtMzItYnl0ZXM=",
        "jwt.expiration=3600000",
        "spring.flyway.enabled=false"
})
class IndustrialRolesIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;

    Long quantumId;
    Long harborId;
    String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        quantumId = users.findByEmail("ops@quantumdyn.com").orElseThrow().getId();
        harborId = users.findByEmail("facilities@harbormed.com").orElseThrow().getId();
        adminToken = login("admin@emsafe.com", "admin123").get("data").get("token").asText();
    }

    private JsonNode login(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private int create(String role, Long orgId) throws Exception {
        return create(role, orgId, UUID.randomUUID() + "@t.com");
    }

    private int create(String role, Long orgId, String email) throws Exception {
        String org = orgId == null ? "" : ",\"organizationId\":" + orgId;
        return mvc.perform(post("/api/users").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test " + role + "\",\"email\":\"" + email + "\","
                                + "\"password\":\"pass1234\",\"role\":\"" + role + "\"" + org + "}"))
                .andReturn().getResponse().getStatus();
    }

    private String token(String email, String pass) throws Exception {
        return login(email, pass).get("data").get("token").asText();
    }

    private int getStatus(String path, String token) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void adminCreatesWorkerAndSso_andBothLoginWithSameEndpoint() throws Exception {
        assertThat(create("WORKER", quantumId, "nuevo.worker@t.com")).isEqualTo(201);
        assertThat(create("SSO_RESPONSIBLE", quantumId, "nuevo.sso@t.com")).isEqualTo(201);

        JsonNode w = login("nuevo.worker@t.com", "pass1234").get("data");
        JsonNode s = login("nuevo.sso@t.com", "pass1234").get("data");
        assertThat(w.get("role").asText()).isEqualTo("worker");
        assertThat(s.get("role").asText()).isEqualTo("sso_responsible");
        assertThat(s.get("organizationId").asLong()).isEqualTo(quantumId);
    }

    @Test
    void industrialAccountNeedsAValidOrganization() throws Exception {
        assertThat(create("WORKER", null)).isEqualTo(400);
        assertThat(create("SSO_RESPONSIBLE", 999999L)).isEqualTo(400);
        Long adminId = users.findByEmail("admin@emsafe.com").map(AppUser::getId).orElseThrow();
        assertThat(create("WORKER", adminId)).isEqualTo(400);   // no es una cuenta CLIENT
    }

    @Test
    void workerSeesOnlyOwnData() throws Exception {
        String w = token("operario1@quantumdyn.com", "worker123");
        Long otherWorker = users.findByEmail("operario2@harbormed.com").orElseThrow().getId();

        assertThat(getStatus("/api/users/profile", w)).isEqualTo(200);
        assertThat(getStatus("/api/users/" + otherWorker, w)).isEqualTo(403);
        assertThat(getStatus("/api/users", w)).isEqualTo(403);
        assertThat(getStatus("/api/sso/workers", w)).isEqualTo(403);
    }

    @Test
    void ssoSeesOnlyWorkersOfItsOrganization() throws Exception {
        String s = token("sso@quantumdyn.com", "sso12345");
        Long own = users.findByEmail("operario1@quantumdyn.com").orElseThrow().getId();
        Long foreign = users.findByEmail("operario2@harbormed.com").orElseThrow().getId();

        MvcResult list = mvc.perform(get("/api/sso/workers").header("Authorization", "Bearer " + s))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = json.readTree(list.getResponse().getContentAsString()).get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("email").asText()).isEqualTo("operario1@quantumdyn.com");

        assertThat(getStatus("/api/sso/workers/" + own, s)).isEqualTo(200);
        assertThat(getStatus("/api/sso/workers/" + foreign, s)).isEqualTo(403);
        // No hereda permisos de admin
        assertThat(getStatus("/api/users", s)).isEqualTo(403);
        assertThat(getStatus("/api/users/" + own, s)).isEqualTo(403);
    }

    @Test
    void adminAndTechnicianStillWorkAsBefore() throws Exception {
        assertThat(login("marcus@emsafe.com", "tech123").get("data").get("role").asText())
                .isEqualTo("technician");
        assertThat(getStatus("/api/users", adminToken)).isEqualTo(200);
        assertThat(getStatus("/api/tech/work-orders", token("marcus@emsafe.com", "tech123")))
                .isNotEqualTo(403);
    }

    @Test
    void publicRegisterStillCreatesOnlyClients() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nuevo\",\"email\":\"nuevo@t.com\",\"password\":\"secret1\","
                                + "\"role\":\"SSO_RESPONSIBLE\",\"organizationId\":" + quantumId + "}"))
                .andExpect(status().isOk());
        AppUser u = users.findByEmail("nuevo@t.com").orElseThrow();
        assertThat(u.getRole().name()).isEqualTo("CLIENT");
        assertThat(u.getOrganizationId()).isNull();
    }
}
