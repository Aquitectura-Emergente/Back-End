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

    @Query("SELECT r FROM RadiationReading r LEFT JOIN FETCH r.device d LEFT JOIN FETCH d.client ORDER BY r.readingDate ASC")
    List<RadiationReading> findAllWithDeviceAndClient();

    @Query("SELECT AVG(r.value) FROM RadiationReading r")
    Double findAverage();

    // ─── Client (mobile) queries ─────────────────────────────────────────────
    @Query("SELECT r FROM RadiationReading r JOIN FETCH r.device d WHERE d.client.id = :clientId ORDER BY r.recordedAt DESC, r.id DESC")
    List<RadiationReading> findByClientIdWithDevice(@Param("clientId") Long clientId);

    @Query("SELECT r FROM RadiationReading r JOIN FETCH r.device d WHERE d.id = :deviceId ORDER BY r.recordedAt DESC, r.id DESC")
    List<RadiationReading> findByDeviceIdWithDevice(@Param("deviceId") Long deviceId);

    // ─── Telemetry (edge ingestion + web/mobile consumption) ──────────────────
    /** Lecturas más recientes (de cualquier sensor), con device + cliente. */
    @Query("SELECT r FROM RadiationReading r LEFT JOIN FETCH r.device d LEFT JOIN FETCH d.client"
            + " ORDER BY r.recordedAt DESC, r.id DESC")
    List<RadiationReading> findRecent(Pageable pageable);

    /** Lecturas de un sensor por su serial (deviceId del edge), más recientes primero. */
    @Query("SELECT r FROM RadiationReading r LEFT JOIN FETCH r.device d LEFT JOIN FETCH d.client"
            + " WHERE r.sensorId = :deviceId ORDER BY r.recordedAt DESC, r.id DESC")
    List<RadiationReading> findBySensorId(@Param("deviceId") String deviceId, Pageable pageable);

    /** Cuántas lecturas ha reportado un sensor (para el panel de descubrimiento). */
    long countBySensorId(String sensorId);
}
