package com.emsafe.devicemanagement.application;

import com.emsafe.devicemanagement.application.views.DeviceManagementViews.PlugCommandView;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.PlugStateView;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.PlugAction;
import com.emsafe.devicemanagement.domain.model.PlugCommand;
import com.emsafe.devicemanagement.domain.model.PlugState;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import com.emsafe.devicemanagement.domain.repositories.PlugCommandRepository;
import com.emsafe.shared.exception.BadRequestException;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;

/**
 * Órdenes del usuario para el enchufe y su estado real. La orden se publica en
 * {@code iot/{plugId}/command} después del commit; el estado llega por
 * {@code iot/{plugId}/state} y resuelve la orden con el mismo {@code commandId}.
 */
@Service
@RequiredArgsConstructor
public class PlugCommandService {
    private static final Logger log = LoggerFactory.getLogger(PlugCommandService.class);

    private final DeviceRepository devices;
    private final PlugCommandRepository commands;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** Se publica tras el commit para que el despachador envíe la orden al broker. */
    public record PlugCommandAccepted(String commandId) {
    }

    @Transactional
    public PlugCommandView request(DeviceActor actor, String plugId, String action) {
        Device plug = requirePlug(plugId);
        actor.requireOwner(plug.organizationId());
        return issue(plug, parseAction(action), actor);
    }

    /** Orden desde el portal del cliente, que identifica el enchufe por su id interno. */
    @Transactional
    public PlugCommandView requestForClient(Long clientId, Long deviceId, String action) {
        Device plug = devices.findByIdAndClient(deviceId, clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));
        return issue(plug, parseAction(action), new DeviceActor(clientId, "client:" + clientId, false, false, true, null));
    }

    @Transactional(readOnly = true)
    public PlugStateView state(DeviceActor actor, String plugId) {
        Device plug = requirePlug(plugId);
        actor.requireRead(plug.organizationId());
        return PlugStateView.from(plug, commands.findLatestForPlug(plug.getSerialNumber()).orElse(null));
    }

    @Transactional(readOnly = true)
    public PlugCommandView command(DeviceActor actor, String commandId) {
        PlugCommand command = commands.findByCommandId(commandId)
                .orElseThrow(() -> new ResourceNotFoundException("Plug command not found: " + commandId));
        actor.requireRead(requirePlug(command.getPlugSerial()).organizationId());
        return PlugCommandView.from(command);
    }

    /**
     * Estado real informado por el Edge hogar. Si trae {@code commandId}, resuelve
     * esa orden. Un enchufe no registrado se ignora con un aviso.
     */
    @Transactional
    public void recordState(String plugId, PlugState state) {
        Device plug = devices.lockBySerial(plugId).orElse(null);
        if (plug == null) {
            log.warn("Plug state for unknown plug {} ignored", plugId);
            return;
        }
        plug.reportPlugState(state, clock.instant());
        devices.save(plug);
        if (state.commandId() != null) {
            commands.findByCommandId(state.commandId()).ifPresent(c -> {
                c.resolveWith(state, clock.instant());
                commands.save(c);
            });
        }
    }

    private PlugCommandView issue(Device plug, PlugAction action, DeviceActor actor) {
        PlugCommand command = plug.requestPlugCommand(action, actor.userId(), actor.label(), clock.instant());
        devices.save(plug);
        commands.save(command);
        events.publishEvent(new PlugCommandAccepted(command.getCommandId()));
        return PlugCommandView.from(command);
    }

    private Device requirePlug(String plugId) {
        if (plugId == null || plugId.isBlank()) throw new BadRequestException("plugId is required");
        return devices.findBySerial(plugId.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Plug not found: " + plugId));
    }

    private static PlugAction parseAction(String action) {
        try {
            return PlugAction.valueOf(action == null ? "" : action.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("action must be ON or OFF");
        }
    }
}
