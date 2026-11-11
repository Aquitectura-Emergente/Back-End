package com.emsafe.aiforecasting.infrastructure.seed;

import com.emsafe.aiforecasting.application.UpsertExposureSnapshotHandler;
import com.emsafe.aiforecasting.application.fakes.InMemoryProcessedMessages;
import com.emsafe.aiforecasting.application.fakes.InMemoryWorkerSnapshotRepository;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import com.emsafe.aiforecasting.domain.services.ExposureProjectionService;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiForecastingDemoSeederTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);
    private final InMemoryWorkerSnapshotRepository snapshots = new InMemoryWorkerSnapshotRepository();
    private final UpsertExposureSnapshotHandler handler =
            new UpsertExposureSnapshotHandler(snapshots, new InMemoryProcessedMessages(), clock);
    private final UserRepository users = mock(UserRepository.class);
    private final AiForecastingDemoSeeder seeder = new AiForecastingDemoSeeder(users, handler, clock);

    private static AppUser client(long id, String type) {
        return AppUser.builder().id(id).role(Role.CLIENT).clientType(type).email(id + "@x").name("n").build();
    }

    @Test
    void seedsFiveWorkersInOneZoneWithDistinctRatesForTheFirstCompany() {
        when(users.findByRole(Role.CLIENT)).thenReturn(List.of(client(5L, "individual"), client(9L, "company"),
                client(3L, "company")));

        int created = seeder.seed();

        assertThat(created).isEqualTo(AiForecastingDemoSeeder.WORKERS);
        List<WorkerExposureSnapshot> seeded =
                snapshots.findByOrganizationIdAndZoneId(3L, AiForecastingDemoSeeder.ZONE_ID);
        assertThat(seeded).hasSize(5);
        assertThat(seeded).extracting(WorkerExposureSnapshot::positionId).doesNotHaveDuplicates();
        ExposureProjectionService projections = new ExposureProjectionService();
        List<BigDecimal> rates = seeded.stream()
                .map(s -> projections.accumulationRate(s.accumulatedIndex(), s.sessionStartedAt(), s.capturedAt()))
                .toList();
        assertThat(rates).doesNotHaveDuplicates();
        assertThat(seeded).allSatisfy(s -> {
            assertThat(s.capturedAt()).isEqualTo(clock.instant());
            assertThat(s.sessionStartedAt()).isBefore(s.capturedAt());
            assertThat(s.plannedShiftEnd()).isAfter(s.capturedAt());
        });
    }

    @Test
    void isIdempotent() {
        when(users.findByRole(Role.CLIENT)).thenReturn(List.of(client(3L, "company")));

        seeder.seed();
        int second = seeder.seed();

        assertThat(second).isZero();
        assertThat(snapshots.size()).isEqualTo(5);
    }

    @Test
    void doesNothingWithoutACompanyClient() {
        when(users.findByRole(Role.CLIENT)).thenReturn(List.of(client(5L, "individual")));

        assertThat(seeder.seed()).isZero();
        assertThat(snapshots.size()).isZero();
    }
}
