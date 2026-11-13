package com.emsafe.radiationmonitoring.application;

import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.radiationmonitoring.domain.model.RadiationReading;
import com.emsafe.radiationmonitoring.domain.services.RadiationLevel;
import com.emsafe.radiationmonitoring.infrastructure.persistence.RadiationReadingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Lecturas del segmento hogar: las registra Device Management al recibirlas por
 * MQTT y las consultan el portal y la app. El nivel ya no viaja en el mensaje,
 * así que se clasifica aquí.
 */
@Service
@RequiredArgsConstructor
public class RadiationReadingService {
    private final RadiationReadingRepository readings;

    /** Lectura de un sensor fijo. {@code client} es el hogar dueño del Edge que la envió. */
    public record HomeReading(Device device, AppUser client, BigDecimal valueUT, boolean saturated,
                              Instant capturedAt) {
    }

    public record RadiationReadingView(Long id, String sensorId, Long deviceId, String deviceName, Double value,
                                       String level, String message, String plug, String location, Double latitude,
                                       Double longitude, Long clientId, String clientName,
                                       LocalDateTime recordedAt) {
    }

    @Transactional
    public RadiationReadingView recordHomeReading(HomeReading r) {
        LocalDateTime recordedAt = LocalDateTime.ofInstant(r.capturedAt(), ZoneId.systemDefault());
        double value = r.valueUT().doubleValue();
        AppUser client = r.client();
        String location = r.device().getLocation() != null ? r.device().getLocation()
                : client != null ? client.getLocation() : null;
        RadiationReading reading = readings.save(RadiationReading.builder()
                .readingDate(recordedAt.toLocalDate())
                .recordedAt(recordedAt)
                .value(value)
                .level(classify(value, r.saturated()))
                .message(r.saturated() ? "Saturated sensor: field was at least this value" : null)
                .sensorId(r.device().getSerialNumber())
                .location(location)
                .latitude(client != null ? client.getLatitude() : null)
                .longitude(client != null ? client.getLongitude() : null)
                .device(r.device())
                .client(client)
                .build());
        return view(reading, client);
    }

    @Transactional(readOnly = true)
    public List<RadiationReadingView> recent(int limit) {
        return readings.findRecent(PageRequest.of(0, limit)).stream().map(r -> view(r, null)).toList();
    }

    @Transactional(readOnly = true)
    public List<RadiationReadingView> bySensor(String sensorId, int limit) {
        return readings.findBySensorId(sensorId, PageRequest.of(0, limit)).stream().map(r -> view(r, null)).toList();
    }

    @Transactional(readOnly = true)
    public Optional<RadiationReadingView> latest(String sensorId) {
        return bySensor(sensorId, 1).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public long countBySensor(String sensorId) {
        return readings.countBySensorId(sensorId);
    }

    /** SAFE / CAUTION / DANGER con los umbrales de {@link RadiationLevel}. Saturada es siempre DANGER. */
    static String classify(double value, boolean saturated) {
        return (saturated ? "danger" : RadiationLevel.byValue(value)).toUpperCase(Locale.ROOT);
    }

    private static RadiationReadingView view(RadiationReading r, AppUser client) {
        Device d = r.getDevice();
        AppUser owner = client != null ? client : r.attributedClient();
        return new RadiationReadingView(r.getId(), r.getSensorId(), d != null ? d.getId() : null,
                d != null ? d.getName() : null, r.getValue(), r.getLevel(), r.getMessage(), r.getPlug(),
                r.getLocation(), r.getLatitude(), r.getLongitude(), owner != null ? owner.getId() : null,
                owner != null ? owner.getName() : null, r.getRecordedAt());
    }
}
