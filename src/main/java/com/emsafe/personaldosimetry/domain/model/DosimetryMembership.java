package com.emsafe.personaldosimetry.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "personal_dosimetry_memberships")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DosimetryMembership {
    @Id
    private Long userId;
    @Column(nullable = false)
    private Long organizationId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MembershipRole role;
    @Column(nullable = false)
    private Long grantedBy;
    @Column(nullable = false)
    private Instant grantedAt;

    public DosimetryMembership(Long userId, Long organizationId, MembershipRole role,
                               Long grantedBy, Instant grantedAt) {
        ExposureIndex.positiveId(userId, "userId");
        ExposureIndex.positiveId(organizationId, "organizationId");
        if (role == null) throw DosimetryRuleException.invalid("role is required");
        this.userId = userId;
        this.organizationId = organizationId;
        this.role = role;
        this.grantedBy = grantedBy;
        this.grantedAt = grantedAt;
    }
}
