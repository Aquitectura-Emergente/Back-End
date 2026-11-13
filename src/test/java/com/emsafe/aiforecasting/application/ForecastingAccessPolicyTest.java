package com.emsafe.aiforecasting.application;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class ForecastingAccessPolicyTest {

    private static final ForecastingActor ADMIN = new ForecastingActor(1L, true, null);
    private static final ForecastingActor COMPANY = new ForecastingActor(10L, false, 10L);
    private static final ForecastingActor TECHNICIAN = new ForecastingActor(20L, false, null);

    private final Map<String, ForecastingActor> users = Map.of(
            "admin@x", ADMIN, "company@x", COMPANY, "tech@x", TECHNICIAN);
    private final ForecastingAccessPolicy policy =
            new ForecastingAccessPolicy(username -> Optional.ofNullable(users.get(username)));

    private static UsernamePasswordAuthenticationToken auth(String username) {
        return new UsernamePasswordAuthenticationToken(username, null, AuthorityUtils.NO_AUTHORITIES);
    }

    @Test
    void resolvesAuthenticatedActor() {
        assertThat(policy.actor(auth("company@x"))).isEqualTo(COMPANY);
    }

    @Test
    void missingAnonymousOrUnknownUserIsDenied() {
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken("k", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        assertThatThrownBy(() -> policy.actor(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.actor(anonymous)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.actor(auth("ghost@x"))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void onlyAdministratorsOperateInboxAndOutbox() {
        assertThatCode(() -> policy.requireAdministrator(ADMIN)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireAdministrator(COMPANY)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requireAdministrator(TECHNICIAN)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void organizationAccessForAdminOrOwningCompany() {
        assertThatCode(() -> policy.requireOrganizationAccess(ADMIN, 99L)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireOrganizationAccess(COMPANY, 10L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireOrganizationAccess(COMPANY, 11L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requireOrganizationAccess(TECHNICIAN, 10L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void organizationScopeForQueries() {
        assertThat(policy.organizationScope(ADMIN, null)).isNull();
        assertThat(policy.organizationScope(ADMIN, 5L)).isEqualTo(5L);
        assertThat(policy.organizationScope(COMPANY, null)).isEqualTo(10L);
        assertThat(policy.organizationScope(COMPANY, 10L)).isEqualTo(10L);
        assertThatThrownBy(() -> policy.organizationScope(COMPANY, 11L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> policy.organizationScope(TECHNICIAN, null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
