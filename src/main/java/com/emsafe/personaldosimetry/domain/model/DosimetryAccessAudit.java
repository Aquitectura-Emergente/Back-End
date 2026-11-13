package com.emsafe.personaldosimetry.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "personal_dosimetry_access_audit")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DosimetryAccessAudit {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private String id;
    @Column(length = 150)
    private String principalName;
    @Column(nullable = false, length = 10)
    private String httpMethod;
    @Column(nullable = false, length = 512)
    private String resourcePath;
    @Column(nullable = false)
    private int responseStatus;
    @Column(nullable = false)
    private Instant occurredAt;

    public DosimetryAccessAudit(String principalName, String httpMethod, String resourcePath,
                                int responseStatus, Instant occurredAt) {
        if (responseStatus != 401 && responseStatus != 403) {
            throw new IllegalArgumentException("Only denied access is audited here");
        }
        this.principalName = truncate(principalName, 150);
        this.httpMethod = truncate(httpMethod, 10);
        this.resourcePath = truncate(resourcePath, 512);
        this.responseStatus = responseStatus;
        this.occurredAt = occurredAt;
    }

    private static String truncate(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
