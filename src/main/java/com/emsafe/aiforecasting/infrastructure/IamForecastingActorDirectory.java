package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.application.ForecastingActor;
import com.emsafe.aiforecasting.application.ForecastingActorDirectory;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Directorio sobre el IAM de {@code develop} (solo lectura): ADMIN es administrador y una cuenta
 * CLIENT con clientType=company representa a la organización cuyo id es el del propio usuario.
 * Es el único punto a cambiar cuando se integre {@code feature/iam}.
 */
@Component
public class IamForecastingActorDirectory implements ForecastingActorDirectory {

    private static final String COMPANY = "company";

    private final UserRepository users;

    public IamForecastingActorDirectory(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ForecastingActor> findByUsername(String username) {
        return users.findByEmail(username).map(IamForecastingActorDirectory::toActor);
    }

    private static ForecastingActor toActor(AppUser user) {
        boolean admin = user.hasRole(Role.ADMIN);
        boolean company = user.hasRole(Role.CLIENT) && COMPANY.equalsIgnoreCase(user.getClientType());
        return new ForecastingActor(user.getId(), admin, company ? user.getId() : null);
    }
}
