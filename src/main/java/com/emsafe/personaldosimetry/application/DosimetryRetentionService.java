package com.emsafe.personaldosimetry.application;

import com.emsafe.personaldosimetry.infrastructure.DosimetryRetentionProperties;
import com.emsafe.personaldosimetry.infrastructure.persistence.DosimetryAccessAuditRepository;
import com.emsafe.personaldosimetry.infrastructure.persistence.DosimetryEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class DosimetryRetentionService {
    private final DosimetryAccessAuditRepository audits;
    private final DosimetryEventRepository events;
    private final DosimetryRetentionProperties policy;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${emsafe.personal-dosimetry.retention.cleanup-interval:PT1H}",
               initialDelayString = "${emsafe.personal-dosimetry.retention.cleanup-interval:PT1H}")
    @Transactional
    public void purgeExpired() {
        if (policy.getAuditDays() == 0 && policy.getPublishedEventDays() == 0) return;
        var now = clock.instant();
        var batch = PageRequest.of(0, policy.getBatchSize());
        if (policy.getAuditDays() > 0) {
            audits.deleteAllInBatch(audits.findByOccurredAtBeforeOrderByOccurredAtAscIdAsc(
                    now.minus(policy.getAuditDays(), ChronoUnit.DAYS), batch));
        }
        if (policy.getPublishedEventDays() > 0) {
            // Retention starts at acknowledgment, never at event creation. Pending events survive.
            events.deleteAllInBatch(events.findByPublishedAtBeforeOrderByPublishedAtAscIdAsc(
                    now.minus(policy.getPublishedEventDays(), ChronoUnit.DAYS), batch));
        }
    }
}
