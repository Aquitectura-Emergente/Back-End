package com.emsafe.clientportal.application;

import com.emsafe.clientportal.interfaces.rest.dto.*;
import com.emsafe.radiationmonitoring.domain.model.RadiationReading;
import com.emsafe.radiationmonitoring.infrastructure.persistence.RadiationReadingRepository;
import com.emsafe.iottelemetry.domain.model.Device;
import com.emsafe.iottelemetry.infrastructure.persistence.DeviceRepository;
import com.emsafe.radiationmonitoring.domain.services.RadiationLevel;
import com.emsafe.shared.exception.BadRequestException;
import com.emsafe.shared.exception.ResourceNotFoundException;
import com.emsafe.iam.interfaces.rest.dto.ChangePasswordRequest;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read/update operations scoped to a single client (the EMSafe mobile app user).
 * Every method is keyed by the clientId taken from the JWT, so a client can only
 * ever see their own devices, readings and alerts.
 */
@Service
@RequiredArgsConstructor
public class ClientService {

    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final RadiationReadingRepository radiationReadingRepository;
    private final PasswordEncoder passwordEncoder;

    /** Threshold (µT) above which radiation is considered to exceed the safe limit. */
    private static final double SAFETY_THRESHOLD = RadiationLevel.DANGER_UT;

