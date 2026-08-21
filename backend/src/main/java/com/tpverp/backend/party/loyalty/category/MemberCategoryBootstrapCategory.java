package com.tpverp.backend.party.loyalty.category;

import com.tpverp.backend.party.MemberCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Entity
@Table(name = "member_category_bootstrap_category")
public class MemberCategoryBootstrapCategory {
    @Id
    private UUID id;
    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;
    @Column(name = "category_id", nullable = false)
    private UUID categoryId;
    @Column(nullable = false, length = 32)
    private String code;
    @Column(nullable = false, length = 64)
    private String name;
    @Column(name = "min_points", nullable = false)
    private long minPoints;
    @Column(name = "discount_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercent;
    @Column(name = "discount_enabled", nullable = false)
    private boolean discountEnabled;
    @Column(name = "manual_only", nullable = false)
    private boolean manualOnly;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected MemberCategoryBootstrapCategory() {
    }

    public MemberCategoryBootstrapCategory(UUID snapshotId, MemberCategory category) {
        this.id = UUID.nameUUIDFromBytes((snapshotId + "|C|" + category.getId())
                .getBytes(StandardCharsets.UTF_8));
        this.snapshotId = snapshotId;
        this.categoryId = category.getId();
        this.code = category.getCode();
        this.name = category.getName();
        this.minPoints = category.getMinPoints();
        this.discountPercent = category.getDiscountPercent();
        this.discountEnabled = category.isDiscountEnabled();
        this.manualOnly = category.isManualOnly();
        this.active = category.isActive();
        this.sortOrder = category.getSortOrder();
    }
}
