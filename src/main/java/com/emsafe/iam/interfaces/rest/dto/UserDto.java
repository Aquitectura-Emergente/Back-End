package com.emsafe.iam.interfaces.rest.dto;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;

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
        String contactPhone,
        Long organizationId
) {
    public static UserDto from(AppUser u) {
        return new UserDto(
                u.getId(),
                u.getName(),
                u.getInitials(),
                u.getEmail(),
                display(u.getRole()),
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
                u.getContactPhone(),
                u.getOrganizationId()
        );
    }

    /**
     * El rol en title-case, que es como lo espera el front de Angular
     * ("Admin", "Technician", "Client").
     *
     * SSO es un acrónimo y se queda en mayúsculas: el title-case genérico lo
     * convertiría en "Sso".
     */
    private static String display(Role role) {
        if (role == Role.SSO) return "SSO";
        String name = role.name();
        return name.charAt(0) + name.substring(1).toLowerCase();
    }
}
