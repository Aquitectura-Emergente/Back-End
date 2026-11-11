package com.emsafe.personaldosimetry;

import com.emsafe.personaldosimetry.application.*;
import com.emsafe.personaldosimetry.domain.model.*;
import com.emsafe.personaldosimetry.infrastructure.persistence.*;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import java.math.BigDecimal;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;

abstract class DosimetryRestartRecoveryContract {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final Instant START = NOW.minusSeconds(600);
    private static final DosimetryActor ADMIN = new DosimetryActor(900001L, true, null, null);

    protected abstract String url();
    protected abstract String driver();
    protected String username() { return "sa"; }
    protected String password() { return ""; }
    protected boolean useFlyway() { return false; }

    @Test
    void newApplicationContextRecoversSessionReadingAuditAndPendingEventsWithoutDoubleCounting() {
        String sessionId, pendingId;
        var request = new IngestReading(900003L, "recovery-boot", 1L, new BigDecimal("101"), "uT", START, START.plusSeconds(1));
        try (var first = start(true)) {
            var service = first.getBean(PersonalDosimetryService.class);
            var limits = first.getBean(OccupationalLimitRepository.class);
            limits.saveAndFlush(new OccupationalLimit(900001L, 1L, 1, new BigDecimal("100"), new BigDecimal("0.8"),
                    START.minusSeconds(1), "RESTART-TEST", ADMIN.userId(), START));
            first.getBean(DosimetryMembershipRepository.class).saveAndFlush(
                    new DosimetryMembership(900002L, 900001L, MembershipRole.OPERATOR, ADMIN.userId(), START));
            sessionId = service.openSession(ADMIN, new OpenSession(900002L, 900003L, 1L, START)).id();
            service.ingest(ADMIN, sessionId, request);
            var events = service.pendingEvents(ADMIN, 0, 20).content();
            pendingId = events.stream().filter(e -> e.eventType().equals("OccupationalLimitExceeded")).findFirst().orElseThrow().eventId();
            for (var event : events) {
                if (!event.eventId().equals(pendingId)) service.acknowledgeEvent(ADMIN, event.eventId());
            }
            first.getBean(DosimetryAccessAuditRepository.class).saveAndFlush(
                    new DosimetryAccessAudit("recovery@pd.test", "GET", "/api/v1/personal-dosimetry/sessions/" + sessionId, 403, NOW));
        }

        // The first connection pool, EntityManagerFactory and application beans are all closed.
        try (var second = start(false)) {
            var service = second.getBean(PersonalDosimetryService.class);
            assertThat(service.getSession(ADMIN, sessionId).accumulatedIndex()).isEqualByComparingTo("101");
            assertThat(service.pendingEvents(ADMIN, 0, 20).content()).extracting(e -> e.eventId()).contains(pendingId);
            var result = service.ingest(ADMIN, sessionId, request);
            assertThat(result.duplicate()).isTrue();
            assertThat(result.session().accumulatedIndex()).isEqualByComparingTo("101");
            assertThat(second.getBean(PersonalReadingRepository.class).findBySessionIdOrderByCapturedFromAsc(
                    sessionId, org.springframework.data.domain.PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
            assertThat(second.getBean(DosimetryAccessAuditRepository.class).findAll().stream()
                    .anyMatch(a -> a.getResourcePath().endsWith(sessionId))).isTrue();
            service.acknowledgeEvent(ADMIN, pendingId);
            assertThat(service.pendingEvents(ADMIN, 0, 20).content()).extracting(e -> e.eventId()).doesNotContain(pendingId);
        }
    }

    private ConfigurableApplicationContext start(boolean first) {
        return new SpringApplicationBuilder(RecoveryApplication.class).web(WebApplicationType.NONE).run(
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + url(),
                "--spring.datasource.driver-class-name=" + driver(),
                "--spring.datasource.username=" + username(),
                "--spring.datasource.password=" + password(),
                "--spring.jpa.hibernate.ddl-auto=" + (useFlyway() || !first ? "validate" : "create"),
                "--spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
                "--spring.flyway.enabled=" + useFlyway(),
                "--spring.flyway.baseline-on-migrate=false",
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN");
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ExposureSession.class)
    @EnableJpaRepositories(basePackageClasses = ExposureSessionRepository.class)
    @Import(PersonalDosimetryService.class)
    static class RecoveryApplication {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean DosimetryDirectory directory() {
            return new DosimetryDirectory() {
                public User userByEmail(String email) { return userById(900001L); }
                public User userById(Long id) { return new User(id, true, id.equals(900001L), true, id.equals(900001L)); }
                public Device deviceById(Long id) { return new Device(id, 900001L, true); }
            };
        }
    }
}
