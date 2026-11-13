package com.emsafe.alerts.interfaces.rest;

import com.emsafe.alerts.interfaces.rest.dto.AlertDto;
import com.emsafe.alerts.interfaces.rest.dto.CreateAlarmRequest;
import com.emsafe.alerts.interfaces.rest.dto.EscalateAlarmRequest;
import com.emsafe.alerts.application.AlarmService;
import com.emsafe.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/alarms")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmService alarmService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AlertDto>>> getAll() {
        return ResponseEntity.ok(ApiResponse.ok(alarmService.getAll()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AlertDto>> create(@Valid @RequestBody CreateAlarmRequest req) {
        AlertDto created = alarmService.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Alarm created", created));
    }

    @PatchMapping("/{id}/resolve")
    public ResponseEntity<ApiResponse<AlertDto>> resolve(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(alarmService.resolve(id)));
    }

    /**
     * El operario acusa recibo del aviso (informe §5.6.3, RF25, US60).
     *
     * <p>POST y no PATCH, igual que las transiciones de Occupational Health: no
     * es la edición libre de un campo, es una operación de dominio que sella una
     * evidencia y publica un hecho. Idempotente: repetirla no mueve la fecha ni
     * vuelve a publicar.</p>
     */
    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<ApiResponse<AlertDto>> acknowledge(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("Alarm acknowledged", alarmService.acknowledge(id)));
    }

    /** Escala el aviso al Responsable de SSO. Idempotente, como el acuse. */
    @PostMapping("/{id}/escalate")
    public ResponseEntity<ApiResponse<AlertDto>> escalate(
            @PathVariable Long id, @Valid @RequestBody EscalateAlarmRequest req) {
        return ResponseEntity.ok(ApiResponse.ok("Alarm escalated", alarmService.escalate(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        alarmService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Alarm deleted", null));
    }
}
