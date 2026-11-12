package com.emsafe.iam.application;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.iam.interfaces.rest.dto.UserDto;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Consultas del Responsable de SSO. Regla única: solo ve a los operarios (WORKER)
 * de SU organización; nunca a los de otra ni a cuentas de otro rol.
 */
@Service
@RequiredArgsConstructor
public class SsoService {

    private final UserRepository userRepository;

    public List<UserDto> workersOfMyOrganization(String callerEmail) {
        AppUser sso = requireSso(callerEmail);
        return userRepository.findByRoleAndOrganizationId(Role.WORKER, sso.getOrganizationId())
                .stream().map(UserDto::from).toList();
    }

    public UserDto workerOfMyOrganization(String callerEmail, Long workerId) {
        AppUser sso = requireSso(callerEmail);
        AppUser worker = userRepository.findById(workerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", workerId));
        if (!worker.hasRole(Role.WORKER) || !worker.sameOrganizationAs(sso)) {
            throw new AccessDeniedException("Worker belongs to another organization");
        }
        return UserDto.from(worker);
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
