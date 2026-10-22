package com.emsafe.radiationmonitoring.domain.model;

import com.emsafe.iottelemetry.domain.model.Device;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "radiation_readings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RadiationReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate readingDate;

    @Column(nullable = false)
    private Double value;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column(length = 200)
    private String location;

    @Column(length = 50)
    private String sensorId;

    /** Nivel reportado por el edge (SAFE | CAUTION | DANGER). */
    @Column(length = 30)
    private String level;

    /** Mensaje descriptivo que acompaña la lectura. */
    @Column(length = 255)
    private String message;

    /** Estado del relé reportado por el dispositivo en esta lectura (ON | OFF). */
    @Column(length = 8)
    private String plug;

    /** Timestamp real de la medición (el edge ingesta varias por día). */
    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private Device device;

    // ─── Comportamiento del agregado ──────────────────────────────────────────

    /**
     * Nivel de la lectura, respetando siempre el que calculó el edge.
     *
     * Importar aquí el servicio de dominio es deliberado: que el agregado
     * responda {@code r.level()} evita que cada consumidor tenga que acordarse
     * de llamar al clasificador, que es justo como se cuelan las
     * reclasificaciones accidentales en el backend.
     */
    public String level() {
        return com.emsafe.radiationmonitoring.domain.services.RadiationLevel.of(this);
    }

    /** Una lectura es alerta cuando su nivel deja de ser "safe". */
    public boolean isAlert() {
        return !"safe".equals(level());
    }

    /**
     * Momento al que pertenece la medición. Se prefiere recordedAt, que es
     * preciso: con el edge enviando varias lecturas al día, readingDate por sí
     * solo no distingue cuál es la última.
     */
    public LocalDateTime timestamp() {
        if (recordedAt != null) return recordedAt;
        return readingDate != null ? readingDate.atStartOfDay() : LocalDateTime.MIN;
    }

    /** La lectura más reciente de un conjunto, o null si no hay ninguna fechada. */
    public static RadiationReading latestOf(java.util.List<RadiationReading> readings) {
        return readings.stream()
                .filter(r -> r.getRecordedAt() != null || r.getReadingDate() != null)
                .max(java.util.Comparator.comparing(RadiationReading::timestamp))
                .orElse(null);
    }
}
