package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.model.AlertThreshold;
import com.emsafe.devicemanagement.domain.model.BootId;
import com.emsafe.devicemanagement.domain.model.CapturedAtSource;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.DeviceSerial;
import com.emsafe.devicemanagement.domain.model.DeviceType;
import com.emsafe.devicemanagement.domain.model.MagneticFluxDensity;
import com.emsafe.devicemanagement.domain.model.ReadingIdentity;
import com.emsafe.devicemanagement.domain.model.ReadingQuality;
import com.emsafe.devicemanagement.domain.model.SyncOrigin;
import com.emsafe.devicemanagement.domain.model.TelemetryReading;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:dmpersistence;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE"
})
@ActiveProfiles("test")
@Import({JpaDeviceRepository.class, JpaDomainEventOutbox.class, JpaTelemetryReadingRepository.class,
        DeviceManagementPersistenceTest.Support.class})
class DeviceManagementPersistenceTest {
    static final Instant T0 = Instant.parse("2026-11-12T15:00:00Z");

    @TestConfiguration
    static class Support {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().addModule(new JavaTimeModule()).build();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(T0, ZoneOffset.UTC);
        }
    }

    @Autowired JpaDeviceRepository devices;
    @Autowired JpaTelemetryReadingRepository readings;
    @Autowired DeviceAuditEntryJpaRepository audit;
    @Autowired DeviceConfigVersionJpaRepository configs;
    @Autowired DeviceManagementEventJpaRepository events;
    @Autowired TelemetryReadingJpaRepository readingRows;

    @Test
    void savingTheAggregateStoresItsAuditConfigHistoryAndEventsInTheSameTransaction() {
        Device d = Device.register(DeviceSerial.of("EMSafe-DOS-01"), DeviceType.PERSONAL_DOSIMETER, null, null,
                "admin", T0);
        d = devices.save(d);
        d.changeAlertThreshold(AlertThreshold.of(250), "sso", T0.plusSeconds(1));
        devices.save(d);

        assertThat(audit.countByDeviceId(d.getId())).isEqualTo(2);
        assertThat(configs.findByDeviceIdOrderByConfigVersionAsc(d.getId()))
                .extracting(v -> v.getConfigVersion()).containsExactly(1, 2);
        assertThat(events.findByEventTypeOrderByOccurredAtAsc("DeviceConfigChanged")).hasSize(2)
                .last().satisfies(e -> assertThat(e.getPayload()).contains("\"configVersion\":2"));
        assertThat(devices.lockBySerial("EMSafe-DOS-01")).isPresent();
    }

    @Test
    void theReadingIdentityIsUniqueInTheDatabase() {
        Device d = devices.save(Device.register(DeviceSerial.of("S-9"), DeviceType.FIXED_SENSOR, null, null, "a", T0));
        ReadingIdentity id = new ReadingIdentity(DeviceSerial.of("S-9"), 5, T0);
        readings.save(reading(d, id));
        assertThat(readings.findByIdentity(id)).isPresent();
        assertThatThrownBy(() -> readingRows.saveAndFlush(reading(d, id)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static TelemetryReading reading(Device d, ReadingIdentity id) {
        return TelemetryReading.capture(d.getId(), id, null, "EDGE", "m-1", CapturedAtSource.DEVICE_CLOCK, T0,
                MagneticFluxDensity.ofMicrotesla(10), SyncOrigin.REALTIME, ReadingQuality.VALID, BootId.random(),
                DeviceType.FIXED_SENSOR, T0);
    }
}
