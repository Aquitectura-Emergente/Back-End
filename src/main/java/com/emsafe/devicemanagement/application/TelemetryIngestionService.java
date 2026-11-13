package com.emsafe.devicemanagement.application;

import com.emsafe.devicemanagement.application.ports.AccountDirectory;
import com.emsafe.devicemanagement.application.ports.DosimetrySessionGateway;
import com.emsafe.devicemanagement.application.ports.DosimetrySessionGateway.DosimetryReading;
import com.emsafe.devicemanagement.application.ports.DosimetrySessionGateway.DosimetryResult;
import com.emsafe.devicemanagement.application.ports.HomeReadingGateway;
import com.emsafe.devicemanagement.application.ports.HomeReadingGateway.RecordedHomeReading;
import com.emsafe.devicemanagement.application.ports.HomeReadingNotifier;
import com.emsafe.devicemanagement.domain.events.TelemetryReadingsSynchronized;
import com.emsafe.devicemanagement.domain.model.BootId;
import com.emsafe.devicemanagement.domain.model.CapturedAtSource;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.DeviceSerial;
import com.emsafe.devicemanagement.domain.model.DeviceType;
import com.emsafe.devicemanagement.domain.model.EdgeNode;
import com.emsafe.devicemanagement.domain.model.MagneticFluxDensity;
import com.emsafe.devicemanagement.domain.model.OrganizationCode;
import com.emsafe.devicemanagement.domain.model.ReadingAttribution;
import com.emsafe.devicemanagement.domain.model.ReadingIdentity;
import com.emsafe.devicemanagement.domain.model.ReadingQuality;
import com.emsafe.devicemanagement.domain.model.SyncOrigin;
import com.emsafe.devicemanagement.domain.model.TelemetryReading;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import com.emsafe.devicemanagement.domain.repositories.DomainEventOutbox;
import com.emsafe.devicemanagement.domain.repositories.EdgeNodeRepository;
import com.emsafe.devicemanagement.domain.repositories.OrganizationCodeRepository;
import com.emsafe.devicemanagement.domain.repositories.TelemetryReadingRepository;
import com.emsafe.iam.domain.model.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Ingesta de un mensaje de telemetría. Guarda cada lectura cruda una sola vez
 * (ReadingIdentity), deriva su arranque y la enruta según el {@code deviceType}
 * del mensaje: el dosímetro a su jornada en Personal Dosimetry, el sensor fijo
 * al flujo del hogar. Las saturadas nunca se descartan.
 *
 * <p>El dispositivo queda bloqueado durante la transacción, así que las
 * lecturas de un mismo dispositivo se procesan de a una aunque haya varias
 * réplicas de ingesta.
 */
