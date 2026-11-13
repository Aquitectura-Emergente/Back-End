package com.emsafe.devicemanagement.domain.model;

import com.emsafe.shared.exception.BadRequestException;

import java.util.regex.Pattern;

/** Código con el que los Edges identifican a la organización ({@code organizationId} del contrato). */
public record OrganizationCode(String value) {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");

    public OrganizationCode {
        if (value == null || !SLUG.matcher(value).matches()) {
            throw new BadRequestException("Organization code must be a lowercase slug of 2 to 64 characters");
        }
    }
}
