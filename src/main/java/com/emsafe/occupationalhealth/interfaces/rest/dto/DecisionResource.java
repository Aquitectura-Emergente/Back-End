package com.emsafe.occupationalhealth.interfaces.rest.dto;

import com.emsafe.occupationalhealth.domain.model.Decision;

public record DecisionResource(Long decidedBy, String decidedAt, String reason) {
    public static DecisionResource from(Decision d) {
        if (d == null) return null;
        return new DecisionResource(
                d.getDecidedBy(),
                d.getDecidedAt() != null ? d.getDecidedAt().toString() : null,
                d.getReason());
    }
}
