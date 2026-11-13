package com.emsafe.occupationalhealth.infrastructure;

import com.emsafe.iottelemetry.domain.model.Device;
import com.emsafe.iottelemetry.infrastructure.persistence.DeviceRepository;
import com.emsafe.occupationalhealth.domain.model.ReportPeriod;
import com.emsafe.occupationalhealth.domain.model.ZoneRiskLevel;
import com.emsafe.occupationalhealth.domain.model.ZoneStatistics;
import com.emsafe.occupationalhealth.domain.services.ZoneExposurePort;
import com.emsafe.plantconfiguration.domain.model.PlantZone;
import com.emsafe.plantconfiguration.infrastructure.persistence.PlantZoneRepository;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementa ZoneExposurePort contra Radiation Monitoring (informe §5.6.4).
 *
 * Capa anticorrupción: traduce el nivel del otro contexto a ZoneRiskLevel y el
 * dominio nunca ve un RadiationReading.
 *
 * ── Historia de este adaptador ────────────────────────────────────────────────
 * Hasta la V22 no existía un modelo de zonas, así que aquí se identificaba la
 * zona con el id del sensor que la cubre (plantZoneId == Device.id). Era el
 * mapeo mínimo sobre lo que había, y el comentario de entonces anunciaba que
 * cuando existiera el modelo de zonas solo cambiaría esta clase. Es lo que ha
 * pasado: `plantconfiguration` es ahora el dueño de la zona y una zona agrupa
 * **varios** sensores, que es la topología real del segmento industrial.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RadiationZoneAdapter implements ZoneExposurePort {

    private final RadiationReadingRepository readingRepository;
    private final DeviceRepository deviceRepository;
    private final PlantZoneRepository zoneRepository;

    /**
     * Antigüedad máxima de la lectura que sostiene la clasificación. Pasado ese
     * plazo el sensor no cuenta, y una zona sin ningún sensor fresco es RISK.
     */
    @Value("${emsafe.occupational-health.zone-reading-max-age-minutes:60}")
    private long maxReadingAgeMinutes;

    /**
     * Riesgo vigente de una zona.
     *
     * <p>Con varios sensores por zona hay que decidir cuál manda, y aquí gana
     * <b>el peor</b>: si un solo sensor de la zona reporta DANGER, la zona no
     * admite a nadie. Promediar escondería justamente el punto caliente al que
     * no se debe mandar un operario.
     *
     * <p>Una zona sin sensores, o sin ninguna lectura reciente, es RISK: no es
     * que sea segura, es que no se sabe.
     */
    @Override
    public ZoneRiskLevel riskLevel(Long organizationId, Long plantZoneId) {
        if (plantZoneId == null) {
            return ZoneRiskLevel.RISK;
        }
        // La zona tiene que ser de la organización que pregunta: si no, se
        // responde RISK en vez de filtrar el estado de otra planta.
        if (zoneRepository.findByIdAndOrganizationId(plantZoneId, organizationId).isEmpty()) {
            log.debug("Zone {} does not belong to organization {}; classified as RISK",
                    plantZoneId, organizationId);
            return ZoneRiskLevel.RISK;
        }

        List<Device> zoneDevices = deviceRepository.findByPlantZoneIdOrderByIdAsc(plantZoneId);
        if (zoneDevices.isEmpty()) {
            log.debug("Zone {} has no devices; classified as RISK", plantZoneId);
            return ZoneRiskLevel.RISK;
        }

        ZoneRiskLevel worst = null;
        for (Device device : zoneDevices) {
            RadiationReading latest = RadiationReading.latestOf(
                    readingRepository.findByDeviceIdWithDevice(device.getId()));
            if (latest == null || isStale(latest)) {
                continue;
            }
            ZoneRiskLevel level = translate(latest.level());
            if (worst == null || severityOf(level) > severityOf(worst)) {
                worst = level;
            }
        }

        if (worst == null) {
            log.debug("Zone {} has no reading newer than {} minutes; classified as RISK",
                    plantZoneId, maxReadingAgeMinutes);
            return ZoneRiskLevel.RISK;
        }
        return worst;
    }

    /**
     * Agrega las lecturas de cada zona de la organización dentro del período.
     *
     * <p>Agrupa por la zona del sensor, así que una zona con tres sensores
     * produce una fila con los tres agregados juntos. Una zona sin lecturas en
     * el período no aparece, en vez de aparecer con ceros: un cero mediría algo
     * que nadie midió.
     */
    @Override
    public List<ZoneStatistics> zoneStatistics(Long organizationId, ReportPeriod period) {
        LocalDateTime from = period.startOfPeriod();
        LocalDateTime to = period.endOfPeriod();

        Set<Long> organizationZones = zoneRepository
                .findByOrganizationIdOrderByNameAsc(organizationId).stream()
                .map(PlantZone::getId)
                .collect(Collectors.toSet());
        if (organizationZones.isEmpty()) {
            return List.of();
        }

        Map<Long, List<RadiationReading>> byZone = readingRepository.findAll().stream()
                .filter(r -> r.getDevice() != null && r.getValue() != null)
                .filter(r -> r.getDevice().getPlantZoneId() != null)
                .filter(r -> organizationZones.contains(r.getDevice().getPlantZoneId()))
                .filter(r -> {
                    LocalDateTime at = r.timestamp();
                    return !at.isBefore(from) && !at.isAfter(to);
                })
                .collect(Collectors.groupingBy(r -> r.getDevice().getPlantZoneId()));

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

    /** Orden de gravedad para elegir el peor sensor de la zona. */
    private int severityOf(ZoneRiskLevel level) {
        return switch (level) {
            case SAFE -> 0;
            case CAUTION -> 1;
            case RISK -> 2;
        };
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
