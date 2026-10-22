package com.emsafe.iam.interfaces.rest.dto;

import com.emsafe.iam.domain.model.AppUser;

import java.time.LocalDate;

public record UserDto(
        Long id,
        String name,
        String initials,
        String email,
        String role,
        String phone,
        String location,
        String status,
        String specialty,
        String department,
        LocalDate joinDate,
        String notes,
        String address,
        String clientType,
        String taxId,
        String industry,
        String country,
        String contactName,
        String contactEmail,
        String contactPhone
) {
    public static UserDto from(AppUser u) {
        // Serialize role as title-case to match Angular frontend ("Admin", "Technician", "Client")
        String roleName = u.getRole().name();
        String roleDisplay = roleName.charAt(0) + roleName.substring(1).toLowerCase();

        return new UserDto(
                u.getId(),
                u.getName(),
                u.getInitials(),
                u.getEmail(),
                roleDisplay,
                u.getPhone(),
                u.getLocation(),
                u.getStatus(),
                u.getSpecialty(),
                u.getDepartment(),
                u.getJoinDate(),
                u.getNotes(),
                u.getAddress(),
                u.getClientType(),
                u.getTaxId(),
                u.getIndustry(),
                u.getCountry(),
                u.getContactName(),
                u.getContactEmail(),
                u.getContactPhone()
        );
    }
}
