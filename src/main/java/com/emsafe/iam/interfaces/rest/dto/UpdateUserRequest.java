package com.emsafe.iam.interfaces.rest.dto;

import java.time.LocalDate;

public record UpdateUserRequest(
        String name,
        String initials,
        String email,
        String phone,
        String location,
        String status,
        String specialty,
        String department,
        LocalDate joinDate,
        String password,
        String notes,
        String address,
        Double latitude,
        Double longitude,
        String clientType,
        String taxId,
        String industry,
        String country,
        String contactName,
        String contactEmail,
        String contactPhone
) {}