@Service
public class TelemetryIngestionService {
    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestionService.class);

    private final DeviceRepository devices;
    private final TelemetryReadingRepository readings;
    private final EdgeNodeRepository edges;
    private final OrganizationCodeRepository organizations;
    private final AccountDirectory accounts;
    private final DosimetrySessionGateway dosimetry;
    private final HomeReadingGateway home;
    private final HomeReadingNotifier notifier;
    private final DomainEventOutbox outbox;
    private final Clock clock;
    private final boolean lendingAllowed;
    private Supplier<BootId> bootIds = BootId::random;

    public TelemetryIngestionService(DeviceRepository devices, TelemetryReadingRepository readings,
                                     EdgeNodeRepository edges, OrganizationCodeRepository organizations,
                                     AccountDirectory accounts, DosimetrySessionGateway dosimetry,
                                     HomeReadingGateway home, HomeReadingNotifier notifier, DomainEventOutbox outbox,
                                     Clock clock,
                                     @Value("${emsafe.device-management.allow-device-lending:false}") boolean lendingAllowed) {
        this.devices = devices;
        this.readings = readings;
        this.edges = edges;
        this.organizations = organizations;
        this.accounts = accounts;
        this.dosimetry = dosimetry;
        this.home = home;
        this.notifier = notifier;
        this.outbox = outbox;
        this.clock = clock;
        this.lendingAllowed = lendingAllowed;
    }

    /** Para pruebas deterministas. */
    void useBootIds(Supplier<BootId> supplier) {
        this.bootIds = supplier;
    }

    public record IncomingReading(long sequence, Instant capturedAt, CapturedAtSource capturedAtSource,
                                  Instant receivedAt, MagneticFluxDensity flux, SyncOrigin origin,
                                  ReadingQuality quality) {
    }

    public record TelemetryBatch(String messageId, OrganizationCode organization, String edgeNodeId,
                                 DeviceSerial deviceId, DeviceType deviceType, List<IncomingReading> readings) {
    }

    public record IngestionResult(boolean accepted, int stored, int duplicates, String detail) {
        static IngestionResult rejected(String detail) {
            return new IngestionResult(false, 0, 0, detail);
        }
    }

    @Transactional
    public IngestionResult ingest(TelemetryBatch batch) {
        Instant now = clock.instant();
        String serial = batch.deviceId().value();
        Long organizationId = organizations.resolve(batch.organization()).orElse(null);
        if (organizationId == null) return reject(batch, "unknown organization " + batch.organization().value());

        EdgeNode edge = edges.findByEdgeNodeId(batch.edgeNodeId()).orElse(null);
        if (edge != null && !edge.belongsTo(organizationId)) {
            return reject(batch, "edge " + batch.edgeNodeId() + " belongs to another organization");
        }
        if (edge == null) log.warn("Telemetry from unregistered edge {} for {}", batch.edgeNodeId(), serial);

        Device device = devices.lockBySerial(serial).orElse(null);
        if (device == null) return reject(batch, "unknown device " + serial);

        ReadingAttribution attribution = device.attributionFor(organizationId,
                edge == null ? null : edge.getOrganizationId(), batch.deviceType(), lendingAllowed);
        if (attribution == ReadingAttribution.REJECTED) {
            return reject(batch, "device " + serial + " does not belong to organization " + batch.organization().value());
        }
        if (attribution == ReadingAttribution.LENT) {
            log.warn("Device {} belongs to organization {} but arrives from edge {} of {}; accepted as lent",
                    serial, device.organizationId(), batch.edgeNodeId(), batch.organization().value());
        }
        if (device.noteReportedType(batch.deviceType(), attribution, organizationId, batch.edgeNodeId(), now)) {
            log.warn("Device {} is registered as {} but reported {}; routing by the reported type",
                    serial, device.getDeviceType(), batch.deviceType());
        }

        AppUser homeAccount = batch.deviceType() == DeviceType.FIXED_SENSOR
                ? accounts.findById(organizationId).orElse(null) : null;
        List<RecordedHomeReading> notifications = new ArrayList<>();
        int stored = 0;
        int duplicates = 0;
        int saturated = 0;
        Instant first = null;
        Instant last = null;
        Instant lastReceived = null;
        for (IncomingReading in : batch.readings()) {
            ReadingIdentity identity = new ReadingIdentity(batch.deviceId(), in.sequence(), in.capturedAt());
            if (readings.findByIdentity(identity).isPresent()) {
                duplicates++;
                continue;
            }
            BootId boot = device.bootFor(in.sequence(), bootIds);
            TelemetryReading reading = TelemetryReading.capture(device.getId(), identity, organizationId,
                    batch.edgeNodeId(), batch.messageId(), in.capturedAtSource(), in.receivedAt(), in.flux(),
                    in.origin(), in.quality(), boot, batch.deviceType(), now);
            if (batch.deviceType() == DeviceType.PERSONAL_DOSIMETER) {
                routeToSession(device, reading);
            } else {
                RecordedHomeReading recorded = home.record(device, homeAccount, in.flux().microtesla(),
                        reading.isSaturated(), in.capturedAt());
                reading.recordAtHome(String.valueOf(recorded.readingId()));
                notifications.add(recorded);
            }
            readings.save(reading);
            stored++;
            if (reading.isSaturated()) saturated++;
            if (first == null || in.capturedAt().isBefore(first)) first = in.capturedAt();
            if (last == null || in.capturedAt().isAfter(last)) last = in.capturedAt();
            if (lastReceived == null || in.receivedAt().isAfter(lastReceived)) lastReceived = in.receivedAt();
        }

        if (lastReceived != null) device.markSeen(lastReceived, batch.edgeNodeId(), now);
        devices.save(device);
        if (edge != null) {
            edge.markSeen(now);
            edges.save(edge);
        }
        if (stored > 0) {
            outbox.append(new TelemetryReadingsSynchronized(serial, organizationId, batch.edgeNodeId(),
                    batch.messageId(), batch.readings().get(0).origin(), stored, saturated, first, last, now));
        }
        notifications.forEach(n -> notifier.notify(n, device.isUnregistered()));
        if (duplicates > 0) log.debug("{} duplicate reading(s) of {} ignored", duplicates, serial);
        return new IngestionResult(true, stored, duplicates, null);
    }

    private void routeToSession(Device device, TelemetryReading reading) {
        var session = dosimetry.sessionCovering(device.getId(), reading.getCapturedAt());
        if (session.isEmpty()) {
            reading.leaveUnassigned("no exposure session covers the capture time");
            log.info("No exposure session for {} at {}; reading kept unassigned",
                    device.getSerialNumber(), reading.getCapturedAt());
            return;
        }
        Instant from = reading.getCapturedAt().minus(device.config().samplingInterval().duration());
        DosimetryResult result = dosimetry.ingest(session.get(), new DosimetryReading(device.getId(),
                reading.getBootId(), reading.getSequenceNumber(), reading.getFluxUt(), from, reading.getCapturedAt(),
                reading.isSaturated()));
        if (result.status() == DosimetryResult.Status.REJECTED) {
            reading.rejectedByTarget(result.detail());
            log.warn("Personal Dosimetry rejected reading {}#{}: {}", device.getSerialNumber(),
                    reading.getSequenceNumber(), result.detail());
        } else {
            reading.assignToSession(session.get(), result.readingRef());
        }
    }

    private IngestionResult reject(TelemetryBatch batch, String detail) {
        log.warn("Telemetry message {} from {} rejected: {}", batch.messageId(), batch.edgeNodeId(), detail);
        return IngestionResult.rejected(detail);
    }
}
