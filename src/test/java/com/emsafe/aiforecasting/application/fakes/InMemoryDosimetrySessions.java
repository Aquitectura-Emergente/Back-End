package com.emsafe.aiforecasting.application.fakes;

import com.emsafe.aiforecasting.domain.model.ClosedExposureSession;
import com.emsafe.aiforecasting.domain.ports.ClosedSessionHistoryPort;
import com.emsafe.aiforecasting.domain.ports.OpenSessionPort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lo que Personal Dosimetry sabe de las jornadas: cerradas (con endedAt) y la abierta de cada operario. */
public class InMemoryDosimetrySessions implements ClosedSessionHistoryPort, OpenSessionPort {

    private final List<ClosedExposureSession> closed = new ArrayList<>();
    private final Map<Long, String> openByWorker = new HashMap<>();

    @Override
    public List<ClosedExposureSession> closedSessions(Long organizationId, Instant from, Instant to) {
        return closed.stream().filter(s -> !s.endedAt().isBefore(from) && s.endedAt().isBefore(to)).toList();
    }

    @Override
    public Optional<String> openSessionId(Long organizationId, Long workerId) {
        return Optional.ofNullable(openByWorker.get(workerId));
    }

    public void close(String sessionId, long workerId, Instant endedAt) {
        openByWorker.remove(workerId, sessionId);
        closed.add(new ClosedExposureSession(sessionId, workerId, 1L, BigDecimal.ONE, BigDecimal.TEN,
                false, false, endedAt.minusSeconds(3600), endedAt, true));
    }

    public void open(String sessionId, long workerId) {
        openByWorker.put(workerId, sessionId);
    }
}
