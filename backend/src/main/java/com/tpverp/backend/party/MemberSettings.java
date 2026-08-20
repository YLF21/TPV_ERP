package com.tpverp.backend.party;

import com.tpverp.backend.organization.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_settings")
public class MemberSettings {

    @Id
    @Column(name = "empresa_id")
    private UUID companyId;
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Company company;
    @Column(name = "balance_accrual_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal balanceAccrualPercent = BigDecimal.ZERO.setScale(2);
    @Column(name = "balance_accrual_base_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAccrualBaseAmount = BigDecimal.ONE.setScale(2);
    @Column(name = "balance_accrual_enabled", nullable = false)
    private boolean balanceAccrualEnabled;
    @Enumerated(EnumType.STRING)
    @Column(name = "balance_expiration_policy", nullable = false, length = 16)
    private BalanceExpirationPolicy balanceExpirationPolicy = BalanceExpirationPolicy.NO_CADUCA;
    @Column(name = "points_per_euro", nullable = false, precision = 8, scale = 2)
    private BigDecimal pointsPerEuro = BigDecimal.ONE.setScale(2);
    @Column(name = "points_accrual_base_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal pointsAccrualBaseAmount = BigDecimal.ONE.setScale(2);
    @Column(name = "points_accrual_enabled", nullable = false)
    private boolean pointsAccrualEnabled = true;
    @Column(name = "category_auto_enabled", nullable = false)
    private boolean categoryAutoEnabled = true;
    @Column(name = "member_welcome_enabled", nullable = false)
    private boolean memberWelcomeEnabled;
    @Enumerated(EnumType.STRING)
    @Column(name = "member_card_code_format", nullable = false, length = 16)
    private MemberCardCodeFormat memberCardCodeFormat = MemberCardCodeFormat.QR;
    @Column(name = "welcome_subject_template", columnDefinition = "text")
    private String welcomeSubjectTemplate;
    @Column(name = "welcome_body_template", columnDefinition = "text")
    private String welcomeBodyTemplate;
    @Version
    private long version;

    protected MemberSettings() {
    }

    public MemberSettings(Company company) {
        this.company = Objects.requireNonNull(company, "company");
        this.companyId = company.getId();
    }

    public void update(boolean balanceAccrualEnabled, BigDecimal balanceAccrualBaseAmount,
            BigDecimal balanceAccrualPercent, BalanceExpirationPolicy expirationPolicy,
            boolean pointsAccrualEnabled, BigDecimal pointsAccrualBaseAmount,
            BigDecimal pointsPerEuro, boolean categoryAutoEnabled, boolean welcomeEnabled,
            MemberCardCodeFormat cardFormat, String subject, String body) {
        this.balanceAccrualEnabled = balanceAccrualEnabled;
        this.balanceAccrualBaseAmount = positiveMoney(
                balanceAccrualBaseAmount, "balanceAccrualBaseAmount");
        this.balanceAccrualPercent = PartyValues.discount(balanceAccrualPercent);
        this.balanceExpirationPolicy = Objects.requireNonNull(expirationPolicy, "expirationPolicy");
        this.pointsAccrualEnabled = pointsAccrualEnabled;
        this.pointsAccrualBaseAmount = positiveMoney(
                pointsAccrualBaseAmount, "pointsAccrualBaseAmount");
        this.pointsPerEuro = PartyValues.money(pointsPerEuro);
        this.categoryAutoEnabled = categoryAutoEnabled;
        this.memberWelcomeEnabled = welcomeEnabled;
        this.memberCardCodeFormat = Objects.requireNonNull(cardFormat, "cardFormat");
        this.welcomeSubjectTemplate = PartyValues.optional(subject);
        this.welcomeBodyTemplate = PartyValues.optional(body);
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public BigDecimal getBalanceAccrualPercent() {
        return balanceAccrualPercent;
    }

    public BigDecimal getBalanceAccrualBaseAmount() {
        return balanceAccrualBaseAmount;
    }

    public boolean isBalanceAccrualEnabled() {
        return balanceAccrualEnabled;
    }

    public BalanceExpirationPolicy getBalanceExpirationPolicy() {
        return balanceExpirationPolicy;
    }

    public BigDecimal getPointsPerEuro() {
        return pointsPerEuro;
    }

    public BigDecimal getPointsAccrualBaseAmount() {
        return pointsAccrualBaseAmount;
    }

    public boolean isPointsAccrualEnabled() {
        return pointsAccrualEnabled;
    }

    public boolean isCategoryAutoEnabled() {
        return categoryAutoEnabled;
    }

    public boolean isMemberWelcomeEnabled() {
        return memberWelcomeEnabled;
    }

    public MemberCardCodeFormat getMemberCardCodeFormat() {
        return memberCardCodeFormat;
    }

    public String getWelcomeSubjectTemplate() {
        return welcomeSubjectTemplate;
    }

    public String getWelcomeBodyTemplate() {
        return welcomeBodyTemplate;
    }

    private static BigDecimal positiveMoney(BigDecimal value, String field) {
        var amount = PartyValues.money(value);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException(field + " debe ser mayor que cero");
        }
        return amount;
    }
}
