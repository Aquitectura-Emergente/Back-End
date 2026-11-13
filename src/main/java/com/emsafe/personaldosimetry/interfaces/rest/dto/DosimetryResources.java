package com.emsafe.personaldosimetry.interfaces.rest.dto;

import com.emsafe.personaldosimetry.domain.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;

public final class DosimetryResources {
    private DosimetryResources() {}

    public record Membership(Long userId, Long organizationId, MembershipRole role, Long grantedBy,
                             Instant grantedAt) {
        public static Membership from(DosimetryMembership m) {
            return new Membership(m.getUserId(), m.getOrganizationId(), m.getRole(), m.getGrantedBy(), m.getGrantedAt());
        }
    }

    public record Limit(Long id, Long organizationId, Long jobPositionId, int version,
                        BigDecimal limitIndex, BigDecimal warningRatio, String methodId, String unit,
                        Instant effectiveFrom, String reference) {
        public static Limit from(OccupationalLimit l) {
            return new Limit(l.getId(), l.getOrganizationId(), l.getJobPositionId(), l.getVersionNumber(),
                    l.getLimitIndex(), l.getWarningRatio(), ExposureIndex.METHOD, ExposureIndex.UNIT,
                    l.getEffectiveFrom(), l.getReference());
        }
    }

    public record Session(String id, Long organizationId, Long workerId, Long deviceId, Long jobPositionId,
                          Long limitVersionId, ExposureSession.State state, String methodId, String unit,
                          BigDecimal accumulatedIndex, BigDecimal limitIndex, BigDecimal percentage,
                          boolean warningRaised, boolean limitExceeded, BigDecimal coveragePercentage,
                          boolean coverageComplete, Instant startedAt, Instant endedAt,
                          Instant reconciliationDeadline, Instant finalizedAt, int outOfRangeReadings) {
        public static Session from(ExposureSession s, Instant now) {
            return new Session(s.getId(), s.getOrganizationId(), s.getWorkerId(), s.getDeviceId(),
                    s.getJobPositionId(), s.getLimitVersionId(), s.getState(), ExposureIndex.METHOD, ExposureIndex.UNIT,
                    s.getAccumulatedIndex(), s.getLimitIndex(), s.percentage(), s.isWarningRaised(),
                    s.isLimitExceeded(), s.coverage(now), s.coverageComplete(), s.getStartedAt(), s.getEndedAt(),
                    s.getReconciliationDeadline(), s.getFinalizedAt(), s.getOutOfRangeReadings());
        }
    }

    public record Reading(String id, String sessionId, Long deviceId, String bootId, long sequence,
                          BigDecimal fieldMicrotesla, String unit, Instant capturedFrom, Instant capturedTo,
                          Instant receivedAt, PersonalReading.Status status) {
        public static Reading from(PersonalReading r) {
            return new Reading(r.getId(), r.getSessionId(), r.getDeviceId(), r.getBootId(), r.getSequence(),
                    r.getFieldMicrotesla(), "uT", r.getCapturedFrom(), r.getCapturedTo(), r.getReceivedAt(), r.getStatus());
        }
    }

    public record Ingestion(Reading reading, boolean duplicate, Session session) {}
    public record Event(String eventId, String eventType, Long organizationId, JsonNode payload, Instant occurredAt) {}
    public record AccessAudit(String id, String principalName, String httpMethod, String resourcePath,
                              int responseStatus, Instant occurredAt) {
        public static AccessAudit from(DosimetryAccessAudit audit) {
            return new AccessAudit(audit.getId(), audit.getPrincipalName(), audit.getHttpMethod(),
                    audit.getResourcePath(), audit.getResponseStatus(), audit.getOccurredAt());
        }
    }
}
