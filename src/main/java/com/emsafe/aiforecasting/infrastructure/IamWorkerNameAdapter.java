package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.domain.ports.WorkerNamePort;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Nombres de operarios desde IAM (solo lectura), como {@link IamForecastingActorDirectory}. Solo
 * devuelve cuentas de la organización pedida ({@code users.organization_id}) con nombre no vacío.
 */
@Component
public class IamWorkerNameAdapter implements WorkerNamePort {

    private final UserRepository users;

    public IamWorkerNameAdapter(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> namesOf(Long organizationId, Set<Long> workerIds) {
        Map<Long, String> names = new HashMap<>();
        if (organizationId == null || workerIds == null || workerIds.isEmpty()) {
            return names;
        }
        for (AppUser u : users.findAllById(workerIds)) {
            if (Objects.equals(u.getOrganizationId(), organizationId) && u.getName() != null
                    && !u.getName().isBlank()) {
                names.put(u.getId(), u.getName().trim());
            }
        }
        return names;
    }
}
