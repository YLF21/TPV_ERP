package com.tpverp.saas.loyalty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saas_member_points_authority")
public class SaasMemberPointsAuthority {

    @Id
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MemberPointsAuthorityStatus status;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SaasMemberPointsAuthority() {
    }

    public SaasMemberPointsAuthority(UUID companyId, Instant now) {
        this.companyId = companyId;
        this.status = MemberPointsAuthorityStatus.NOT_INITIALIZED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public MemberPointsAuthorityStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == MemberPointsAuthorityStatus.ACTIVE;
    }

    public void activate(Instant now) {
        if (!isActive()) {
            status = MemberPointsAuthorityStatus.ACTIVE;
            activatedAt = now;
            updatedAt = now;
        }
    }
}
