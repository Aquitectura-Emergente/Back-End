package com.emsafe.devicemanagement.application;

import com.emsafe.devicemanagement.application.ports.AccountDirectory;
import com.emsafe.devicemanagement.domain.model.OrganizationCode;
import com.emsafe.devicemanagement.domain.repositories.OrganizationCodeRepository;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.shared.exception.BadRequestException;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Código de texto con el que los Edges de una organización publican su
 * telemetría. Sin él, la ingesta rechaza todas sus lecturas.
 */
@Service
@RequiredArgsConstructor
public class OrganizationCodeService {
    private final OrganizationCodeRepository codes;
    private final AccountDirectory accounts;

    public record OrganizationCodeView(Long organizationId, String code) {
    }

    @Transactional(readOnly = true)
    public OrganizationCodeView codeOf(DeviceActor actor, Long organizationId) {
        actor.requireAdministrator();
        requireOrganization(organizationId);
        return new OrganizationCodeView(organizationId,
                codes.codeOf(organizationId).map(OrganizationCode::value).orElse(null));
    }

    /** Idempotente: repetir el mismo código no cambia nada. Un código no se reasigna ni se cambia. */
    @Transactional
    public OrganizationCodeView assign(DeviceActor actor, Long organizationId, String code) {
        actor.requireAdministrator();
        requireOrganization(organizationId);
        OrganizationCode value = new OrganizationCode(code == null ? null : code.trim());
        Long owner = codes.resolve(value).orElse(null);
        if (owner != null && !owner.equals(organizationId)) {
            throw new BadRequestException("Organization code " + value.value() + " belongs to another organization");
        }
        OrganizationCode current = codes.codeOf(organizationId).orElse(null);
        if (current != null && !current.equals(value)) {
            throw new BadRequestException("Organization " + organizationId + " already uses code " + current.value());
        }
        if (current == null) codes.assign(value, organizationId);
        return new OrganizationCodeView(organizationId, value.value());
    }

    private void requireOrganization(Long organizationId) {
        AppUser account = accounts.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + organizationId));
        if (account.getRole() != Role.CLIENT) {
            throw new BadRequestException("Organization " + organizationId + " is not a client account");
        }
    }
}
