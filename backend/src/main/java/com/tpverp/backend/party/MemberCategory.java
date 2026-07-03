package com.tpverp.backend.party;

import com.tpverp.backend.organization.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_category")
public class MemberCategory {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Company company;
    @Column(nullable = false, length = 32, updatable = false)
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
    private boolean active = true;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
    @Version
    private long version;

    protected MemberCategory() {
    }

    public MemberCategory(Company company, String name, long minPoints, BigDecimal discountPercent,
            boolean discountEnabled, int sortOrder) {
        this(company, name, code(name), minPoints, discountPercent, discountEnabled, false, sortOrder);
    }

    public MemberCategory(Company company, String name, String code, long minPoints, BigDecimal discountPercent,
            boolean discountEnabled, boolean manualOnly, int sortOrder) {
        id = UUID.randomUUID();
        this.company = Objects.requireNonNull(company, "company");
        this.code = code(code);
        this.manualOnly = manualOnly;
        update(name, minPoints, discountPercent, discountEnabled, sortOrder);
    }

    public void update(String name, long minPoints, BigDecimal discountPercent,
            boolean discountEnabled, int sortOrder) {
        if (minPoints < 0) {
            throw new IllegalArgumentException("message.member_category.min_points_invalid");
        }
        this.name = PartyValues.required(name, "name");
        this.minPoints = minPoints;
        this.discountPercent = PartyValues.discount(discountPercent);
        this.discountEnabled = discountEnabled;
        this.sortOrder = sortOrder;
    }

    public void deactivate() {
        active = false;
    }

    public UUID getId() {
        return id;
    }

    public Company getCompany() {
        return company;
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public long getMinPoints() {
        return minPoints;
    }

    public BigDecimal getDiscountPercent() {
        return discountPercent;
    }

    public boolean isDiscountEnabled() {
        return discountEnabled;
    }

    public boolean isManualOnly() {
        return manualOnly;
    }

    public boolean isActive() {
        return active;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    private static String code(String value) {
        return PartyValues.required(value, "code").toUpperCase(Locale.ROOT)
                .replace(' ', '_');
    }
}
