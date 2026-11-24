package com.emsafe.devicemanagement.application;

import com.emsafe.devicemanagement.application.ports.AccountDirectory;
import com.emsafe.devicemanagement.domain.model.EdgeNode;
import com.emsafe.devicemanagement.domain.model.EdgeSegment;
import com.emsafe.devicemanagement.domain.model.OrganizationCode;
import com.emsafe.devicemanagement.domain.repositories.EdgeNodeRepository;
import com.emsafe.devicemanagement.domain.repositories.OrganizationCodeRepository;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.shared.exception.BadRequestException;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

/**
 * Alta de lo que la ingesta necesita para aceptar la telemetría de una
 * organización: el código de texto con el que publican sus Edges y los propios
 * nodos Edge. La "organización" es el id de la cuenta CLIENT dueña de los
 * equipos. Solo el administrador.
 */
@Service
@RequiredArgsConstructor
public class OrganizationCodeService {
    private final OrganizationCodeRepository codes;
    private final EdgeNodeRepository edges;
    private final AccountDirectory accounts;
    private final Clock clock;

    public record OrganizationCodeView(String code, Long organizationId, String organizationEmail) {
    }

    @Transactional(readOnly = true)
    public List<OrganizationCodeView> list(DeviceActor actor) {
        actor.requireAdministrator();
        return codes.findAll().entrySet().stream()
                .map(e -> new OrganizationCodeView(e.getKey(), e.getValue(),
                        accounts.findById(e.getValue()).map(AppUser::getEmail).orElse(null)))
                .toList();
    }

    /** Un código apunta a una sola organización y cada organización tiene un solo código. */
    @Transactional
    public OrganizationCodeView assign(DeviceActor actor, String code, Long organizationId) {
        actor.requireAdministrator();
        AppUser organization = requireOrganization(organizationId);
        OrganizationCode value = new OrganizationCode(code == null ? null : code.trim());
        if (codes.resolve(value).isPresent()) {
            throw new BadRequestException("Organization code " + value.value() + " is already assigned");
        }
        OrganizationCode current = codes.codeOf(organizationId).orElse(null);
        if (current != null) {
            throw new BadRequestException("Organization " + organizationId + " already uses code " + current.value());
        }
        codes.assign(value, organizationId);
        return new OrganizationCodeView(value.value(), organizationId, organization.getEmail());
    }

    @Transactional
    public EdgeNode registerEdge(DeviceActor actor, String edgeNodeId, Long organizationId, String segment) {
        actor.requireAdministrator();
        requireOrganization(organizationId);
        EdgeNode edge = EdgeNode.register(edgeNodeId, organizationId, parseSegment(segment), clock.instant());
        if (edges.findByEdgeNodeId(edge.getEdgeNodeId()).isPresent()) {
            throw new BadRequestException("Edge node " + edge.getEdgeNodeId() + " is already registered");
        }
        return edges.save(edge);
    }

    private AppUser requireOrganization(Long organizationId) {
        if (organizationId == null) throw new BadRequestException("organizationId is required");
        AppUser account = accounts.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + organizationId));
        if (account.getRole() != Role.CLIENT) {
            throw new BadRequestException("Organization " + organizationId + " is not a CLIENT account");
        }
        return account;
    }

    private static EdgeSegment parseSegment(String segment) {
        try {
            return EdgeSegment.valueOf(segment == null ? "" : segment.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("segment must be PLANT or HOME");
        }
    }
}
