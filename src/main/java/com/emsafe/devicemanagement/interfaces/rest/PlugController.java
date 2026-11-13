package com.emsafe.devicemanagement.interfaces.rest;

import com.emsafe.devicemanagement.application.PlugCommandService;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.PlugCommandView;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.PlugStateView;
import com.emsafe.devicemanagement.interfaces.rest.dto.DeviceManagementRequests.RequestPlugCommand;
import com.emsafe.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Órdenes de la app para el enchufe. La respuesta es 202: el estado real llega después por MQTT. */
@RestController
@RequestMapping("/api/v1/device-management/plugs")
@RequiredArgsConstructor
public class PlugController {
    private final PlugCommandService plugs;
    private final DeviceActorResolver actors;

    @PostMapping("/{plugId}/commands")
    public ResponseEntity<ApiResponse<PlugCommandView>> command(@PathVariable String plugId,
                                                               @Valid @RequestBody RequestPlugCommand req,
                                                               Authentication auth) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok("Plug command accepted",
                plugs.request(actors.resolve(auth), plugId, req.action())));
    }

    @GetMapping("/{plugId}/state")
    public ResponseEntity<ApiResponse<PlugStateView>> state(@PathVariable String plugId, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok(plugs.state(actors.resolve(auth), plugId)));
    }

    @GetMapping("/{plugId}/commands/{commandId}")
    public ResponseEntity<ApiResponse<PlugCommandView>> commandStatus(@PathVariable String plugId,
                                                                     @PathVariable String commandId,
                                                                     Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok(plugs.command(actors.resolve(auth), commandId)));
    }
}
