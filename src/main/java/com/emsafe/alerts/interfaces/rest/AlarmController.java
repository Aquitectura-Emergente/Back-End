package com.emsafe.alerts.interfaces.rest;

import com.emsafe.alerts.interfaces.rest.dto.AlertDto;
import com.emsafe.alerts.interfaces.rest.dto.CreateAlarmRequest;
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

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        alarmService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Alarm deleted", null));
    }
}
