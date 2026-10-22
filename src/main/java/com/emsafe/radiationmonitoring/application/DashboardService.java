package com.emsafe.radiationmonitoring.application;

import com.emsafe.alerts.interfaces.rest.dto.AlertDto;
import com.emsafe.radiationmonitoring.interfaces.rest.dto.ChartDataDto;
import com.emsafe.radiationmonitoring.interfaces.rest.dto.ClientRadiationDto;
import com.emsafe.radiationmonitoring.interfaces.rest.dto.LatestWorkOrderDto;
import com.emsafe.radiationmonitoring.interfaces.rest.dto.RadiationPointDto;
import com.emsafe.radiationmonitoring.interfaces.rest.dto.StatsDto;
import com.emsafe.radiationmonitoring.domain.model.RadiationReading;
import com.emsafe.alerts.infrastructure.persistence.AlertRepository;
import com.emsafe.radiationmonitoring.infrastructure.persistence.RadiationReadingRepository;
import com.emsafe.radiationmonitoring.domain.services.RadiationLevel;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.fieldservice.domain.model.WorkOrderStatus;
import com.emsafe.fieldservice.infrastructure.persistence.SensorRepository;
import com.emsafe.fieldservice.infrastructure.persistence.WorkOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final WorkOrderRepository workOrderRepository;
    private final SensorRepository sensorRepository;
    private final UserRepository userRepository;
    private final AlertRepository alertRepository;
    private final RadiationReadingRepository radiationReadingRepository;

    /** µT — safe limit shown in the admin dashboard (ICNIRP 50 Hz reference). */
    private static final double SAFETY_THRESHOLD = RadiationLevel.DANGER_UT;

    public StatsDto getStats() {
        long pending = workOrderRepository.countByStatus(WorkOrderStatus.PENDING);
        long activeClients = userRepository.findByRole(Role.CLIENT).stream()
                .filter(u -> "active".equalsIgnoreCase(u.getStatus()))
                .count();
        long criticalAlerts = alertRepository.countByType("danger");
        long totalSensors = sensorRepository.count();
        Double radAvg = radiationReadingRepository.findAverage();
        double currentRadAvg = radAvg != null ? Math.round(radAvg * 1000.0) / 1000.0 : 0.0;

        return new StatsDto(
                totalSensors > 0 ? totalSensors : 1284,
                "+12%",
                activeClients,
                "+3",
                pending,
                "Stable",
                criticalAlerts,
                criticalAlerts > 1 ? "High Risk" : "Normal",
                4.2,
                currentRadAvg,
                SAFETY_THRESHOLD
        );
    }

    public List<AlertDto> getAlerts() {
        return alertRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(AlertDto::from).toList();
    }

    public List<LatestWorkOrderDto> getLatestWorkOrders() {
        return workOrderRepository.findTop4ByOrderByScheduledDateDesc()
                .stream().map(LatestWorkOrderDto::from).toList();
    }

    public ChartDataDto getChartData() {
        List<Integer> activitySeries = List.of(5, 14, 10, 22, 18, 30, 26, 38, 32, 45, 40, 52);
        List<String> activityCats = List.of("01 May", "", "", "10 May", "", "", "20 May", "", "", "", "30 May", "");

        List<RadiationReading> readings = radiationReadingRepository.findAllSorted();
        List<Double> radSeries = readings.stream()
                .map(RadiationReading::getValue)
                .toList();

        long tx = countByState("TX");
        long ca = countByState("CA");
        long wa = countByState("WA");
        long ny = countByState("NY");

        return new ChartDataDto(
                new ChartDataDto.SystemActivity(activitySeries, activityCats),
                new ChartDataDto.RadiationTrends(radSeries.isEmpty()
                        ? List.of(75.0, 85.0, 80.0, 95.0, 90.0, 105.0, 115.0, 110.0)
                        : radSeries),
                new ChartDataDto.Regional(
                        List.of(tx, ca, wa, ny),
                        List.of("TX", "CA", "WA", "NY")
                )
        );
    }

    public List<RadiationPointDto> getRadiationMap() {
        return radiationReadingRepository.findAllSorted().stream()
                .map(r -> new RadiationPointDto(
                        r.getId(),
                        r.getLatitude(),
                        r.getLongitude(),
                        r.getLocation(),
                        r.getSensorId(),
                        r.getValue(),
                        RadiationLevel.of(r),
                        r.getReadingDate() != null ? r.getReadingDate().toString() : null
                ))
                .toList();
    }

    /**
     * Groups radiation readings by their client user (via device → client).
     * Returns one marker per client at the location of their highest reading.
     */
    public List<ClientRadiationDto> getRadiationMapByClient() {
        List<RadiationReading> readings = radiationReadingRepository.findAllWithDeviceAndClient();

        // Group readings by client; skip readings not linked to a device with a client
        Map<Long, List<RadiationReading>> byClient = readings.stream()
                .filter(r -> r.getDevice() != null && r.getDevice().getClient() != null)
                .collect(Collectors.groupingBy(r -> r.getDevice().getClient().getId()));

        List<ClientRadiationDto> result = new ArrayList<>();

        for (Map.Entry<Long, List<RadiationReading>> entry : byClient.entrySet()) {
            Long clientId = entry.getKey();
            List<RadiationReading> clientReadings = entry.getValue();

            // Get the client entity (same for all readings in this group)
            var clientUser = clientReadings.get(0).getDevice().getClient();

            // Marker uses the CLIENT'S registered lat/lng (their physical site)
            Double lat = clientUser.getLatitude();
            Double lng = clientUser.getLongitude();
            String address = clientUser.getAddress() != null ? clientUser.getAddress() : clientUser.getLocation();

            // One entry per device showing its LATEST reading (by recordedAt), so the
            // map reflects the sensor's CURRENT measurement — not a historical peak.
            Map<Long, List<RadiationReading>> byDevice = clientReadings.stream()
                    .filter(r -> r.getDevice() != null)
                    .collect(Collectors.groupingBy(r -> r.getDevice().getId()));

            List<ClientRadiationDto.DeviceReadingDto> deviceDtos = byDevice.values().stream()
                    .map(list -> {
                        RadiationReading latest = list.stream()
                                .max(Comparator.comparing((RadiationReading r) -> readingTs(r)))
                                .orElse(null);
                        if (latest == null) return null;
                        return new ClientRadiationDto.DeviceReadingDto(
                                latest.getDevice().getId(),
                                latest.getDevice().getName(),
                                latest.getDevice().getType(),
                                latest.getDevice().getSerialNumber(),
                                latest.getDevice().getLocation(), // zone/room within the facility
                                latest.getDevice().getStatus(),
                                latest.getValue(),
                                RadiationLevel.of(latest),
                                latest.getReadingDate() != null ? latest.getReadingDate().toString() : null
                        );
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingDouble(ClientRadiationDto.DeviceReadingDto::latestValue).reversed())
                    .toList();

            // Client headline value/level = the worst CURRENT sensor (highest latest reading).
            double maxVal = deviceDtos.stream()
                    .mapToDouble(ClientRadiationDto.DeviceReadingDto::latestValue)
                    .max().orElse(0.0);
            String level = deviceDtos.stream()
                    .map(ClientRadiationDto.DeviceReadingDto::level)
                    .reduce("safe", RadiationLevel::worse);

            result.add(new ClientRadiationDto(
                    clientId,
                    clientUser.getName(),
                    lat,
                    lng,
                    address,
                    maxVal,
                    level,
                    deviceDtos
            ));
        }

        result.sort(Comparator.comparingDouble(ClientRadiationDto::maxValue).reversed());
        return result;
    }

    private long countByState(String state) {
        return workOrderRepository.countByCitySuffix(", " + state);
    }

    /** Best-effort timestamp for a reading: precise recordedAt, else start of its date. */
    private static LocalDateTime readingTs(RadiationReading r) {
        if (r.getRecordedAt() != null) return r.getRecordedAt();
        return r.getReadingDate() != null ? r.getReadingDate().atStartOfDay() : LocalDateTime.MIN;
    }
}
