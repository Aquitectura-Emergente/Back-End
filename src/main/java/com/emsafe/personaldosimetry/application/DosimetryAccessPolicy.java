package com.emsafe.personaldosimetry.application;

import com.emsafe.personaldosimetry.infrastructure.persistence.DosimetryMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DosimetryAccessPolicy {
    private final DosimetryDirectory directory;
    private final DosimetryMembershipRepository memberships;

    public DosimetryActor actor(String email) {
        var user = directory.userByEmail(email);
        if (!user.active()) throw new AccessDeniedException("Account is inactive");
        if (user.administrator()) return new DosimetryActor(user.id(), true, null, null);
        var membership = memberships.findById(user.id())
                .orElseThrow(() -> new AccessDeniedException("No dosimetry membership has been granted"));
        return new DosimetryActor(user.id(), false, membership.getOrganizationId(), membership.getRole());
    }
}
