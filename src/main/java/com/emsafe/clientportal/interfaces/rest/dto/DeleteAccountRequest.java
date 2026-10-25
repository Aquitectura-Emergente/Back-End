package com.emsafe.clientportal.interfaces.rest.dto;

/** Confirmation payload for self-service account deletion (current password). */
public record DeleteAccountRequest(String password) {}
