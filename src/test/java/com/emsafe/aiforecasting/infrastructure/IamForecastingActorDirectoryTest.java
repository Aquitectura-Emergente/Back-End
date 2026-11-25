package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.application.ForecastingActor;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Traducción de una cuenta de IAM a la organización que representa en AI Forecasting. */
class IamForecastingActorDirectoryTest {

    private static AppUser user(long id, Role role, String clientType, Long organizationId) {
        return AppUser.builder().id(id).email("u" + id + "@x").role(role).status("active")
                .clientType(clientType).organizationId(organizationId).build();
    }

    @Test
    void adminIsAdministratorWithoutOrganization() {
        assertThat(IamForecastingActorDirectory.toActor(user(1, Role.ADMIN, null, null)))
                .isEqualTo(new ForecastingActor(1L, true, null));
    }

    @Test
    void companyClientRepresentsItself() {
        assertThat(IamForecastingActorDirectory.toActor(user(8, Role.CLIENT, "Company", null)))
                .isEqualTo(new ForecastingActor(8L, false, 8L));
    }

    @Test
    void individualClientRepresentsNoOrganization() {
        assertThat(IamForecastingActorDirectory.toActor(user(9, Role.CLIENT, "individual", null)).organizationId())
                .isNull();
    }

    @Test
    void ssoRepresentsItsOrganization() {
        assertThat(IamForecastingActorDirectory.toActor(user(30, Role.SSO_RESPONSIBLE, null, 8L)))
                .isEqualTo(new ForecastingActor(30L, false, 8L));
    }

    @Test
    void ssoWithoutOrganizationRepresentsNone() {
        assertThat(IamForecastingActorDirectory.toActor(user(31, Role.SSO_RESPONSIBLE, null, null)))
                .isEqualTo(new ForecastingActor(31L, false, null));
    }

    @Test
    void workerAndTechnicianRepresentNoOrganization() {
        assertThat(IamForecastingActorDirectory.toActor(user(40, Role.WORKER, null, 8L)).organizationId()).isNull();
        assertThat(IamForecastingActorDirectory.toActor(user(50, Role.TECHNICIAN, null, null)).organizationId())
                .isNull();
    }
}
