package com.emsafe.clientportal.interfaces.rest.dto;

/**
 * Fields a client may update on their own profile from the mobile app.
 * Email, role and password are intentionally excluded.
 */
public record UpdateClientProfileRequest(
        String name,
        String phone,
        String location,
        String address,
        Double latitude,
        Double longitude
) {}
