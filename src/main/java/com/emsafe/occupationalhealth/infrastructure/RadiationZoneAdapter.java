package com.emsafe.occupationalhealth.infrastructure;

import com.emsafe.occupationalhealth.domain.model.ReportPeriod;
import com.emsafe.occupationalhealth.domain.model.ZoneRiskLevel;
import com.emsafe.occupationalhealth.domain.model.ZoneStatistics;
import com.emsafe.occupationalhealth.domain.services.ZoneExposurePort;
import com.emsafe.radiationmonitoring.domain.model.RadiationReading;
import com.emsafe.radiationmonitoring.infrastructure.persistence.RadiationReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementa ZoneExposurePort contra Radiation Monitoring (informe §5.6.4).
 *
 * Capa anticorrupción: traduce el nivel del otro contexto a ZoneRiskLevel y el
 * dominio nunca ve un RadiationReading.
 *
 * ── Qué es provisional aquí ───────────────────────────────────────────────────
 * El informe supone un contexto con zonas de planta propias. Ese modelo no
 * existe todavía, así que **una zona de planta se identifica con el id del
 * sensor (Device) que la cubre**: plantZoneId == Device.id. Es el mapeo mínimo
 * sobre lo que ya hay, y queda confinado a este adaptador — cuando exista el
 * modelo de zonas, solo cambia esta clase.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RadiationZoneAdapter implements ZoneExposurePort {

    private final RadiationReadingRepository readingRepository;

    /**
     * Antigüedad máxima de la lectura que sostiene la clasificación. Pasado ese
     * plazo la zona se considera sin datos, y por tanto RISK.
     */
    @Value("${emsafe.occupational-health.zone-reading-max-age-minutes:60}")
    private long maxReadingAgeMinutes;

    @Override
    public ZoneRiskLevel riskLevel(Long organizationId, Long plantZoneId) {
        if (plantZoneId == null) {
            return ZoneRiskLevel.RISK;
        }
        List<RadiationReading> readings = readingRepository.findByDeviceIdWithDevice(plantZoneId);
        RadiationReading latest = RadiationReading.latestOf(readings);

        if (latest == null) {
            log.debug("Zone {} has no readings; classified as RISK", plantZoneId);
            return ZoneRiskLevel.RISK;
        }
        if (isStale(latest)) {
            log.debug("Zone {} last reading is older than {} minutes; classified as RISK",
                    plantZoneId, maxReadingAgeMinutes);
            return ZoneRiskLevel.RISK;
        }
        return translate(latest.level());
    }

    /**
     * Agrega las lecturas de cada zona dentro del período.
     *
     * "Cada zona" son los sensores que han reportado algo, dado el mapeo
     * zona == sensor de este adaptador. Una zona sin lecturas en el período no
     * aparece en el resultado en vez de aparecer con ceros: un cero mediría algo
     * que nadie midió.
     */
    @Override
    public List<ZoneStatistics> zoneStatistics(Long organizationId, ReportPeriod period) {
        LocalDateTime from = period.startOfPeriod();
        LocalDateTime to = period.endOfPeriod();

        Map<Long, List<RadiationReading>> byZone = readingRepository.findAll().stream()
                .filter(r -> r.getDevice() != null && r.getValue() != null)
                .filter(r -> {
                    LocalDateTime at = r.timestamp();
                    return !at.isBefore(from) && !at.isAfter(to);
                })
                .collect(Collectors.groupingBy(r -> r.getDevice().getId()));

        return byZone.entrySet().stream()
                .map(e -> statisticsOf(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(ZoneStatistics::zoneId))
                .toList();
    }

    private ZoneStatistics statisticsOf(Long zoneId, List<RadiationReading> readings) {
        DoubleSummaryStatistics stats = readings.stream()
                .mapToDouble(RadiationReading::getValue)
                .summaryStatistics();
        return new ZoneStatistics(zoneId,
                BigDecimal.valueOf(stats.getAverage()).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(stats.getMax()).setScale(4, RoundingMode.HALF_UP),
                (int) stats.getCount());
    }

    private boolean isStale(RadiationReading reading) {
        return reading.timestamp()
                .isBefore(LocalDateTime.now().minusMinutes(maxReadingAgeMinutes));
    }

    /**
     * El edge es la autoridad de la clasificación y aquí no se reclasifica: solo
     * se renombra al vocabulario de este contexto. "danger" pasa a RISK porque
     * es el término que usa el informe para la zona que no admite a nadie.
     */
    private ZoneRiskLevel translate(String level) {
        return switch (level) {
            case "safe" -> ZoneRiskLevel.SAFE;
            case "caution" -> ZoneRiskLevel.CAUTION;
            default -> ZoneRiskLevel.RISK;
        };
    }
}
