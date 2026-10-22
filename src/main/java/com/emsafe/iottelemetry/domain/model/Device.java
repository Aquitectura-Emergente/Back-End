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
    private String status = "active";

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
}
