package com.emsafe.aiforecasting.infrastructure.seed;

import com.emsafe.aiforecasting.application.InboxOutcome;
import com.emsafe.aiforecasting.application.UpsertExposureSnapshotHandler;
import com.emsafe.aiforecasting.application.commands.UpsertExposureSnapshotCommand;
import com.emsafe.aiforecasting.infrastructure.AiForecastingConfiguration;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;

/**
 * Datos de demostración, apagado por defecto ({@code emsafe.ai-forecasting.seed-demo=false}).
 *
 * <p>Crea 5 operarios de la primera cuenta CLIENT {@code company} (menor id) en la misma zona y
 * con tasas distintas, a través del mismo handler del inbox. Corre en ApplicationReadyEvent, es
 * decir, después de {@code DataInitializer}. Los eventId incluyen la fecha: reiniciar el mismo
 * día no tiene efecto y al día siguiente se refrescan las mismas sesiones.</p>
 *
 * <p>Solo lee usuarios de IAM; los workerId son identificadores de integración ficticios.</p>
 */
@Component
@ConditionalOnProperty(prefix = "emsafe.ai-forecasting", name = "seed-demo", havingValue = "true")
public class AiForecastingDemoSeeder {

    static final int WORKERS = 5;
    static final String ZONE_ID = "DEMO-ZONE-A";
    private static final Logger log = LoggerFactory.getLogger(AiForecastingDemoSeeder.class);
    private static final long LIMIT_INDEX = 12_000L;
    /** Índice acumulado en 4 h de cada operario → tasas 0.5, 0.35, 0.2, 0.1 y 0.05 uT·s/s. */
    private static final long[] INDEX_AFTER_4H = {7_200, 5_040, 2_880, 1_440, 720};

    private final UserRepository users;
    private final UpsertExposureSnapshotHandler upsert;
    private final Clock clock;

    public AiForecastingDemoSeeder(UserRepository users, UpsertExposureSnapshotHandler upsert,
                                   @Qualifier(AiForecastingConfiguration.CLOCK) Clock clock) {
        this.users = users;
        this.upsert = upsert;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            int created = seed();
            log.info("AI Forecasting demo seed: {} snapshot(s) created/updated", created);
        } catch (RuntimeException e) {
            log.warn("AI Forecasting demo seed failed: {}", e.toString());
        }
    }

    /** @return snapshots creados o actualizados (0 si ya estaba sembrado hoy o no hay empresa). */
    int seed() {
        AppUser company = users.findByRole(Role.CLIENT).stream()
                .filter(u -> "company".equalsIgnoreCase(u.getClientType()))
                .min(Comparator.comparing(AppUser::getId))
                .orElse(null);
        if (company == null) {
            log.info("AI Forecasting demo seed skipped: no CLIENT company account");
            return 0;
        }
        Long org = company.getId();
        Instant now = clock.instant();
        Instant started = now.minus(Duration.ofHours(4));
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);

        int created = 0;
        for (int i = 0; i < WORKERS; i++) {
            int n = i + 1;
            InboxOutcome outcome = upsert.handle(new UpsertExposureSnapshotCommand(
                    "demo-af-snapshot-" + org + "-" + n + "-" + day, null, org, 1000L + n,
                    "DEMO-" + org + "-" + n, ZONE_ID, 100L + n,
                    BigDecimal.valueOf(INDEX_AFTER_4H[i]), BigDecimal.valueOf(LIMIT_INDEX),
                    started, started.plus(Duration.ofHours(8)), now));
            if (!outcome.duplicate()) {
                created++;
            }
        }
        return created;
    }
}
