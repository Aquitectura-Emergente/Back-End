package com.emsafe.personaldosimetry.infrastructure;

import com.emsafe.iam.application.events.IndustrialAccountSaved;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.personaldosimetry.application.DosimetryActor;
import com.emsafe.personaldosimetry.application.DosimetryDirectory;
import com.emsafe.personaldosimetry.application.DosimetryDirectory.IndustrialAccount;
import com.emsafe.personaldosimetry.application.PersonalDosimetryService;
import com.emsafe.personaldosimetry.domain.model.DosimetryRuleException;
import com.emsafe.personaldosimetry.domain.model.MembershipRole;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.GrantMembership;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndustrialMembershipProvisionerTest {

    private final PersonalDosimetryService dosimetry = mock(PersonalDosimetryService.class);
    private final DosimetryDirectory directory = mock(DosimetryDirectory.class);
    private final IndustrialMembershipProvisioner provisioner =
            new IndustrialMembershipProvisioner(dosimetry, directory, true);

    @Test
    void aNewWorkerBecomesOperatorSignedByWhoCreatedIt() {
        provisioner.on(new IndustrialAccountSaved(21L, Role.WORKER, 8L, true, 11L));

        ArgumentCaptor<DosimetryActor> actor = ArgumentCaptor.forClass(DosimetryActor.class);
        ArgumentCaptor<GrantMembership> grant = ArgumentCaptor.forClass(GrantMembership.class);
        verify(dosimetry).grantMembership(actor.capture(), grant.capture());
        assertThat(actor.getValue().userId()).isEqualTo(11L);
        assertThat(grant.getValue()).isEqualTo(new GrantMembership(21L, 8L, MembershipRole.OPERATOR));
    }

    @Test
    void anSsoResponsibleGetsTheSsoMembershipSignedByAnAdministratorWhenTheSystemCreatedIt() {
        when(directory.anyAdministratorId()).thenReturn(Optional.of(1L));

        provisioner.on(new IndustrialAccountSaved(11L, Role.SSO_RESPONSIBLE, 8L, true, null));

        ArgumentCaptor<DosimetryActor> actor = ArgumentCaptor.forClass(DosimetryActor.class);
        verify(dosimetry).grantMembership(actor.capture(), any());
        assertThat(actor.getValue().userId()).isEqualTo(1L);
    }

    @Test
    void inactiveAccountsAndOtherRolesAreLeftAlone() {
        provisioner.on(new IndustrialAccountSaved(21L, Role.WORKER, 8L, false, 11L));
        provisioner.on(new IndustrialAccountSaved(22L, Role.CLIENT, 8L, true, 11L));

        verify(dosimetry, never()).grantMembership(any(), any());
    }

    @Test
    void aConflictingMembershipIsNotForcedAndDoesNotBreakTheCaller() {
        when(dosimetry.grantMembership(any(), any()))
                .thenThrow(DosimetryRuleException.conflict("Membership already exists"));

        provisioner.on(new IndustrialAccountSaved(21L, Role.WORKER, 9L, true, 11L));

        verify(dosimetry).grantMembership(any(), any());
    }

    @Test
    void atStartupEveryActiveIndustrialAccountGetsItsMembership() {
        when(directory.anyAdministratorId()).thenReturn(Optional.of(1L));
        when(directory.industrialAccounts()).thenReturn(List.of(
                new IndustrialAccount(11L, 8L, false, true),
                new IndustrialAccount(12L, 8L, true, true),
                new IndustrialAccount(13L, 9L, true, false)));

        provisioner.backfill();

        verify(dosimetry).grantMembership(any(), org.mockito.ArgumentMatchers.eq(new GrantMembership(11L, 8L, MembershipRole.SSO)));
        verify(dosimetry).grantMembership(any(), org.mockito.ArgumentMatchers.eq(new GrantMembership(12L, 8L, MembershipRole.OPERATOR)));
        verify(dosimetry, times(2)).grantMembership(any(), any());
    }

    @Test
    void theStartupPassCanBeTurnedOff() {
        new IndustrialMembershipProvisioner(dosimetry, directory, false).backfill();

        verify(directory, never()).industrialAccounts();
    }
}
