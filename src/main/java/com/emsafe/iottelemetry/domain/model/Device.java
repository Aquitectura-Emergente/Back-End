package com.emsafe.iottelemetry.domain.model;

import com.emsafe.iam.domain.model.AppUser;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(length = 200)
    private String location;

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

    /**
     * Estado DESEADO del relé (ON | OFF), ordenado por el usuario desde la app.
     * El edge lo consulta (GET /api/v1/devices/{serial}/plug) para accionar el
     * dispositivo; el estado real reportado viaja en cada lectura (reading.plug).
     */
    @Column(name = "desired_plug", length = 8)
    private String desiredPlug;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private AppUser client;

    // ─── Comportamiento del agregado ──────────────────────────────────────────

    /**
     * Sensor que el edge reporta con un serial que no está registrado. Se crea
     * igualmente para no perder la lectura; queda sin cliente hasta que un
     * técnico lo reclame durante una instalación.
     */
    public static Device unregistered(String serialNumber) {
        return Device.builder()
                .name("Unregistered sensor " + serialNumber)
                .type("Sensor")
                .status(DeviceStatus.UNREGISTERED)
                .serialNumber(serialNumber)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public boolean isUnregistered() {
        return DeviceStatus.isUnregistered(status);
    }

    /**
     * Un técnico reclama el sensor durante una instalación: queda ligado al
     * cliente y la ubicación de la orden, y pasa a activo. El nombre y el tipo
     * solo se pisan si vienen informados; si el dispositivo aún no tiene,
     * se le da uno por defecto.
     */
    public void claimFor(AppUser newClient, String siteLocation, String newName, String newType) {
        if (newName != null && !newName.isBlank()) this.name = newName;
        else if (this.name == null || this.name.isBlank()) this.name = "Sensor";

        if (newType != null && !newType.isBlank()) this.type = newType;
        else if (this.type == null || this.type.isBlank()) this.type = "Sensor";

        this.client = newClient;
        this.location = siteLocation;
        this.status = DeviceStatus.ACTIVE;
        this.installDate = LocalDate.now();
    }

    /**
     * Una recogida devuelve el sensor al pool: además del estado, se limpian
     * cliente y fecha de instalación para que pueda volver a descubrirse en
     * otra sede.
     */
    public void releaseToPool() {
        this.status = DeviceStatus.UNREGISTERED;
        this.client = null;
        this.installDate = null;
    }

    /** Cambia el estado operativo; si es una recogida, libera además el sensor. */
    public void changeStatus(String newStatus) {
        if (DeviceStatus.isUnregistered(newStatus)) {
            releaseToPool();
        } else {
            this.status = newStatus;
        }
    }
}
