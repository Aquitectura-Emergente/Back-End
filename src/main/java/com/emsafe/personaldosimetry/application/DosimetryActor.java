package com.emsafe.personaldosimetry.application;

import com.emsafe.personaldosimetry.domain.model.MembershipRole;
import org.springframework.security.access.AccessDeniedException;

public record DosimetryActor(Long userId, boolean administrator, Long organizationId, MembershipRole role) {
    public void requireAdministrator() {
        if (!administrator) throw new AccessDeniedException("Administrator access is required");
    }

    public void requireSupervisor(Long organizationId) {
        if (!administrator && (role != MembershipRole.SSO || !organizationId.equals(this.organizationId))) {
            throw new AccessDeniedException("SSO access to this organization is required");
        }
    }

    public void requireRead(Long organizationId, Long workerId) {
        if (administrator) return;
        if (!organizationId.equals(this.organizationId)
                || (role != MembershipRole.SSO && !userId.equals(workerId))) {
            throw new AccessDeniedException("Exposure is outside the authorized scope");
        }
    }
}
