package com.emsafe.personaldosimetry.application;

import com.emsafe.personaldosimetry.domain.model.DosimetryAccessAudit;
import com.emsafe.personaldosimetry.domain.model.DosimetryRuleException;
import com.emsafe.personaldosimetry.infrastructure.persistence.DosimetryAccessAuditRepository;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryResources;
import com.emsafe.shared.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class DosimetryAuditService {
    private final DosimetryAccessAuditRepository audits;
    private final Clock clock;

    // A rejected business transaction must not roll back its access evidence.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenied(String principal, String method, String path, int status) {
        audits.saveAndFlush(new DosimetryAccessAudit(principal, method, path, status,
                clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }

    @Transactional(readOnly = true)
    public PageResponse<DosimetryResources.AccessAudit> list(DosimetryActor actor, int page, int size) {
        actor.requireAdministrator();
        if (page < 0 || size < 1 || size > 200) {
            throw DosimetryRuleException.invalid("Page must be non-negative and size between 1 and 200");
        }
        return PageResponse.of(audits.findAllByOrderByOccurredAtDescIdDesc(PageRequest.of(page, size))
                .map(DosimetryResources.AccessAudit::from));
    }
}
