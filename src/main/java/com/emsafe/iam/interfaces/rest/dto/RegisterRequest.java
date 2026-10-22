package com.emsafe.iam.interfaces.rest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public sign-up payload (EMSafe mobile app). Creates a CLIENT account in
 * "pending" state — the user cannot log in until an admin activates it.
 */
public record RegisterRequest(
        @NotBlank(message = "Name is required")
        String name,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 6, message = "Password must be at least 6 characters")
        String password,

        String phone,
        String address,

        // Client profile captured during mobile sign-up (company | individual).
        String clientType,
        String contactName,
        String taxId,
        String industry
) {}
