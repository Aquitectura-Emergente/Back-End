package com.emsafe.iam.interfaces.rest.dto;

public record RefreshResponse(String token, long expiresIn) {}
