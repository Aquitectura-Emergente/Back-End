package com.emsafe.devicemanagement.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/** Código de organización que usan los Edges, mapeado a la cuenta de la organización. */
@Entity
@Table(name = "dm_organization_codes")
@Getter
public class OrganizationCodeEntity {
    @Id
    @Column(length = 64)
    private String code;
    @Column(name = "organization_id", nullable = false, unique = true)
    private Long organizationId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OrganizationCodeEntity() {
    }

    public OrganizationCodeEntity(String code, Long organizationId, Instant createdAt) {
        this.code = code;
        this.organizationId = organizationId;
        this.createdAt = createdAt;
    }
}
