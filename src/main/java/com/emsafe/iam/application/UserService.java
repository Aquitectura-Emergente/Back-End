package com.emsafe.iam.application;

import com.emsafe.shared.exception.BadRequestException;
import com.emsafe.shared.exception.ResourceNotFoundException;
import com.emsafe.iam.interfaces.rest.dto.ChangePasswordRequest;
import com.emsafe.iam.interfaces.rest.dto.CreateUserRequest;
import com.emsafe.iam.interfaces.rest.dto.UpdateUserRequest;
import com.emsafe.iam.interfaces.rest.dto.UserDto;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Initials;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.domain.model.UserStatus;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public List<UserDto> findAll(String role) {
        List<AppUser> users;
        if (StringUtils.hasText(role)) {
            try {
                Role r = Role.valueOf(role.toUpperCase());
                users = userRepository.findByRole(r);
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("Invalid role: " + role);
            }
        } else {
            users = userRepository.findAll();
        }
        return users.stream().map(UserDto::from).toList();
    }

    public UserDto findById(Long id) {
        return userRepository.findById(id)
                .map(UserDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    @Transactional
    public UserDto create(CreateUserRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new BadRequestException("Email already in use: " + req.email());
        }

        String initials = StringUtils.hasText(req.initials())
                ? req.initials()
                : Initials.from(req.name());

        AppUser user = AppUser.builder()
                .name(req.name())
                .initials(initials)
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .role(req.role())
                .phone(req.phone())
                .location(req.location())
                .specialty(req.specialty())
                .department(req.department())
                .joinDate(req.joinDate() != null ? req.joinDate() : LocalDate.now())
                .status(UserStatus.ACTIVE)
                .notes(req.notes())
                .build();

        return UserDto.from(userRepository.save(user));
    }

    @Transactional
    public UserDto update(Long id, UpdateUserRequest req) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));

        if (StringUtils.hasText(req.name())) user.setName(req.name());
        if (StringUtils.hasText(req.initials())) user.setInitials(req.initials());
        if (StringUtils.hasText(req.email()) && !req.email().equalsIgnoreCase(user.getEmail())) {
            if (userRepository.existsByEmail(req.email())) {
                throw new BadRequestException("Email already in use: " + req.email());
            }
            user.setEmail(req.email().trim());
        }
        if (StringUtils.hasText(req.phone())) user.setPhone(req.phone());
        if (StringUtils.hasText(req.location())) user.setLocation(req.location());
        if (StringUtils.hasText(req.status())) user.setStatus(req.status());
        if (StringUtils.hasText(req.specialty())) user.setSpecialty(req.specialty());
        if (StringUtils.hasText(req.department())) user.setDepartment(req.department());
        if (req.joinDate() != null) user.setJoinDate(req.joinDate());
        if (req.notes() != null) user.setNotes(req.notes());
        // Client (company / individual) profile fields — allow clearing with non-null empty.
        if (req.address() != null) user.setAddress(req.address());
        // Map-picker coordinates (the web/mobile radiation map depends on these).
        if (req.latitude() != null) user.setLatitude(req.latitude());
        if (req.longitude() != null) user.setLongitude(req.longitude());
        if (req.clientType() != null) user.setClientType(req.clientType());
        if (req.taxId() != null) user.setTaxId(req.taxId());
        if (req.industry() != null) user.setIndustry(req.industry());
        if (req.country() != null) user.setCountry(req.country());
        if (req.contactName() != null) user.setContactName(req.contactName());
        if (req.contactEmail() != null) user.setContactEmail(req.contactEmail());
        if (req.contactPhone() != null) user.setContactPhone(req.contactPhone());
        if (StringUtils.hasText(req.password())) {
            user.setPasswordHash(passwordEncoder.encode(req.password()));
        }

        return UserDto.from(userRepository.save(user));
    }

    @Transactional
    public void changePassword(Long id, ChangePasswordRequest req) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepository.save(user);
    }

    @Transactional
    public void delete(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User", id);
        }
        userRepository.deleteById(id);
    }
}
