package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Nombres de IAM: solo de la organización pedida y con nombre no vacío. */
class IamWorkerNameAdapterTest {

    private final UserRepository users = mock(UserRepository.class);
    private final IamWorkerNameAdapter adapter = new IamWorkerNameAdapter(users);

    private static AppUser user(long id, String name, Long organizationId) {
        return AppUser.builder().id(id).email("u" + id + "@x").name(name).role(Role.WORKER).status("active")
                .organizationId(organizationId).build();
    }

    @Test
    void returnsOnlyNamedWorkersOfTheOrganization() {
        when(users.findAllById(any())).thenReturn(List.of(
                user(12, " Carlos Rojas ", 8L),
                user(13, "Otra Empresa", 9L),
                user(14, "  ", 8L),
                user(15, null, 8L)));

        assertThat(adapter.namesOf(8L, Set.of(12L, 13L, 14L, 15L))).isEqualTo(Map.of(12L, "Carlos Rojas"));
    }

    @Test
    void noIdsOrNoOrganizationDoesNotQueryIam() {
        assertThat(adapter.namesOf(8L, Set.of())).isEmpty();
        assertThat(adapter.namesOf(null, Set.of(12L))).isEmpty();
        verifyNoInteractions(users);
    }
}
