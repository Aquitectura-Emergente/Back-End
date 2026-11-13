package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.model.ConnectivityStatus;
import com.emsafe.devicemanagement.domain.model.Device;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeviceJpaRepository extends JpaRepository<Device, Long> {
    Optional<Device> findBySerialNumber(String serialNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Device d WHERE d.serialNumber = :serial")
    Optional<Device> lockBySerialNumber(@Param("serial") String serialNumber);

    @Query("SELECT d FROM Device d LEFT JOIN FETCH d.client ORDER BY d.id ASC")
    List<Device> findAllWithClient();

    @Query("SELECT d FROM Device d LEFT JOIN FETCH d.client WHERE d.id = :id")
    Optional<Device> findByIdWithClient(@Param("id") Long id);

    List<Device> findByClient_IdOrderByIdAsc(Long clientId);

    Optional<Device> findByIdAndClient_Id(Long id, Long clientId);

    List<Device> findByClient_IdAndStatusOrderByIdAsc(Long clientId, String status);

    long countByStatus(String status);

    List<Device> findByStatusOrderByIdAsc(String status);

    List<Device> findByPlantZoneIdOrderByIdAsc(Long plantZoneId);

    long countByPlantZoneId(Long plantZoneId);

    List<Device> findByConnectivity(ConnectivityStatus connectivity);
}
