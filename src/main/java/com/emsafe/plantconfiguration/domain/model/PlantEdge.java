package com.emsafe.plantconfiguration.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * El edge que conecta una zona de planta con el servidor.
 *
 * <p>Topología del segmento industrial: los dispositivos IoT de una zona hablan
 * con su edge, y el edge habla con el servidor. <b>Uno por zona</b>, impuesto
 * por un UNIQUE sobre {@code plant_zone_id} en la migración V22.
 *
 * <p><b>Esta tabla la escribe el SSO, no el edge.</b> El edge del segmento
 * industrial todavía no existe como software —el que hay en
 * {@code emsafe-edge-flask} es el del segmento hogar—, así que el registro es
 * una <i>declaración</i> del responsable de SSO desde el portal: "esta zona la
 * sirve el edge con este serial". Cuando se construya el edge industrial,
 * tendrá contra qué autenticarse.
 *
 * <p>Por eso aquí no hay credenciales ni {@code lastSeenAt}: modelar un
 * handshake que nadie ejecuta sería inventar el contrato antes de tener las dos
 * partes. Hoy {@code /api/v1/readings} es anónimo y endurecerlo toca el repo del
 * edge.
 */
@Entity
@Table(name = "plant_edges")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlantEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plant_zone_id", nullable = false)
    private Long plantZoneId;

    @Column(name = "serial_number", nullable = false, length = 100)
    private String serialNumber;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = EdgeStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Conectividad informada por el propio Edge (ONLINE / OFFLINE). Es aparte de
     * {@code status}, que es la activación administrativa que decide el SSO.
     */
    @Column(length = 10)
    private String connectivity;

    @Column(name = "connectivity_changed_at")
    private Instant connectivityChangedAt;

    public static PlantEdge declare(Long plantZoneId, String serialNumber) {
        if (plantZoneId == null) {
            throw new PlantConfigurationRuleException("An edge requires a plant zone");
        }
        return PlantEdge.builder()
                .plantZoneId(plantZoneId)
                .serialNumber(requireSerial(serialNumber))
                .status(EdgeStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public void changeSerial(String newSerial) {
        this.serialNumber = requireSerial(newSerial);
    }

    public void activate() {
        this.status = EdgeStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = EdgeStatus.INACTIVE;
    }

    /** Se ignora un estado más viejo que el vigente. */
    public boolean recordConnectivity(boolean online, Instant at) {
        if (at == null || (connectivityChangedAt != null && at.isBefore(connectivityChangedAt))) return false;
        String next = online ? "ONLINE" : "OFFLINE";
        boolean changed = !next.equals(connectivity);
        connectivity = next;
        connectivityChangedAt = at;
        return changed;
    }

    public boolean isActive() {
        return EdgeStatus.ACTIVE.equals(status);
    }

    private static String requireSerial(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new PlantConfigurationRuleException("Edge serial number is required");
        }
        if (trimmed.length() > 100) {
            throw new PlantConfigurationRuleException(
                    "Edge serial number cannot exceed 100 characters");
        }
        return trimmed;
    }
}
