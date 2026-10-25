package com.emsafe.fieldservice.application;

import com.emsafe.alerts.domain.model.Alert;
import com.emsafe.alerts.infrastructure.persistence.AlertRepository;
import com.emsafe.iottelemetry.application.DeviceProvisioningService;
import com.emsafe.iottelemetry.interfaces.rest.dto.DeviceDto;
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
    private final DeviceProvisioningService deviceProvisioning;
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
            // Arrancar un mantenimiento pone en marcha los equipos del cliente
            // que estaban marcados para revisión.
            if (wo.startsMaintenance(previousStatus)) {
                deviceProvisioning.startMaintenanceFor(wo.getClientUser().getId());
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
        if (req.claimedDevices() != null) {
            req.claimedDevices().forEach(c -> deviceProvisioning.claimDiscovered(
                    c.deviceId(), c.serialNumber(), c.name(), c.type(),
                    wo.getClientUser(), wo.getLocation()));
        }

        // ── Installation (legacy): create devices from a typed serial ──
        if (req.newDevices() != null) {
            req.newDevices().forEach(d -> deviceProvisioning.installNew(
                    d.serialNumber(), d.name(), d.type(),
                    wo.getClientUser(), wo.getLocation()));
        }

        // ── Maintenance / Collection: update device status ──
        if (req.deviceUpdates() != null) {
            req.deviceUpdates().forEach(u -> deviceProvisioning.changeStatus(u.deviceId(), u.status()));
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
        return deviceProvisioning.listForClient(wo.getClientUser().getId());
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
