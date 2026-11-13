package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.model.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Device findBySerialNumber(String serialNumber);

    @Query("SELECT d FROM Device d LEFT JOIN FETCH d.client ORDER BY d.id ASC")
    List<Device> findAllWithClient();

    @Query("SELECT d FROM Device d LEFT JOIN FETCH d.client WHERE d.id = :id")
    java.util.Optional<Device> findByIdWithClient(@org.springframework.data.repository.query.Param("id") Long id);

    // ─── Client (mobile) queries ─────────────────────────────────────────────
    List<Device> findByClient_IdOrderByIdAsc(Long clientId);

    java.util.Optional<Device> findByIdAndClient_Id(Long id, Long clientId);

    // ─── Work-order flow queries ─────────────────────────────────────────────
    List<Device> findByClient_IdAndStatusOrderByIdAsc(Long clientId, String status);

    long countByStatus(String status);

    // ─── Discovery (installation claim) ──────────────────────────────────────
    /** Devices not yet assigned to a client (auto-created by the edge ingest). */
    List<Device> findByStatusOrderByIdAsc(String status);

    // ─── Plant zones (segmento industrial, V22) ──────────────────────────────
    /** Sensores que vigilan una zona de planta. Los consulta `plantconfiguration`. */
    List<Device> findByPlantZoneIdOrderByIdAsc(Long plantZoneId);

    long countByPlantZoneId(Long plantZoneId);
}
