package com.emsafe.devicemanagement.infrastructure.acl;

import com.emsafe.devicemanagement.application.ports.AccountDirectory;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class IamAccountDirectory implements AccountDirectory {
    private final UserRepository users;

    @Override
    public Optional<AppUser> findById(Long id) {
        return id == null ? Optional.empty() : users.findById(id);
    }

    @Override
    public Optional<AppUser> findByEmail(String email) {
        return email == null ? Optional.empty() : users.findByEmail(email);
    }
}
