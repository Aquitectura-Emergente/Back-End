package com.emsafe.personaldosimetry.infrastructure;

import com.emsafe.iam.application.events.IndustrialAccountSaved;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.personaldosimetry.application.DosimetryActor;
import com.emsafe.personaldosimetry.application.DosimetryDirectory;
import com.emsafe.personaldosimetry.application.PersonalDosimetryService;
import com.emsafe.personaldosimetry.domain.model.MembershipRole;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.GrantMembership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Da a cada operario y responsable de SSO su membership de dosimetría en su
 * organización: OPERATOR al WORKER y SSO al SSO_RESPONSIBLE.
 *
 * <p>Sin ella el SSO no puede configurar límites ni abrir jornadas, y al
 * operario no se le puede asignar una. Se concede al crear o editar la cuenta
 * (evento de IAM, después del commit) y, al arrancar, a las cuentas que ya
 * existían sin membership.</p>
 *
 * <p>Nunca reasigna: si la cuenta ya tiene una membership de otra organización
 * o rol, la deja como está y lo registra en el log.</p>
 */
@Component
public class IndustrialMembershipProvisioner {

    private static final Logger log = LoggerFactory.getLogger(IndustrialMembershipProvisioner.class);

    private final PersonalDosimetryService dosimetry;
    private final DosimetryDirectory directory;
    private final boolean backfillEnabled;

    public IndustrialMembershipProvisioner(PersonalDosimetryService dosimetry, DosimetryDirectory directory,
                                           @Value("${emsafe.personal-dosimetry.membership-backfill-enabled:true}")
                                           boolean backfillEnabled) {
        this.dosimetry = dosimetry;
        this.directory = directory;
        this.backfillEnabled = backfillEnabled;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(IndustrialAccountSaved event) {
        if (event == null || !event.active() || event.organizationId() == null) return;
        MembershipRole role = roleFor(event.role());
        if (role == null) return;
        grant(event.userId(), event.organizationId(), role, event.savedBy());
    }

    /** Las cuentas creadas antes de este enlace (o por las semillas, que no pasan por el servicio). */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void backfill() {
        if (!backfillEnabled) return;
        int granted = 0;
        for (DosimetryDirectory.IndustrialAccount account : directory.industrialAccounts()) {
            if (!account.active()) continue;
            if (grant(account.id(), account.organizationId(),
                    account.worker() ? MembershipRole.OPERATOR : MembershipRole.SSO, null)) {
                granted++;
            }
        }
        if (granted > 0) log.info("Personal Dosimetry: {} industrial accounts have their membership", granted);
    }

    /** true si la cuenta queda con su membership (nueva o ya existente). */
    boolean grant(Long userId, Long organizationId, MembershipRole role, Long grantedBy) {
        Long signer = grantedBy != null ? grantedBy : directory.anyAdministratorId().orElse(null);
        if (signer == null) {
            log.warn("No administrator to grant the {} membership of user {}", role, userId);
            return false;
        }
        try {
            dosimetry.grantMembership(new DosimetryActor(signer, true, null, null),
                    new GrantMembership(userId, organizationId, role));
            return true;
        } catch (RuntimeException e) {
            // Otra organización ya asignada, empresa inactiva o cuenta no elegible: no se fuerza.
            log.warn("Dosimetry {} membership for user {} in organization {} not granted: {}",
                    role, userId, organizationId, e.getMessage());
            return false;
        }
    }

    private static MembershipRole roleFor(Role role) {
        if (role == Role.WORKER) return MembershipRole.OPERATOR;
        if (role == Role.SSO_RESPONSIBLE) return MembershipRole.SSO;
        return null;
    }
}
