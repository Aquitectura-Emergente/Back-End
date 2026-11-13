package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.model.ConnectivityStatus;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import com.emsafe.devicemanagement.domain.repositories.DomainEventOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Guarda el dispositivo junto con su auditoría, su historial de configuración y sus eventos. */
@Component
@RequiredArgsConstructor
public class JpaDeviceRepository implements DeviceRepository {
    private final DeviceJpaRepository devices;
    private final DeviceAuditEntryJpaRepository audit;
    private final DeviceConfigVersionJpaRepository configs;
    private final DomainEventOutbox outbox;

    @Override
    public Device save(Device device) {
        Device saved = devices.save(device);
        configs.saveAll(device.pullConfigVersions());
        audit.saveAll(device.pullAuditEntries());
        device.pullEvents().forEach(outbox::append);
        return saved;
    }

    @Override
    public List<Device> saveAll(List<Device> list) {
        return list.stream().map(this::save).toList();
    }

    @Override
    public Optional<Device> findById(Long id) {
        return devices.findById(id);
    }

    @Override
    public Optional<Device> findByIdWithClient(Long id) {
        return devices.findByIdWithClient(id);
    }

    @Override
    public Optional<Device> findBySerial(String serialNumber) {
        return devices.findBySerialNumber(serialNumber);
    }

    @Override
    public Optional<Device> lockBySerial(String serialNumber) {
        return devices.lockBySerialNumber(serialNumber);
    }

    @Override
    public List<Device> findAll() {
        return devices.findAll();
    }

    @Override
    public List<Device> findAllWithClient() {
        return devices.findAllWithClient();
    }

    @Override
    public List<Device> findByClient(Long clientId) {
        return devices.findByClient_IdOrderByIdAsc(clientId);
    }

    @Override
    public Optional<Device> findByIdAndClient(Long id, Long clientId) {
        return devices.findByIdAndClient_Id(id, clientId);
    }

    @Override
    public List<Device> findByClientAndStatus(Long clientId, String status) {
        return devices.findByClient_IdAndStatusOrderByIdAsc(clientId, status);
    }

    @Override
    public List<Device> findByStatus(String status) {
        return devices.findByStatusOrderByIdAsc(status);
    }

    @Override
    public List<Device> findByZone(Long plantZoneId) {
        return devices.findByPlantZoneIdOrderByIdAsc(plantZoneId);
    }

    @Override
    public long countByZone(Long plantZoneId) {
        return devices.countByPlantZoneId(plantZoneId);
    }

    @Override
    public List<Device> findOnline() {
        return devices.findByConnectivity(ConnectivityStatus.ONLINE);
    }

    @Override
    public boolean existsById(Long id) {
        return devices.existsById(id);
    }

    @Override
    public void deleteById(Long id) {
        devices.deleteById(id);
    }
}
