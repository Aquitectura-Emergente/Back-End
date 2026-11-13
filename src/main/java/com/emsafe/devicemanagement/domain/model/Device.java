package com.emsafe.devicemanagement.domain.model;

import com.emsafe.devicemanagement.domain.events.DeviceConfigChanged;
import com.emsafe.devicemanagement.domain.events.DeviceConnectivityChanged;
import com.emsafe.devicemanagement.domain.events.DeviceDomainEvent;
import com.emsafe.devicemanagement.domain.events.DeviceInactivityDetected;
import com.emsafe.devicemanagement.domain.events.PlugCommandRequested;
import com.emsafe.devicemanagement.domain.events.PlugStateReported;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.shared.exception.BadRequestException;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Agregado raíz de Device Management.
 *
 * <p>Controla el ciclo de vida, la conectividad, la configuración versionada,
 * el estado del enchufe y la derivación del arranque. Cada cambio genera su
 * entrada de auditoría y, cuando corresponde, un evento de dominio; el
 * repositorio los guarda junto con el dispositivo.
 *
 * <p>Las anotaciones JPA son solo mapeo. El builder construye dispositivos ya
 * existentes (semillas, pruebas); los cambios de estado pasan por los métodos.
 */
@Entity
@Table(name = "devices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Device {
    private static final String SYSTEM = "system";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    /** Texto libre que muestran el portal y la app ("Sensor", "Monitor"...). */
    @Column(nullable = false, length = 50)
    private String type;

    @Column(length = 200)
    private String location;

    /** Estado operativo que usan las órdenes de trabajo y los frontends. */
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = DeviceStatus.ACTIVE;

    @Column(length = 100)
    private String serialNumber;

    @Column
    private LocalDate installDate;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();



    /** Organización o cliente dueño del dispositivo. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private AppUser client;

    /**
     * Zona de planta (Plant Configuration). Se guarda como id y no como relación
     * para no atar los dos contextos en ambos sentidos.
     */
    @Column(name = "plant_zone_id")
    private Long plantZoneId;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", length = 30)
    @Builder.Default
    private DeviceType deviceType = DeviceType.FIXED_SENSOR;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", length = 20)
    @Builder.Default
    private DeviceLifecycleStatus lifecycleStatus = DeviceLifecycleStatus.INSTALLED;

    @Enumerated(EnumType.STRING)
    @Column(name = "connectivity", length = 10)
    @Builder.Default
    private ConnectivityStatus connectivity = ConnectivityStatus.OFFLINE;

    @Column(name = "connectivity_changed_at")
    private Instant connectivityChangedAt;

    @Column(name = "connectivity_edge_node_id", length = 100)
    private String connectivityEdgeNodeId;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_reported_type", length = 30)
    private DeviceType lastReportedType;

    @Column(name = "firmware_version", length = 50)
    private String firmwareVersion;

    @Column(name = "battery_level")
    private Integer batteryLevel;

    @Column(name = "boot_id", length = 64)
    private String bootId;

    @Column(name = "last_sequence")
    private Long lastSequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "plug_power", length = 10)
    private PlugPower plugPower;

    @Column(name = "plug_command_id", length = 64)
    private String plugCommandId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plug_source", length = 10)
    private PlugStateSource plugSource;

    @Column(name = "plug_changed_at")
    private Instant plugChangedAt;

    @Embedded
    @AttributeOverride(name = "version", column = @Column(name = "config_version"))
    @Builder.Default
    private DeviceConfig config = DeviceConfig.initial(Instant.now());

    @Transient
    @Getter(AccessLevel.NONE)
    private List<DeviceAuditEntry> pendingAudit;

    @Transient
    @Getter(AccessLevel.NONE)
    private List<DeviceConfigVersion> pendingConfigVersions;

    @Transient
    @Getter(AccessLevel.NONE)
    private List<DeviceDomainEvent> pendingEvents;

    // ─── Registro ─────────────────────────────────────────────────────────────

    /**
     * Alta de un dispositivo. Sin dueño queda en almacén; con dueño, instalado.
     * Un dosímetro publica su configuración inicial al Edge.
     */
    public static Device register(DeviceSerial serial, DeviceType deviceType, String name, AppUser owner,
                                  String actor, Instant now) {
        Objects.requireNonNull(serial, "serial");
        Objects.requireNonNull(deviceType, "deviceType");
        boolean installed = owner != null;
        Device device = Device.builder()
                .name(name == null || name.isBlank() ? defaultName(deviceType, serial) : name.trim())
                .type(displayType(deviceType))
                .serialNumber(serial.value())
                .deviceType(deviceType)
                .status(installed ? DeviceStatus.ACTIVE : DeviceStatus.UNREGISTERED)
                .lifecycleStatus(installed ? DeviceLifecycleStatus.INSTALLED : DeviceLifecycleStatus.IN_STOCK)
                .client(owner)
                .installDate(installed ? LocalDate.ofInstant(now, ZoneId.systemDefault()) : null)
                .createdAt(LocalDateTime.ofInstant(now, ZoneId.systemDefault()))
                .config(DeviceConfig.initial(now))
                .plugPower(deviceType == DeviceType.SMART_PLUG ? PlugPower.UNKNOWN : null)
                .build();
        device.audit(AuditEntryType.REGISTERED, "deviceType", null, deviceType.name(), actor, now,
                installed ? "owner=" + owner.getId() : null);
        device.pendingConfigVersions().add(DeviceConfigVersion.of(device, device.config, actor));
        device.publishConfigIfDosimeter();
        return device;
    }

    /**
     * Sensor que el Edge informa con un serial no registrado (modelo antiguo).
     * Queda sin cliente hasta que un técnico lo reclame en una instalación.
     */
    public static Device unregistered(String serialNumber) {
        return Device.builder()
                .name("Unregistered sensor " + serialNumber)
                .type("Sensor")
                .status(DeviceStatus.UNREGISTERED)
                .lifecycleStatus(DeviceLifecycleStatus.IN_STOCK)
                .serialNumber(serialNumber)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ─── Consultas ────────────────────────────────────────────────────────────

    public Long organizationId() {
        return client == null ? null : client.getId();
    }

    public DeviceSerial serial() {
        return DeviceSerial.of(serialNumber);
    }

    public DeviceConfig config() {
        return config == null ? DeviceConfig.initial(null) : config;
    }

    public PlugState plugState() {
        if (plugPower == null || plugSource == null || plugChangedAt == null) return null;
        return new PlugState(plugPower, plugCommandId, plugSource, plugChangedAt);
    }

    public boolean isUnregistered() {
        return DeviceStatus.isUnregistered(status);
    }

    public boolean isActive() {
        return DeviceStatus.ACTIVE.equalsIgnoreCase(status);
    }

    /**
     * Si una lectura de la organización {@code messageOrganizationId} puede
     * aceptarse. Fuera de la organización dueña solo se acepta como préstamo:
     * el Edge es de la organización del mensaje y el dispositivo se usa con un
     * tipo distinto del registrado (el mismo sensor sirve de dosímetro o de
     * sensor fijo según el Edge).
     */
    public ReadingAttribution attributionFor(Long messageOrganizationId, Long edgeOrganizationId,
                                             DeviceType reportedType, boolean lendingAllowed) {
        if (messageOrganizationId != null && messageOrganizationId.equals(organizationId())) {
            return ReadingAttribution.OWNER;
        }
        if (lendingAllowed && messageOrganizationId != null && messageOrganizationId.equals(edgeOrganizationId)
                && reportedType != null && reportedType != deviceType) {
            return ReadingAttribution.LENT;
        }
        return ReadingAttribution.REJECTED;
    }

    // ─── Configuración ────────────────────────────────────────────────────────

    public void changeAlertThreshold(AlertThreshold threshold, String actor, Instant now) {
        Objects.requireNonNull(threshold, "threshold");
        requireNotRetired();
        if (deviceType != DeviceType.PERSONAL_DOSIMETER) {
            throw new BadRequestException("Only personal dosimeters have an alert threshold");
        }
        DeviceConfig previous = config();
        if (previous.alertThreshold().equals(threshold) && previous.definedBy() == ConfigDefinedBy.SSO_RESPONSIBLE) {
            return;
        }
        config = previous.withThreshold(threshold, ConfigDefinedBy.SSO_RESPONSIBLE, now);
        audit(AuditEntryType.CONFIG_CHANGED, "alertThresholdUT",
                previous.alertThreshold().microtesla().toPlainString(), threshold.microtesla().toPlainString(),
                actor, now, "configVersion=" + config.version());
        recordConfigVersion(actor);
    }

    /** Muestreo e inactividad, que configura el administrador. Null deja el valor actual. */
    public void changeOperationalConfig(SamplingInterval sampling, InactivityTimeout inactivity,
                                        String actor, Instant now) {
        requireNotRetired();
        DeviceConfig previous = config();
        SamplingInterval newSampling = sampling == null ? previous.samplingInterval() : sampling;
        InactivityTimeout newInactivity = inactivity == null ? previous.inactivityTimeout() : inactivity;
        boolean samplingChanged = !newSampling.equals(previous.samplingInterval());
        boolean inactivityChanged = !newInactivity.equals(previous.inactivityTimeout());
        if (!samplingChanged && !inactivityChanged) return;
        config = previous.withOperational(newSampling, newInactivity, now);
        String version = "configVersion=" + config.version();
        if (samplingChanged) {
            audit(AuditEntryType.CONFIG_CHANGED, "samplingIntervalMs",
                    String.valueOf(previous.samplingInterval().millis()), String.valueOf(newSampling.millis()),
                    actor, now, version);
        }
        if (inactivityChanged) {
            audit(AuditEntryType.CONFIG_CHANGED, "inactivityTimeoutS",
                    String.valueOf(previous.inactivityTimeout().seconds()), String.valueOf(newInactivity.seconds()),
                    actor, now, version);
        }
        recordConfigVersion(actor);
    }

    // ─── Ciclo de vida, vinculación y zona ────────────────────────────────────

    public void changeLifecycle(DeviceLifecycleStatus target, String actor, Instant now) {
        DeviceLifecycleStatus current = lifecycleStatus == null ? DeviceLifecycleStatus.INSTALLED : lifecycleStatus;
        if (!current.canMoveTo(target)) {
            throw new BadRequestException("Device cannot move from " + current + " to " + target);
        }
        lifecycleStatus = target;
        audit(AuditEntryType.LIFECYCLE_CHANGED, "lifecycleStatus", current.name(), target.name(), actor, now, null);
    }

    public void assignToZone(Long zoneId, String actor, Instant now) {
        if (Objects.equals(plantZoneId, zoneId)) return;
        String previous = plantZoneId == null ? null : plantZoneId.toString();
        plantZoneId = zoneId;
        audit(AuditEntryType.ZONE_CHANGED, "plantZoneId", previous, zoneId == null ? null : zoneId.toString(),
                actor, now, null);
    }

    /** Cambia el dueño; es una vinculación y queda auditada. */
    public void linkClient(AppUser newClient, String actor, Instant now) {
        Long previous = organizationId();
        Long next = newClient == null ? null : newClient.getId();
        if (Objects.equals(previous, next)) return;
        client = newClient;
        audit(AuditEntryType.LINKED, "clientId", previous == null ? null : previous.toString(),
                next == null ? null : next.toString(), actor, now, null);
    }

    /** Datos descriptivos que edita el administrador desde el portal. */
    public void updateDetails(String newName, String newType, String newLocation, String newStatus,
                              String newSerial, LocalDate newInstallDate) {
        if (newName != null && !newName.isBlank()) name = newName;
        if (newType != null && !newType.isBlank()) type = newType;
        if (newLocation != null) location = newLocation;
        if (newStatus != null && !newStatus.isBlank()) status = newStatus;
        if (newSerial != null) serialNumber = newSerial.isBlank() ? null : newSerial.trim();
        if (newInstallDate != null) installDate = newInstallDate;
    }

    /**
     * Un técnico reclama el sensor durante una instalación: queda ligado al
     * cliente y la ubicación de la orden, y pasa a activo e instalado.
     */
    public void claimFor(AppUser newClient, String siteLocation, String newName, String newType) {
        Instant now = Instant.now();
        if (newName != null && !newName.isBlank()) this.name = newName;
        else if (this.name == null || this.name.isBlank()) this.name = "Sensor";
        if (newType != null && !newType.isBlank()) this.type = newType;
        else if (this.type == null || this.type.isBlank()) this.type = "Sensor";
        linkClient(newClient, "field-service", now);
        this.location = siteLocation;
        this.status = DeviceStatus.ACTIVE;
        this.installDate = LocalDate.now();
        moveLifecycleQuietly(DeviceLifecycleStatus.INSTALLED, "field-service", now);
    }

    /** Una recogida devuelve el sensor al almacén, sin cliente ni fecha de instalación. */
    public void releaseToPool() {
        Instant now = Instant.now();
        this.status = DeviceStatus.UNREGISTERED;
        linkClient(null, "field-service", now);
        this.installDate = null;
        moveLifecycleQuietly(DeviceLifecycleStatus.IN_STOCK, "field-service", now);
    }

    /** Cambia el estado operativo; si es una recogida, libera además el sensor. */
    public void changeStatus(String newStatus) {
        if (DeviceStatus.isUnregistered(newStatus)) {
            releaseToPool();
        } else {
            this.status = newStatus;
        }
    }

    // ─── Conectividad ─────────────────────────────────────────────────────────

    /**
     * Aplica un cambio de conectividad. Se ignora si es más viejo que el
     * vigente (un retained o un reintento atrasado) o si no cambia nada.
     *
     * @return true si cambió
     */
    public boolean recordConnectivity(ConnectivityStatus newStatus, Instant changedAt, String edgeNodeId,
                                      String reason, Instant now) {
        Objects.requireNonNull(newStatus, "connectivity");
        Objects.requireNonNull(changedAt, "changedAt");
        if (connectivityChangedAt != null && changedAt.isBefore(connectivityChangedAt)) return false;
        if (edgeNodeId != null) connectivityEdgeNodeId = edgeNodeId;
        ConnectivityStatus previous = connectivity == null ? ConnectivityStatus.OFFLINE : connectivity;
        if (newStatus == previous) {
            if (connectivityChangedAt == null) connectivityChangedAt = changedAt;
            return false;
        }
        connectivity = newStatus;
        connectivityChangedAt = changedAt;
        audit(AuditEntryType.CONNECTIVITY_CHANGED, "connectivity", previous.name(), newStatus.name(),
                edgeNodeId == null ? SYSTEM : "edge:" + edgeNodeId, now, "reason=" + reason);
        raise(new DeviceConnectivityChanged(serialNumber, organizationId(), newStatus, previous, changedAt,
                edgeNodeId, reason, now));
        return true;
    }

    /** Datos que informa el Edge junto con el estado; null significa "sin dato". */
    public void reportHardware(String newFirmwareVersion, Integer newBatteryLevel) {
        if (newFirmwareVersion != null && !newFirmwareVersion.isBlank()) firmwareVersion = newFirmwareVersion;
        if (newBatteryLevel != null) batteryLevel = newBatteryLevel;
    }

    /** Llegó una lectura: el dispositivo está vivo. Si figuraba OFFLINE, vuelve a ONLINE. */
    public void markSeen(Instant receivedAt, String edgeNodeId, Instant now) {
        if (receivedAt == null) return;
        if (lastSeenAt == null || receivedAt.isAfter(lastSeenAt)) lastSeenAt = receivedAt;
        if (connectivity != ConnectivityStatus.ONLINE) {
            recordConnectivity(ConnectivityStatus.ONLINE, receivedAt, edgeNodeId, "TELEMETRY", now);
        }
    }

    /**
     * Un dispositivo ONLINE sin noticias durante más que su tiempo de
     * inactividad pasa a OFFLINE.
     *
     * @return true si se detectó la inactividad
     */
    public boolean detectInactivity(Instant now) {
        if (connectivity != ConnectivityStatus.ONLINE) return false;
        Instant base = latest(lastSeenAt, connectivityChangedAt);
        if (base == null || !base.plus(config().inactivityTimeout().duration()).isBefore(now)) return false;
        recordConnectivity(ConnectivityStatus.OFFLINE, now, null, "INACTIVITY", now);
        raise(new DeviceInactivityDetected(serialNumber, organizationId(), base, now));
        return true;
    }

    // ─── Telemetría ───────────────────────────────────────────────────────────

    /**
     * Arranque al que pertenece una lectura nueva. Cada Edge publica en orden
     * las lecturas de un dispositivo, así que una secuencia menor que la última
     * vista solo puede venir de un reinicio. Los duplicados no pasan por aquí.
     */
    public BootId bootFor(long sequence, Supplier<BootId> newBoot) {
        if (bootId == null || (lastSequence != null && sequence < lastSequence)) {
            bootId = newBoot.get().value();
        }
        lastSequence = sequence;
        return new BootId(bootId);
    }

    /**
     * Registra el tipo con el que llegó una lectura. Se enruta según ese tipo
     * aunque difiera del registrado; la discrepancia y el préstamo se auditan
     * una vez, cuando cambia el tipo informado.
     *
     * @return true si el tipo informado no es el registrado
     */
    public boolean noteReportedType(DeviceType reportedType, ReadingAttribution attribution,
                                    Long messageOrganizationId, String edgeNodeId, Instant now) {
        boolean mismatch = reportedType != deviceType;
        if (reportedType != lastReportedType) {
            String actor = "edge:" + edgeNodeId;
            if (mismatch) {
                audit(AuditEntryType.DEVICE_TYPE_MISMATCH, "deviceType", String.valueOf(deviceType),
                        reportedType.name(), actor, now, null);
            }
            if (attribution == ReadingAttribution.LENT) {
                audit(AuditEntryType.DEVICE_LENT, "organizationId", String.valueOf(organizationId()),
                        String.valueOf(messageOrganizationId), actor, now, null);
            }
            lastReportedType = reportedType;
        }
        return mismatch;
    }

    // ─── Enchufe ──────────────────────────────────────────────────────────────

    public PlugCommand requestPlugCommand(PlugAction action, Long userId, String actor, Instant now) {
        Objects.requireNonNull(action, "action");
        requireType(DeviceType.SMART_PLUG);
        requireNotRetired();
        PlugCommand command = PlugCommand.request(id, serialNumber, action, userId, now);
        audit(AuditEntryType.PLUG_COMMAND, "plugAction", plugPower == null ? null : plugPower.name(),
                action.name(), actor, now, "commandId=" + command.getCommandId());
        raise(new PlugCommandRequested(serialNumber, organizationId(), command.getCommandId(), action,
                command.getRequestedAt(), userId));
        return command;
    }

    /**
     * Estado real que informa el Edge. Se ignora si es más viejo que el vigente
     * o idéntico (el Edge lo republica al reconectarse).
     *
     * @return true si cambió
     */
    public boolean reportPlugState(PlugState state, Instant now) {
        Objects.requireNonNull(state, "state");
        requireType(DeviceType.SMART_PLUG);
        if (plugChangedAt != null && state.changedAt().isBefore(plugChangedAt)) return false;
        if (state.equals(plugState())) return false;
        String previous = plugPower == null ? null : plugPower.name();
        plugPower = state.power();
        plugCommandId = state.commandId();
        plugSource = state.source();
        plugChangedAt = state.changedAt();
        audit(AuditEntryType.PLUG_STATE, "plugState", previous, state.power().name(), "edge", now,
                "source=" + state.source() + (state.commandId() == null ? "" : ", commandId=" + state.commandId()));
        raise(new PlugStateReported(serialNumber, organizationId(), state.power(), state.commandId(),
                state.source(), state.changedAt()));
        return true;
    }



    // ─── Pendientes para el repositorio ───────────────────────────────────────

    public List<DeviceAuditEntry> pullAuditEntries() {
        return drain(pendingAudit());
    }

    public List<DeviceConfigVersion> pullConfigVersions() {
        return drain(pendingConfigVersions());
    }

    public List<DeviceDomainEvent> pullEvents() {
        return drain(pendingEvents());
    }

    // ─── Internos ─────────────────────────────────────────────────────────────

    private void recordConfigVersion(String actor) {
        pendingConfigVersions().add(DeviceConfigVersion.of(this, config, actor));
        publishConfigIfDosimeter();
    }

    /** Solo el dosímetro recibe configuración por MQTT. */
    private void publishConfigIfDosimeter() {
        if (deviceType != DeviceType.PERSONAL_DOSIMETER) return;
        DeviceConfig c = config();
        raise(new DeviceConfigChanged(serialNumber, organizationId(), c.version(), c.alertThreshold().microtesla(),
                c.definedBy(), c.samplingInterval().millis(), c.inactivityTimeout().seconds(), c.updatedAt()));
    }

    private void moveLifecycleQuietly(DeviceLifecycleStatus target, String actor, Instant now) {
        DeviceLifecycleStatus current = lifecycleStatus == null ? DeviceLifecycleStatus.INSTALLED : lifecycleStatus;
        if (current.canMoveTo(target)) changeLifecycle(target, actor, now);
    }

    private void requireNotRetired() {
        if (lifecycleStatus == DeviceLifecycleStatus.RETIRED) {
            throw new BadRequestException("Device " + serialNumber + " is retired");
        }
    }

    private void requireType(DeviceType expected) {
        if (deviceType != expected) {
            throw new BadRequestException("Device " + serialNumber + " is not a " + expected);
        }
    }

    private void audit(AuditEntryType type, String field, String oldValue, String newValue,
                       String actor, Instant now, String details) {
        pendingAudit().add(DeviceAuditEntry.of(this, type, field, oldValue, newValue, actor, now, details));
    }

    private void raise(DeviceDomainEvent event) {
        pendingEvents().add(event);
    }

    private List<DeviceAuditEntry> pendingAudit() {
        if (pendingAudit == null) pendingAudit = new ArrayList<>();
        return pendingAudit;
    }

    private List<DeviceConfigVersion> pendingConfigVersions() {
        if (pendingConfigVersions == null) pendingConfigVersions = new ArrayList<>();
        return pendingConfigVersions;
    }

    private List<DeviceDomainEvent> pendingEvents() {
        if (pendingEvents == null) pendingEvents = new ArrayList<>();
        return pendingEvents;
    }

    private static <T> List<T> drain(List<T> pending) {
        List<T> copy = List.copyOf(pending);
        pending.clear();
        return copy;
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static String defaultName(DeviceType type, DeviceSerial serial) {
        return switch (type) {
            case PERSONAL_DOSIMETER -> "Dosímetro " + serial;
            case FIXED_SENSOR -> "Sensor " + serial;
            case SMART_PLUG -> "Enchufe " + serial;
        };
    }

    private static String displayType(DeviceType type) {
        return switch (type) {
            case PERSONAL_DOSIMETER -> "Dosimeter";
            case FIXED_SENSOR -> "Sensor";
            case SMART_PLUG -> "Plug";
        };
    }
}
