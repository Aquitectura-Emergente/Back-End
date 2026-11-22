package com.emsafe.radiationmonitoring.infrastructure.persistence;

import com.emsafe.radiationmonitoring.domain.model.RadiationReading;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RadiationReadingRepository extends JpaRepository<RadiationReading, Long> {

    @Query("SELECT r FROM RadiationReading r ORDER BY r.readingDate ASC")
    List<RadiationReading> findAllSorted();

    @Query("SELECT r FROM RadiationReading r LEFT JOIN FETCH r.device d LEFT JOIN FETCH d.client LEFT JOIN FETCH r.client"
            + " ORDER BY r.readingDate ASC")
    List<RadiationReading> findAllWithDeviceAndClient();

    @Query("SELECT AVG(r.value) FROM RadiationReading r")
    Double findAverage();

    // ─── Client (mobile) queries ─────────────────────────────────────────────
    /**
     * Historial de un cliente: las lecturas atribuidas a él. Las que no tienen
     * cliente guardado (anteriores) se atribuyen al dueño del dispositivo.
     */
    @Query("SELECT r FROM RadiationReading r JOIN FETCH r.device d LEFT JOIN r.client c"
            + " WHERE c.id = :clientId OR (c.id IS NULL AND d.client.id = :clientId)"
            + " ORDER BY r.recordedAt DESC, r.id DESC")
    List<RadiationReading> findByClientIdWithDevice(@Param("clientId") Long clientId);

    /**
     * Lecturas de un dispositivo que pertenecen a su dueño: las de un préstamo
     * (atribuidas a otro cliente) no aparecen en el historial del dueño.
     */
    @Query("SELECT r FROM RadiationReading r JOIN FETCH r.device d LEFT JOIN r.client c"
            + " WHERE d.id = :deviceId AND (c.id IS NULL OR c.id = d.client.id)"
            + " ORDER BY r.recordedAt DESC, r.id DESC")
    List<RadiationReading> findByDeviceIdWithDevice(@Param("deviceId") Long deviceId);

    /**
     * Lecturas con valor de los sensores de un conjunto de zonas de planta dentro
     * de una ventana. Reemplaza al {@code findAll()} con el que Occupational
     * Health armaba sus estadísticas por zona (2026-11-22): aquel cargaba la
     * tabla entera en memoria y filtraba en Java.
     *
     * <p>La ventana se aplica sobre {@code recordedAt} y, para las lecturas
     * antiguas que no lo tienen, sobre {@code readingDate}: es el mismo criterio
     * que {@link RadiationReading#timestamp()}.</p>
     */
    @Query("SELECT r FROM RadiationReading r JOIN FETCH r.device d"
            + " WHERE d.plantZoneId IN :zoneIds AND r.value IS NOT NULL"
            + " AND ((r.recordedAt IS NOT NULL AND r.recordedAt BETWEEN :from AND :to)"
            + "   OR (r.recordedAt IS NULL AND r.readingDate BETWEEN :fromDate AND :toDate))")
    List<RadiationReading> findInPlantZonesBetween(@Param("zoneIds") java.util.Collection<Long> zoneIds,
                                                   @Param("from") java.time.LocalDateTime from,
                                                   @Param("to") java.time.LocalDateTime to,
                                                   @Param("fromDate") java.time.LocalDate fromDate,
                                                   @Param("toDate") java.time.LocalDate toDate);

    // ─── Telemetry (edge ingestion + web/mobile consumption) ──────────────────
    /** Lecturas más recientes (de cualquier sensor), con device + cliente. */
    @Query("SELECT r FROM RadiationReading r LEFT JOIN FETCH r.device d LEFT JOIN FETCH d.client LEFT JOIN FETCH r.client"
            + " ORDER BY r.recordedAt DESC, r.id DESC")
    List<RadiationReading> findRecent(Pageable pageable);

    /** Lecturas de un sensor por su serial (deviceId del edge), más recientes primero. */
    @Query("SELECT r FROM RadiationReading r LEFT JOIN FETCH r.device d LEFT JOIN FETCH d.client LEFT JOIN FETCH r.client"
            + " WHERE r.sensorId = :deviceId ORDER BY r.recordedAt DESC, r.id DESC")
    List<RadiationReading> findBySensorId(@Param("deviceId") String deviceId, Pageable pageable);

    /** Cuántas lecturas ha reportado un sensor (para el panel de descubrimiento). */
    long countBySensorId(String sensorId);
}
