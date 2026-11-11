package com.emsafe.personaldosimetry.infrastructure;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.iottelemetry.infrastructure.persistence.DeviceRepository;
import com.emsafe.personaldosimetry.application.DosimetryDirectory;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExistingPlatformDirectory implements DosimetryDirectory {
    private final UserRepository users;
    private final DeviceRepository devices;

    public User userByEmail(String email) {
        return snapshot(users.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated account not found")));
    }

    public User userById(Long id) {
        return snapshot(users.findById(id).orElseThrow(() -> new ResourceNotFoundException("User", id)));
    }

    public Device deviceById(Long id) {
        var d = devices.findByIdWithClient(id).orElseThrow(() -> new ResourceNotFoundException("Device", id));
        return new Device(d.getId(), d.getClient() == null ? null : d.getClient().getId(), d.isActive());
    }

    private User snapshot(AppUser user) {
        return new User(user.getId(), user.getStatus() == null || user.isActive(),
                user.hasRole(Role.ADMIN), user.hasRole(Role.CLIENT),
                "company".equalsIgnoreCase(user.getClientType()));
    }
}
