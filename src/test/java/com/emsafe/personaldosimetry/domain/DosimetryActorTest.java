package com.emsafe.personaldosimetry.domain;

import com.emsafe.personaldosimetry.application.DosimetryActor;
import com.emsafe.personaldosimetry.domain.model.MembershipRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;

class DosimetryActorTest {
    @Test
    void operatorReadsOnlyOwnExposureWithinTheCompany() {
        var actor = new DosimetryActor(2L, false, 1L, MembershipRole.OPERATOR);
        assertThatCode(() -> actor.requireRead(1L, 2L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> actor.requireRead(1L, 3L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> actor.requireRead(9L, 2L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> actor.requireSupervisor(1L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void supervisorCannotCrossCompanyBoundaryOrGrantAccess() {
        var actor = new DosimetryActor(3L, false, 1L, MembershipRole.SSO);
        assertThatCode(() -> actor.requireSupervisor(1L)).doesNotThrowAnyException();
        assertThatCode(() -> actor.requireRead(1L, 2L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> actor.requireSupervisor(9L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(actor::requireAdministrator).isInstanceOf(AccessDeniedException.class);
    }
}
