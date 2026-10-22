package com.emsafe.clientportal.interfaces.rest.dto;

import com.emsafe.iam.domain.model.AppUser;

import java.time.LocalDate;

/**
 * Profile of the logged-in client (mobile app).
 * Role is returned in lowercase ("client") to stay consistent with the login response.
 */
public record ClientProfileDto(
        Long id,
        String name,
        String initials,
        String email,
        String role,
        String phone,
        String location,
        String address,
        Double latitude,
        Double longitude,
        LocalDate joinDate
) {
    public static ClientProfileDto from(AppUser u) {
        return new ClientProfileDto(
                u.getId(),
                u.getName(),
                u.getInitials(),
                u.getEmail(),
                u.getRole().name().toLowerCase(),
                u.getPhone(),
                u.getLocation(),
                u.getAddress(),
                u.getLatitude(),
                u.getLongitude(),
                u.getJoinDate()
        );
    }
}
