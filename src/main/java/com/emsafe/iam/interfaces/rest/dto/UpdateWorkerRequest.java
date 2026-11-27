package com.emsafe.iam.interfaces.rest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Cambios del responsable de SSO sobre un operario suyo; los campos nulos no cambian. */
public record UpdateWorkerRequest(
        String name,

        @Email(message = "Invalid email format")
        String email,

        String phone,
        String department,

        @Pattern(regexp = "active|inactive", message = "Status must be active or inactive")
        String status,

        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,

        String notes
) {}
