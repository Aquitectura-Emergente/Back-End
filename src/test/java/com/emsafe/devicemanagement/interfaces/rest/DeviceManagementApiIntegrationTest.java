package com.emsafe.devicemanagement.interfaces.rest;

import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.DeviceSerial;
import com.emsafe.devicemanagement.domain.model.DeviceType;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import com.emsafe.devicemanagement.infrastructure.mqtt.MqttGateway;
import com.emsafe.devicemanagement.infrastructure.mqtt.RecordingMqttGateway;
import com.emsafe.devicemanagement.infrastructure.persistence.DeviceManagementEventJpaRepository;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:devicemanagementapi;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class DeviceManagementApiIntegrationTest {
    static final String API = "/api/v1/device-management";

    @TestConfiguration
    static class Broker {
        @Bean
        @Primary
        RecordingMqttGateway recordingMqttGateway() {
            return new RecordingMqttGateway();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired DeviceRepository devices;
    @Autowired DeviceManagementEventJpaRepository events;
    @Autowired MqttGateway mqtt;

    String suffix;
    String dosimeter;
    String plug;
    String ssoEmail;
    String otherSsoEmail;
    String clientEmail;
    String otherClientEmail;
    String adminEmail;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        AppUser company = account("company", Role.CLIENT, null);
        AppUser otherCompany = account("other", Role.CLIENT, null);
        ssoEmail = account("sso", Role.SSO_RESPONSIBLE, company.getId()).getEmail();
        otherSsoEmail = account("sso2", Role.SSO_RESPONSIBLE, otherCompany.getId()).getEmail();
        AppUser home = account("home", Role.CLIENT, null);
        clientEmail = home.getEmail();
        otherClientEmail = account("home2", Role.CLIENT, null).getEmail();
        adminEmail = account("admin", Role.ADMIN, null).getEmail();
        Instant now = Instant.now();
        dosimeter = "DOS-" + suffix;
        plug = "PLUG-" + suffix;
        devices.save(Device.register(DeviceSerial.of(dosimeter), DeviceType.PERSONAL_DOSIMETER, null, company, "t", now));
        devices.save(Device.register(DeviceSerial.of(plug), DeviceType.SMART_PLUG, null, home, "t", now));
        ((RecordingMqttGateway) mqtt).accept = true;
        ((RecordingMqttGateway) mqtt).clear();
    }

    private AppUser account(String name, Role role, Long organizationId) {
        return users.save(AppUser.builder().email(name + "-" + suffix + "@test.local").passwordHash("x")
                .name(name).initials("XX").role(role).status("active").organizationId(organizationId)
                .clientType(role == Role.CLIENT ? "company" : null).build());
    }

    @Test
    void theSsoOfTheOwnerChangesTheThresholdWhichIsVersionedAuditedAndQueuedForTheEdge() throws Exception {
        long before = events.findByEventTypeOrderByOccurredAtAsc("DeviceConfigChanged").size();
        mvc.perform(put(API + "/devices/" + dosimeter + "/config/alert-threshold")
                        .with(user(ssoEmail).roles("SSO_RESPONSIBLE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alertThresholdUT\": 250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configVersion").value(2))
                .andExpect(jsonPath("$.data.definedBy").value("SSO_RESPONSIBLE"));
        assertThat(events.findByEventTypeOrderByOccurredAtAsc("DeviceConfigChanged")).hasSize((int) before + 1);

        mvc.perform(get(API + "/devices/" + dosimeter + "/audit").with(user(ssoEmail).roles("SSO_RESPONSIBLE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].entryType").value("CONFIG_CHANGED"))
                .andExpect(jsonPath("$.data.content[0].oldValue").value("300.0"))
                .andExpect(jsonPath("$.data.content[0].newValue").value("250.0"))
                .andExpect(jsonPath("$.data.content[0].actor").value(ssoEmail));
    }

    @Test
    void anotherOrganizationOrAnInvalidThresholdIsRefused() throws Exception {
        mvc.perform(put(API + "/devices/" + dosimeter + "/config/alert-threshold")
                        .with(user(otherSsoEmail).roles("SSO_RESPONSIBLE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alertThresholdUT\": 250}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(API + "/devices/" + dosimeter + "/config/alert-threshold")
                        .with(user(ssoEmail).roles("SSO_RESPONSIBLE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alertThresholdUT\": 790}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(API + "/devices/" + dosimeter + "/config")).andExpect(status().isForbidden());
    }

    @Test
    void onlyTheAdministratorChangesSamplingAndLifecycle() throws Exception {
        String body = "{\"samplingIntervalMs\": 2000, \"inactivityTimeoutS\": 120}";
        mvc.perform(put(API + "/devices/" + dosimeter + "/config/operational")
                        .with(user(ssoEmail).roles("SSO_RESPONSIBLE"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put(API + "/devices/" + dosimeter + "/config/operational")
                        .with(user(adminEmail).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.samplingIntervalMs").value(2000))
                .andExpect(jsonPath("$.data.inactivityTimeoutS").value(120));
        mvc.perform(put(API + "/devices/" + dosimeter + "/lifecycle").with(user(adminEmail).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"RETIRED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lifecycleStatus").value("RETIRED"));
    }

    @Test
    void theOwnerOrdersThePlugAndTheCommandIsPublishedAfterCommit() throws Exception {
        String response = mvc.perform(post(API + "/plugs/" + plug + "/commands")
                        .with(user(clientEmail).roles("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\": \"OFF\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String commandId = JsonPath.read(response, "$.data.commandId");

        RecordingMqttGateway.Published p = ((RecordingMqttGateway) mqtt).published.get(0);
        assertThat(p.topic()).isEqualTo("iot/" + plug + "/command");
        assertThat(p.qos()).isEqualTo(1);
        assertThat(p.retained()).isFalse();
        String payload = new String(p.payload());
        assertThat(payload).contains("\"commandId\":\"" + commandId + "\"", "\"requestedBy\":\"USER\"",
                "\"action\":\"OFF\"", "\"schemaVersion\":\"1.0\"");

        mvc.perform(get(API + "/plugs/" + plug + "/state").with(user(clientEmail).roles("CLIENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.lastCommand.status").value("PENDING"));
    }

    @Test
    void aCommandThatCannotBePublishedIsMarkedAndOtherClientsAreRefused() throws Exception {
        ((RecordingMqttGateway) mqtt).accept = false;
        String response = mvc.perform(post(API + "/plugs/" + plug + "/commands")
                        .with(user(clientEmail).roles("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\": \"ON\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String commandId = JsonPath.read(response, "$.data.commandId");
        mvc.perform(get(API + "/plugs/" + plug + "/commands/" + commandId).with(user(clientEmail).roles("CLIENT")))
                .andExpect(jsonPath("$.data.status").value("SEND_FAILED"));

        mvc.perform(post(API + "/plugs/" + plug + "/commands").with(user(otherClientEmail).roles("CLIENT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\": \"ON\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(API + "/plugs/" + dosimeter + "/commands").with(user(adminEmail).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\": \"ON\"}"))
                .andExpect(status().isBadRequest());
    }
}