    // ─── Profile ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ClientProfileDto getProfile(Long clientId) {
        return ClientProfileDto.from(getClient(clientId));
    }

    @Transactional
    public ClientProfileDto updateProfile(Long clientId, UpdateClientProfileRequest req) {
        AppUser u = getClient(clientId);
        if (StringUtils.hasText(req.name()))     u.setName(req.name());
        if (StringUtils.hasText(req.phone()))    u.setPhone(req.phone());
        if (StringUtils.hasText(req.location())) u.setLocation(req.location());
        if (StringUtils.hasText(req.address()))  u.setAddress(req.address());
        if (req.latitude() != null)              u.setLatitude(req.latitude());
        if (req.longitude() != null)             u.setLongitude(req.longitude());
        return ClientProfileDto.from(userRepository.save(u));
    }

    @Transactional
    public void changePassword(Long clientId, ChangePasswordRequest req) {
        AppUser u = getClient(clientId);
        if (!passwordEncoder.matches(req.currentPassword(), u.getPasswordHash())) {
            throw new BadRequestException("Current password is incorrect");
        }
        u.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepository.save(u);
    }

    /**
     * Self-service account deletion (right to be forgotten). Requires the
     * current password as confirmation; only ever deletes the JWT owner.
     * The client's devices are unlinked automatically (FK ON DELETE SET NULL)
     * and historical readings remain as anonymous system data.
     */
    @Transactional
    public void deleteAccount(Long clientId, String password) {
        AppUser u = getClient(clientId);
        if (password == null || !passwordEncoder.matches(password, u.getPasswordHash())) {
            throw new BadRequestException("Password is incorrect");
        }
        userRepository.delete(u);
    }

    // ─── Devices ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ClientDeviceDto> getDevices(Long clientId) {
        return deviceRepository.findByClient_IdOrderByIdAsc(clientId).stream()
                .map(d -> toDeviceDto(d, radiationReadingRepository.findByDeviceIdWithDevice(d.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ClientDeviceDto getDeviceDetail(Long clientId, Long deviceId) {
        Device d = deviceRepository.findByIdAndClient_Id(deviceId, clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));
        return toDeviceDto(d, radiationReadingRepository.findByDeviceIdWithDevice(deviceId));
    }

    @Transactional(readOnly = true)
    public List<ClientReadingDto> getDeviceReadings(Long clientId, Long deviceId) {
        // Ownership check: throws if the device doesn't belong to this client
        deviceRepository.findByIdAndClient_Id(deviceId, clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));
        return radiationReadingRepository.findByDeviceIdWithDevice(deviceId).stream()
                .map(this::toReadingDto)
                .toList();
    }

    /**
     * Stores the relay state the user wants (ON | OFF) for one of THEIR devices.
     * The edge polls GET /api/v1/devices/{serial}/plug to pick up the order and
     * drive the physical relay (mobile → backend → edge → device).
     */
    @Transactional
    public ClientDeviceDto setDesiredPlug(Long clientId, Long deviceId, String plug) {
        Device d = deviceRepository.findByIdAndClient_Id(deviceId, clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));
        d.requestPlug(plug);
        deviceRepository.save(d);
        return toDeviceDto(d, radiationReadingRepository.findByDeviceIdWithDevice(deviceId));
    }

    // ─── Readings ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ClientReadingDto> getReadings(Long clientId) {
        return radiationReadingRepository.findByClientIdWithDevice(clientId).stream()
                .map(this::toReadingDto)
                .toList();
    }

    // ─── Dashboard ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ClientDashboardDto getDashboard(Long clientId) {
        List<Device> devices = deviceRepository.findByClient_IdOrderByIdAsc(clientId);
        List<RadiationReading> readings = radiationReadingRepository.findByClientIdWithDevice(clientId);

        List<ClientDeviceDto> deviceDtos = devices.stream()
                .map(d -> toDeviceDto(d, readings.stream()
                        .filter(r -> r.getDevice() != null && r.getDevice().getId().equals(d.getId()))
                        .toList()))
                .toList();

        // Average over the last 24h so it tracks current conditions and matches
        // the live level pill (an all-time average would stay high forever after
        // any single spike, which reads as "high average but green level").
        LocalDateTime avgCutoff = LocalDateTime.now().minusHours(24);
        List<RadiationReading> recent = readings.stream()
                .filter(r -> r.timestamp().isAfter(avgCutoff))
                .toList();
        double avg = recent.isEmpty() ? 0.0
                : round3(recent.stream().mapToDouble(RadiationReading::getValue).average().orElse(0.0));
        double max = readings.stream().mapToDouble(RadiationReading::getValue).max().orElse(0.0);
        int activeCount = (int) devices.stream().filter(Device::isActive).count();
        int alertCount = (int) readings.stream().filter(RadiationReading::isAlert).count();

        // readings already come sorted DESC by recordedAt (most recent first)
        List<ClientReadingDto> latest = readings.stream()
                .limit(10)
                .map(this::toReadingDto)
                .toList();

        // Overall level = worst current level among the client's devices
        // (derived from the edge-computed levels, not recomputed here).
        String overallLevel = deviceDtos.stream()
                .map(ClientDeviceDto::latestLevel)
                .filter(l -> l != null)
                .reduce("safe", RadiationLevel::worse);

        return new ClientDashboardDto(
                devices.size(),
                activeCount,
                avg,
                round3(max),
                overallLevel,
                SAFETY_THRESHOLD,
                alertCount,
                deviceDtos,
                latest
        );
    }

    // ─── Reports (US19/US20/US22 + TS07) ──────────────────────────────────────

    /**
     * Aggregated radiation report: "month" buckets the last 30 days per day,
     * "year" buckets the last 12 months per month. Levels come from the edge
     * (smart edge) via levelOf; alerts = readings above "safe".
     */
    @Transactional(readOnly = true)
    public ClientReportDto getReport(Long clientId, String period) {
        boolean yearly = "year".equalsIgnoreCase(period);
        LocalDate from = yearly
                ? LocalDate.now().minusMonths(11).withDayOfMonth(1)
                : LocalDate.now().minusDays(29);

        List<RadiationReading> readings = radiationReadingRepository
                .findByClientIdWithDevice(clientId).stream()
                .filter(r -> r.getReadingDate() != null && r.getValue() != null
                        && !r.getReadingDate().isBefore(from))
                .toList();

        // Group readings into day/month buckets, oldest first.
        Map<String, List<RadiationReading>> grouped = new LinkedHashMap<>();
        readings.stream()
                .sorted(Comparator.comparing(RadiationReading::getReadingDate))
                .forEach(r -> {
                    String key = yearly
                            ? YearMonth.from(r.getReadingDate())
                                    .format(DateTimeFormatter.ofPattern("MMM yyyy"))
                            : r.getReadingDate()
                                    .format(DateTimeFormatter.ofPattern("dd MMM"));
                    grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
                });

        List<ClientReportDto.Bucket> buckets = grouped.entrySet().stream()
                .map(e -> {
                    List<RadiationReading> group = e.getValue();
                    double avg = group.stream()
                            .mapToDouble(RadiationReading::getValue).average().orElse(0);
                    double peak = group.stream()
                            .mapToDouble(RadiationReading::getValue).max().orElse(0);
                    int alerts = (int) group.stream().filter(RadiationReading::isAlert).count();
                    return new ClientReportDto.Bucket(
                            e.getKey(), round3(avg), round3(peak), group.size(), alerts);
                })
                .toList();

        double avg = readings.stream()
                .mapToDouble(RadiationReading::getValue).average().orElse(0);
        double peak = readings.stream()
                .mapToDouble(RadiationReading::getValue).max().orElse(0);
        int alerts = (int) readings.stream().filter(RadiationReading::isAlert).count();

        return new ClientReportDto(
                yearly ? "year" : "month",
                round3(avg), round3(peak), readings.size(), alerts, buckets);
    }

    // ─── Alerts (derived from readings) ───────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ClientAlertDto> getAlerts(Long clientId) {
        return radiationReadingRepository.findByClientIdWithDevice(clientId).stream()
                .filter(RadiationReading::isAlert)
                .sorted(Comparator.comparing(RadiationReading::timestamp).reversed())
                .map(r -> {
                    String lvl = r.level();
                    String deviceName = r.getDevice() != null ? r.getDevice().getName() : "Sensor";
                    return new ClientAlertDto(
                            r.getId(),
                            lvl,
                            lvl,
                            "danger".equals(lvl) ? "Critical radiation level" : "Elevated radiation level",
                            deviceName + " recorded " + r.getValue() + " µT",
                            r.getValue(),
                            r.getDevice() != null ? r.getDevice().getId() : null,
                            r.getDevice() != null ? r.getDevice().getName() : null,
                            r.getReadingDate() != null ? r.getReadingDate().toString() : null,
                            r.getRecordedAt() != null ? r.getRecordedAt().toString() : null
                    );
                })
                .toList();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private AppUser getClient(Long clientId) {
        if (clientId == null) {
            throw new BadRequestException("Missing user id in token");
        }
        return userRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("User", clientId));
    }

    private ClientDeviceDto toDeviceDto(Device d, List<RadiationReading> deviceReadings) {
        RadiationReading latest = RadiationReading.latestOf(deviceReadings);
        Double latestVal = latest != null ? latest.getValue() : null;
        return new ClientDeviceDto(
                d.getId(),
                d.getName(),
                d.getType(),
                d.getLocation(),
                d.getStatus(),
                d.getSerialNumber(),
                d.getInstallDate() != null ? d.getInstallDate().toString() : null,
                latestVal,
                latest != null ? latest.level() : null,
                latest != null && latest.getReadingDate() != null ? latest.getReadingDate().toString() : null,
                deviceReadings.size(),
                latest != null ? latest.getPlug() : null,
                d.getDesiredPlug()
        );
    }

    private ClientReadingDto toReadingDto(RadiationReading r) {
        return new ClientReadingDto(
                r.getId(),
                r.getValue(),
                r.level(),
                r.getReadingDate() != null ? r.getReadingDate().toString() : null,
                r.getRecordedAt() != null ? r.getRecordedAt().toString() : null,
                r.getDevice() != null ? r.getDevice().getId() : null,
                r.getDevice() != null ? r.getDevice().getName() : null
        );
    }

    private double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
