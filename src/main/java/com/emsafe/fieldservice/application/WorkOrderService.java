package com.emsafe.fieldservice.application;

import com.emsafe.alerts.domain.model.Alert;
import com.emsafe.alerts.infrastructure.persistence.AlertRepository;
import com.emsafe.iottelemetry.interfaces.rest.dto.DeviceDto;
import com.emsafe.iottelemetry.domain.model.Device;
import com.emsafe.iottelemetry.infrastructure.persistence.DeviceRepository;
import com.emsafe.fieldservice.domain.model.History;
import com.emsafe.fieldservice.infrastructure.persistence.HistoryRepository;
import com.emsafe.shared.exception.BadRequestException;
import com.emsafe.shared.exception.ResourceNotFoundException;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.fieldservice.interfaces.rest.dto.*;
import com.emsafe.fieldservice.domain.model.*;
import com.emsafe.fieldservice.infrastructure.persistence.WorkOrderRepository;
import com.emsafe.shared.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkOrderService {

    private final WorkOrderRepository workOrderRepository;
    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final AlertRepository alertRepository;
    private final HistoryRepository historyRepository;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");

    // ─── Admin endpoints ───────────────────────────────────────────────────────

    public PageResponse<WorkOrderDto> findAllPaged(String status, String type, String search, String sort, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, buildSort(sort));
        return PageResponse.of(workOrderRepository.searchPaged(
                        WorkOrderStatus.activeFilter(status),
                        WorkOrderType.fromFilter(type),
                        StringUtils.hasText(search) ? search : null,
                        pageable)
                .map(WorkOrderDto::from));
    }

    public List<WorkOrderDto> findAll(String status, String type, String search, String sort) {
        return workOrderRepository.search(
                        WorkOrderStatus.activeFilter(status),
                        WorkOrderType.fromFilter(type),
                        StringUtils.hasText(search) ? search : null,
                        buildSort(sort))
                .stream().map(WorkOrderDto::from).toList();
    }

    /** Sort: 'created' → newest added first (id desc); default → closest scheduled date first. */
    private Sort buildSort(String sort) {
        if ("created".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Direction.DESC, "id");
        }
        return Sort.by(Sort.Direction.ASC, "scheduledDate");
    }

    @Transactional
    public WorkOrderDto create(CreateWorkOrderRequest req) {
        WorkOrder wo = WorkOrder.builder()
                .type(req.type())
                .client(req.client())
                .location(req.location())
                .city(req.city())
                .scheduledDate(req.scheduledDate())
                .scheduledTime(req.scheduledTime())
                .priority(req.priority())
                .contactName(req.contactName())
                .contactRole(req.contactRole())
                .contactPhone(req.contactPhone())
                .contactEmail(req.contactEmail())
                .accessInstructions(req.accessInstructions())
                .expectedSensors(req.expectedSensors())
                .assetId(req.assetId())
                .technicianNotes(req.notes())   // admin notes — visible to the technician
                .status(WorkOrderStatus.PENDING)
                .build();

        if (req.requiredTools() != null) {
            wo.getRequiredTools().addAll(req.requiredTools());
        }

        AppUser clientUser = null;
        if (req.clientId() != null && req.clientId() != 0) {
            clientUser = userRepository.findById(req.clientId()).orElse(null);
            wo.setClientUser(clientUser);
        }

        if (req.technicianId() != null) {
            wo.assignTechnician(requireTechnician(req.technicianId()));
        }

        WorkOrder saved = workOrderRepository.save(wo);

        // Persist auto-generated orderId
        if (saved.getOrderId() == null) {
            saved.assignOrderIdIfMissing();
            workOrderRepository.save(saved);
        }

        // Every created order raises an alarm in the alarm history.
        raiseOrderCreatedAlert(saved, clientUser);

        return WorkOrderDto.from(saved);
    }

    public WorkOrderEditDto findEditById(Long id) {
        WorkOrder wo = workOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkOrder", id));
        return WorkOrderEditDto.from(wo);
    }

    @Transactional
    public WorkOrderDto update(Long id, UpdateWorkOrderRequest req) {
        WorkOrder wo = workOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkOrder", id));

        if (req.type() != null) wo.setType(req.type());
        if (StringUtils.hasText(req.client())) wo.setClient(req.client());
        if (req.location() != null) wo.setLocation(req.location());
        if (req.city() != null) wo.setCity(req.city());
        if (req.scheduledDate() != null) wo.setScheduledDate(req.scheduledDate());
        if (req.scheduledTime() != null) wo.setScheduledTime(req.scheduledTime());
        if (StringUtils.hasText(req.priority())) wo.setPriority(req.priority());
        if (req.notes() != null) wo.setTechnicianNotes(req.notes());

        if (req.clientId() != null) {
            wo.setClientUser(req.clientId() == 0 ? null
                    : userRepository.findById(req.clientId()).orElse(null));
        }

        if (req.technicianId() != null) {
            wo.assignTechnician(req.technicianId() == 0 ? null : requireTechnician(req.technicianId()));
        }

        return WorkOrderDto.from(workOrderRepository.save(wo));
    }

    @Transactional
    public void delete(Long id, String reason) {
        WorkOrder wo = workOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkOrder", id));
        // Deleting an order from the admin = cancelling it (soft-cancel): el
        // agregado conserva la orden para que el Historial siga mostrando su
        // detalle completo, y las listas activas la excluyen.
        wo.cancel(reason);
        WorkOrder saved = workOrderRepository.save(wo);
        recordHistory(saved, "cancelled");
    }

    // ─── Tech endpoints ────────────────────────────────────────────────────────

    public List<WorkOrderDetailDto> findByTechnician(Long technicianId, String status) {
        List<WorkOrder> list;
        WorkOrderStatus statusEnum = WorkOrderStatus.fromFilter(status);

        if (statusEnum != null) {
            list = workOrderRepository.findByTechnicianIdAndStatus(technicianId, statusEnum);
        } else {
            list = workOrderRepository.findByTechnicianId(technicianId);
        }
        return list.stream().map(WorkOrderDetailDto::from).toList();
    }

    public WorkOrderDetailDto findDetailById(Long id) {
        WorkOrder wo = workOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkOrder", id));
        return WorkOrderDetailDto.from(wo, resolveClientDevices(wo));
    }

    /**
     * Ownership check for the tech portal: the order must be assigned to the
     * technician extracted from the JWT (admins bypass this in the controller).
     */
    public void assertOwnedByTechnician(Long orderId, Long technicianId) {
        WorkOrder wo = workOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkOrder", orderId));
        if (technicianId == null
                || wo.getTechnician() == null
                || !technicianId.equals(wo.getTechnician().getId())) {
            throw new AccessDeniedException("This work order is not assigned to you");
        }
    }

    @Transactional
    public WorkOrderDetailDto patch(Long id, PatchWorkOrderRequest req) {
        WorkOrder wo = workOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WorkOrder", id));

        WorkOrderStatus previousStatus = wo.getStatus();

        if (StringUtils.hasText(req.status())) {
            WorkOrderStatus target = WorkOrderStatus.from(req.status());
            if (target == WorkOrderStatus.CANCELLED) {
                wo.cancel(req.cancellationReason());
            } else {
                wo.transitionTo(target);
            }
            // Starting a Maintenance job → the client's devices flagged for
            // maintenance automatically move to "in-maintenance".
            if (wo.startsMaintenance(previousStatus)) {
                deviceRepository
                        .findByClient_IdAndStatusOrderByIdAsc(wo.getClientUser().getId(), "requires-maintenance")
                        .forEach(d -> {
                            d.setStatus("in-maintenance");
                            deviceRepository.save(d);
                        });
            }
        }

        if (StringUtils.hasText(req.technicianNotes())) {
            wo.setTechnicianNotes(req.technicianNotes());
        }

        // ── Work-order sensors (legacy display sensors) ──
        if (req.sensors() != null) {
            req.sensors().forEach(s -> wo.upsertSensor(s.sensorId(), s.location(), s.status()));
        }

        // ── Installation (discovery): claim sensors already reported by the edge ──
        // The technician picks discovered sensors and provides name + type; the
        // backend assigns client + location (from the work order). If the sensor
        // hasn't reported yet, find-or-create by serial as a safety net.
        if (req.claimedDevices() != null) {
            for (PatchWorkOrderRequest.ClaimDeviceDto c : req.claimedDevices()) {
                Device device = null;
                if (c.deviceId() != null) {
                    device = deviceRepository.findById(c.deviceId()).orElse(null);
                }
                if (device == null && StringUtils.hasText(c.serialNumber())) {
                    device = deviceRepository.findBySerialNumber(c.serialNumber().trim());
                }
                if (device == null) {
                    if (!StringUtils.hasText(c.serialNumber())) {
                        throw new BadRequestException("deviceId or serialNumber is required to claim a device");
                    }
                    device = Device.builder()
                            .serialNumber(c.serialNumber().trim())
                            .createdAt(LocalDateTime.now())
                            .build();
                }
                if (StringUtils.hasText(c.name())) device.setName(c.name());
                else if (!StringUtils.hasText(device.getName())) device.setName("Sensor");
                if (StringUtils.hasText(c.type())) device.setType(c.type());
                else if (!StringUtils.hasText(device.getType())) device.setType("Sensor");
                device.setClient(wo.getClientUser());
                device.setLocation(wo.getLocation());
                device.setStatus("active");
                device.setInstallDate(LocalDate.now());
                deviceRepository.save(device);
            }
        }

        // ── Installation (legacy): create devices from a typed serial ──
        if (req.newDevices() != null) {
            for (PatchWorkOrderRequest.NewDeviceDto d : req.newDevices()) {
                if (!StringUtils.hasText(d.serialNumber())) {
                    throw new BadRequestException("Serial number is required for every installed device");
                }
                Device device = Device.builder()
                        .name(StringUtils.hasText(d.name()) ? d.name() : "Sensor")
                        .type(StringUtils.hasText(d.type()) ? d.type() : "Sensor")
                        .location(wo.getLocation())
                        .status("active")
                        .serialNumber(d.serialNumber())
                        .installDate(LocalDate.now())
                        .client(wo.getClientUser())
                        .build();
                deviceRepository.save(device);
            }
        }

        // ── Maintenance / Collection: update device status ──
        // Collection returns a sensor to the pool: status "unregistered" also
        // clears the client and install date so it can be re-discovered elsewhere.
        if (req.deviceUpdates() != null) {
            for (PatchWorkOrderRequest.DeviceStatusDto u : req.deviceUpdates()) {
                if (u.deviceId() == null || !StringUtils.hasText(u.status())) continue;
                deviceRepository.findById(u.deviceId()).ifPresent(device -> {
                    device.setStatus(u.status());
                    if ("unregistered".equals(u.status())) {
                        device.setClient(null);
                        device.setInstallDate(null);
                    }
                    deviceRepository.save(device);
                });
            }
        }

        // ── Maintenance: record actions ──
        if (req.maintenanceActions() != null) {
            req.maintenanceActions().forEach(a -> wo.addMaintenanceAction(
                    a.deviceId(), a.deviceName(), a.action(), a.description()));
        }

        // ── Evidence images ──
        if (req.evidence() != null) {
            req.evidence().forEach(wo::addEvidence);
        }

        if (req.activityLogEntry() != null) {
            wo.logActivity(req.activityLogEntry().event(), req.activityLogEntry().time());
        }

        WorkOrder saved = workOrderRepository.save(wo);

        // On first transition to COMPLETED, write a completed history record.
        if (previousStatus != WorkOrderStatus.COMPLETED
                && saved.getStatus() == WorkOrderStatus.COMPLETED) {
            recordHistory(saved, "completed");
        }

        return WorkOrderDetailDto.from(saved, resolveClientDevices(saved));
    }

    // ─── Dashboard helpers ─────────────────────────────────────────────────────

    public List<WorkOrderDto> findLatestFour() {
        return workOrderRepository.findTop4ByOrderByScheduledDateDesc()
                .stream().map(WorkOrderDto::from).toList();
    }

    public long countByStatus(WorkOrderStatus status) {
        return workOrderRepository.countByStatus(status);
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    /** Devices belonging to the order's client (empty when no client is linked). */
    private List<DeviceDto> resolveClientDevices(WorkOrder wo) {
        if (wo.getClientUser() == null) return List.of();
        return deviceRepository.findByClient_IdOrderByIdAsc(wo.getClientUser().getId())
                .stream().map(DeviceDto::from).toList();
    }

    private void raiseOrderCreatedAlert(WorkOrder wo, AppUser clientUser) {
        String techPart = StringUtils.hasText(wo.getTechnicianName())
                ? " assigned to " + wo.getTechnicianName() : "";
        Alert alert = Alert.builder()
                .type("info")
                .icon("ph-clipboard-text")
                .title("New work order created")
                .description(String.format("%s — %s%s.", wo.getOrderId(), wo.getClient(), techPart))
                .relativeTime("Just now")
                .recipientType(clientUser != null ? "specific" : "all")
                .clientName(wo.getClient())
                .build();
        if (clientUser != null) {
            alert.getRecipientClientIds().add(clientUser.getId());
        }
        alertRepository.save(alert);
    }

    private void recordHistory(WorkOrder wo, String status) {
        LocalDateTime now = LocalDateTime.now();
        History h = History.builder()
                .orderId(wo.getOrderId())
                .completionDate(now.toLocalDate())
                .completionTime(now.format(TIME_FMT))
                .client(wo.getClient())
                .site(wo.getLocation())
                .serviceType(wo.getType().displayName())
                .technician(wo.getTechnicianName())
                .technicianInitials(wo.getTechnicianInitials())
                .status(status)
                .technicianId(wo.getTechnician() != null ? wo.getTechnician().getId() : null)
                .workOrderId(wo.getId())
                .build();
        historyRepository.save(h);
    }

    private AppUser requireTechnician(Long technicianId) {
        return userRepository.findById(technicianId)
                .orElseThrow(() -> new ResourceNotFoundException("Technician", technicianId));
    }
}
