package com.emsafe.personaldosimetry.infrastructure.persistence;

import com.emsafe.personaldosimetry.domain.model.ExposureSession;
import com.emsafe.personaldosimetry.domain.model.OccupationalLimit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bordes de {@link ExposureSessionRepository#findEndedBetween}, la consulta de
 * ExposureQueryFacade.closedSessions: solo CLOSED, de la organización pedida, con
 * endedAt en [from, to) y en orden de cierre.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:pd_ended_between;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
class ExposureSessionRepositoryEndedBetweenTest {

    private static final long ORG = 1L;
    private static final long OTHER_ORG = 2L;
    private static final Instant START = Instant.parse("2026-11-10T08:00:00Z");
    private static final Instant FROM = Instant.parse("2026-11-10T12:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-11T00:00:00Z");

    @Autowired private ExposureSessionRepository sessions;

    private long nextWorker = 100;

    private static OccupationalLimit limit(long organizationId) {
        var limit = new OccupationalLimit(organizationId, 4L, 1, new BigDecimal("1000"), new BigDecimal("0.8"),
                START, "DEMO", 1L, START);
        ReflectionTestUtils.setField(limit, "id", 10L);
        return limit;
    }

    private ExposureSession open(long organizationId) {
        long worker = nextWorker++;
        return ExposureSession.open(organizationId, worker, worker + 1_000, limit(organizationId), START);
    }

    private ExposureSession closing(long organizationId, Instant end) {
        ExposureSession s = open(organizationId);
        s.requestClose(end, end);
        return s;
    }

    private ExposureSession closed(long organizationId, Instant end) {
        ExposureSession s = closing(organizationId, end);
        s.finalizeSession(end.plusSeconds(60), true);
        return s;
    }

    private String idOf(ExposureSession s) {
        return sessions.saveAndFlush(s).getId();
    }

    private String atFrom;
    private String inside;
    private String insideLater;

    @BeforeEach
    void seed() {
        sessions.deleteAll();
        insideLater = idOf(closed(ORG, Instant.parse("2026-11-10T20:00:00Z")));
        atFrom = idOf(closed(ORG, FROM));
        inside = idOf(closed(ORG, Instant.parse("2026-11-10T16:00:00Z")));
        idOf(closed(ORG, TO));                                          // límite superior: excluido
        idOf(closed(ORG, FROM.minusSeconds(1)));                        // antes del rango
        idOf(closing(ORG, Instant.parse("2026-11-10T15:00:00Z")));      // CLOSING: aún puede cambiar
        idOf(open(ORG));                                                // OPEN: sin endedAt
        idOf(closed(OTHER_ORG, Instant.parse("2026-11-10T16:00:00Z"))); // otra organización
    }

    @Test
    void returnsOnlyClosedSessionsOfTheOrganizationEndedInTheHalfOpenRangeInEndOrder() {
        List<ExposureSession> found = sessions.findEndedBetween(ORG, ExposureSession.State.CLOSED, FROM, TO);

        assertThat(found).extracting(ExposureSession::getId).containsExactly(atFrom, inside, insideLater);
        assertThat(found).allSatisfy(s -> {
            assertThat(s.getState()).isEqualTo(ExposureSession.State.CLOSED);
            assertThat(s.getOrganizationId()).isEqualTo(ORG);
        });
    }

    @Test
    void anEmptyRangeFindsNothing() {
        assertThat(sessions.findEndedBetween(ORG, ExposureSession.State.CLOSED,
                Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2026-12-02T00:00:00Z"))).isEmpty();
    }
}
