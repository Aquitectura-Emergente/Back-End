package com.emsafe.iam.application.events;

import com.emsafe.iam.domain.model.Role;

/**
 * Se creó o modificó una cuenta del segmento industrial (WORKER o
 * SSO_RESPONSIBLE) ligada a una organización. Lo escuchan los contextos que dan
 * acceso por organización, como Personal Dosimetry con sus memberships.
 *
 * @param savedBy quién hizo el cambio; null si fue el propio sistema (semillas).
 */
public record IndustrialAccountSaved(Long userId, Role role, Long organizationId, boolean active, Long savedBy) {
}
