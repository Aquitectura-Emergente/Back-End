package com.emsafe.personaldosimetry.infrastructure;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.devicemanagement.application.DeviceCatalog;
import com.emsafe.personaldosimetry.application.DosimetryDirectory;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class ExistingPlatformDirectory implements DosimetryDirectory {
    private final UserRepository users;
    private final DeviceCatalog devices;

    public User userByEmail(String email) {
        return snapshot(users.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated account not found")));
    }

    public User userById(Long id) {
        return snapshot(users.findById(id).orElseThrow(() -> new ResourceNotFoundException("User", id)));
    }

    public Device deviceById(Long id) {
        var d = devices.findWithClient(id).orElseThrow(() -> new ResourceNotFoundException("Device", id));
        return new Device(d.getId(), d.getClient() == null ? null : d.getClient().getId(), d.isActive());
    }

    public List<IndustrialAccount> industrialAccounts() {
        return Stream.concat(users.findByRole(Role.WORKER).stream(), users.findByRole(Role.SSO_RESPONSIBLE).stream())
                .filter(u -> u.getOrganizationId() != null)
                .map(u -> new IndustrialAccount(u.getId(), u.getOrganizationId(), u.hasRole(Role.WORKER), u.isActive()))
                .toList();
    }

    public Optional<Long> anyAdministratorId() {
        return users.findByRole(Role.ADMIN).stream().filter(AppUser::isActive)
                .map(AppUser::getId).min(Comparator.naturalOrder());
    }

    private User snapshot(AppUser user) {
        return new User(user.getId(), user.getStatus() == null || user.isActive(),
                user.hasRole(Role.ADMIN), user.hasRole(Role.CLIENT),
                "company".equalsIgnoreCase(user.getClientType()),
                user.hasRole(Role.WORKER), user.hasRole(Role.SSO_RESPONSIBLE));
    }
}
