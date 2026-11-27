package com.emsafe.iam.application;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.iam.interfaces.rest.dto.CreateUserRequest;
import com.emsafe.iam.interfaces.rest.dto.CreateWorkerRequest;
import com.emsafe.iam.interfaces.rest.dto.UpdateUserRequest;
import com.emsafe.iam.interfaces.rest.dto.UpdateWorkerRequest;
import com.emsafe.iam.interfaces.rest.dto.UserDto;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Operarios del Responsable de SSO. Regla única: solo ve y gestiona a los
 * operarios (WORKER) de SU organización; nunca a los de otra ni a cuentas de
 * otro rol.
 */
@Service
@RequiredArgsConstructor
public class SsoService {

    private final UserRepository userRepository;
    private final UserService users;

    public List<UserDto> workersOfMyOrganization(String callerEmail) {
        AppUser sso = requireSso(callerEmail);
        return userRepository.findByRoleAndOrganizationId(Role.WORKER, sso.getOrganizationId())
                .stream().map(UserDto::from).toList();
    }

    public UserDto workerOfMyOrganization(String callerEmail, Long workerId) {
        return UserDto.from(requireOwnWorker(requireSso(callerEmail), workerId));
    }

    /** Alta de un operario en la organización del responsable. */
    @Transactional
    public UserDto createWorker(String callerEmail, CreateWorkerRequest req) {
        AppUser sso = requireSso(callerEmail);
        return users.create(new CreateUserRequest(req.name().trim(), null, req.email().trim(), req.password(),
                Role.WORKER, blankToNull(req.phone()), null, null, blankToNull(req.department()), null,
                blankToNull(req.notes()), sso.getOrganizationId()), sso.getId());
    }

    /** Datos, estado (activo / inactivo) o clave de un operario suyo. */
    @Transactional
    public UserDto updateWorker(String callerEmail, Long workerId, UpdateWorkerRequest req) {
        AppUser sso = requireSso(callerEmail);
        requireOwnWorker(sso, workerId);
        return users.update(workerId, new UpdateUserRequest(req.name(), null, req.email(), req.phone(), null,
                req.status(), null, req.department(), null, req.password(), req.notes(),
                null, null, null, null, null, null, null, null, null, null), sso.getId());
    }

    private AppUser requireOwnWorker(AppUser sso, Long workerId) {
        AppUser worker = userRepository.findById(workerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", workerId));
        if (!worker.hasRole(Role.WORKER) || !worker.sameOrganizationAs(sso)) {
            throw new AccessDeniedException("Worker belongs to another organization");
        }
        return worker;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AppUser requireSso(String email) {
        AppUser sso = userRepository.findByEmail(email)
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
        if (!sso.hasRole(Role.SSO_RESPONSIBLE) || sso.getOrganizationId() == null) {
            throw new AccessDeniedException("Access denied");
        }
        return sso;
    }
}
