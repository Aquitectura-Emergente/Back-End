package com.emsafe.personaldosimetry.domain.model;

public class DosimetryRuleException extends RuntimeException {
    public enum Kind { INVALID_INPUT, CONFLICT }
    private final Kind kind;

    private DosimetryRuleException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() { return kind; }

    public static DosimetryRuleException invalid(String message) {
        return new DosimetryRuleException(Kind.INVALID_INPUT, message);
    }

    public static DosimetryRuleException conflict(String message) {
        return new DosimetryRuleException(Kind.CONFLICT, message);
    }
}
