package com.tpverp.backend.party.loyalty.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "member_category_bootstrap_assignment")
public class MemberCategoryBootstrapAssignment {
    @Id
    private UUID id;
    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;
    @Column(name = "member_id", nullable = false)
    private UUID memberId;
    @Column(name = "category_id")
    private UUID categoryId;
    @Column(name = "lock_automatic", nullable = false)
    private boolean lockAutomatic;
    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;
    @Column(name = "assignment_source", nullable = false, length = 24)
    private String assignmentSource;
    @Column(name = "assignment_action", nullable = false, length = 8)
    private String assignmentAction;
    @Column(name = "lock_known", nullable = false)
    private boolean lockKnown;

    protected MemberCategoryBootstrapAssignment() {
    }

    public MemberCategoryBootstrapAssignment(
            UUID snapshotId,
            UUID memberId,
            UUID categoryId,
            boolean lockAutomatic,
            Instant assignedAt,
            String assignmentSource,
            String assignmentAction,
            boolean lockKnown) {
        this.id = UUID.nameUUIDFromBytes((snapshotId + "|M|" + memberId)
                .getBytes(StandardCharsets.UTF_8));
        this.snapshotId = snapshotId;
        this.memberId = memberId;
        this.categoryId = categoryId;
        this.lockAutomatic = lockAutomatic;
        this.assignedAt = assignedAt;
        this.assignmentSource = assignmentSource;
        this.assignmentAction = assignmentAction;
        this.lockKnown = lockKnown;
    }
}
