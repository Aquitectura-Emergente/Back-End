package com.emsafe.devicemanagement.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Orden del usuario para el enchufe. La crea el {@link Device} y se resuelve
 * cuando el Edge informa un estado con su {@code commandId}.
 */
@Entity
@Table(name = "plug_commands")
@Getter
public class PlugCommand {
    public static final String REQUESTED_BY_USER = "USER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "command_id", nullable = false, length = 36, unique = true)
    private String commandId;
    @Column(name = "plug_device_id")
    private Long plugDeviceId;
    @Column(name = "plug_serial", nullable = false, length = 100)
    private String plugSerial;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private PlugAction action;
    @Column(name = "requested_by", nullable = false, length = 10)
    private String requestedBy;
    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlugCommandStatus status;
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected PlugCommand() {
    }

    static PlugCommand request(Long plugDeviceId, String plugSerial, PlugAction action, Long userId, Instant now) {
        PlugCommand c = new PlugCommand();
        c.commandId = UUID.randomUUID().toString();
        c.plugDeviceId = plugDeviceId;
        c.plugSerial = plugSerial;
        c.action = action;
        c.requestedBy = REQUESTED_BY_USER;
        c.requestedByUserId = userId;
        // El contrato usa segundos; el Edge descarta órdenes con más de 60 s.
        c.requestedAt = now.truncatedTo(ChronoUnit.SECONDS);
        c.status = PlugCommandStatus.PENDING;
        return c;
    }

    /**
     * El Edge respondió con el estado del relé tras esta orden. Si una orden
     * figuraba como no enviada y llega su respuesta, en realidad se entregó.
     */
    public void resolveWith(PlugState state, Instant now) {
        if (status != PlugCommandStatus.PENDING && status != PlugCommandStatus.SEND_FAILED) return;
        status = state.power() == action.expectedPower() ? PlugCommandStatus.CONFIRMED : PlugCommandStatus.NOT_APPLIED;
        resolvedAt = now;
    }

    public void markSendFailed(Instant now) {
        if (status != PlugCommandStatus.PENDING) return;
        status = PlugCommandStatus.SEND_FAILED;
        resolvedAt = now;
    }
}
