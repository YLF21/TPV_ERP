package com.tpverp.backend.promotion;

import com.tpverp.backend.document.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromotionalCouponService {

    private final PromotionalCouponRepository coupons;
    private final PromotionalCouponAttemptRepository attempts;
    private final CouponCodeGenerator codeGenerator;
    private final Clock clock;

    @Autowired
    public PromotionalCouponService(
            PromotionalCouponRepository coupons,
            PromotionalCouponAttemptRepository attempts) {
        this(coupons, attempts, new SecureRandomCouponCodeGenerator(), Clock.systemDefaultZone());
    }

    PromotionalCouponService(
            PromotionalCouponRepository coupons,
            PromotionalCouponAttemptRepository attempts,
            CouponCodeGenerator codeGenerator,
            Clock clock) {
        this.coupons = Objects.requireNonNull(coupons, "coupons");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public CreationResult generateAfterTicketConfirmation(CreationCommand command) {
        validateCreation(command);
        var code = codeGenerator.generate();
        var coupon = createCoupon(command, code, now());
        coupons.save(coupon);
        return new CreationResult(coupon.id(), code, coupon.codeLast4(), coupon.validFrom(), coupon.validUntil());
    }

    @Transactional
    public RedemptionResult redeem(RedemptionCommand command) {
        validateRedemption(command);
        var codeHash = hash(command.code());
        var codeLast4 = last4(command.code());
        var coupon = coupons.findLockedByCompanyIdAndCodeHash(command.companyId(), codeHash);
        return redeemLocked(command, coupon, codeHash, codeLast4);
    }

    /**
     * Consumes a coupon already frozen into an authorized fiscal snapshot. The
     * public coupon code is deliberately not persisted in that snapshot.
     */
    @Transactional
    public RedemptionResult redeemAuthorized(AuthorizedRedemptionCommand command) {
        validateAuthorizedRedemption(command);
        var coupon = coupons.findLockedByIdAndCompanyId(command.couponId(), command.companyId());
        if (coupon.isEmpty()) {
            return RedemptionResult.rejected(command.documentId(), CouponRejectReason.NOT_FOUND);
        }
        var existing = coupon.orElseThrow();
        var context = new RedemptionCommand(
                command.companyId(), command.storeId(), command.documentId(), command.userId(),
                command.terminalId(), command.customerId(), command.memberId(),
                command.memberCategoryId(), existing.codeLast4(), command.pendingDocumentAmount());
        return redeemLocked(context, coupon, existing.codeHash(), existing.codeLast4());
    }

    private RedemptionResult redeemLocked(
            RedemptionCommand command,
            Optional<PromotionalCoupon> coupon,
            String codeHash,
            String codeLast4) {
        if (coupon.isEmpty()) {
            recordAttempt(command, codeHash, codeLast4, CouponRejectReason.NOT_FOUND);
            return RedemptionResult.rejected(command.documentId(), CouponRejectReason.NOT_FOUND);
        }

        var existing = coupon.get();
        var statusRejection = statusRejection(existing, currentDate());
        if (statusRejection != null) {
            recordAttempt(command, codeHash, codeLast4, statusRejection);
            return RedemptionResult.rejected(command.documentId(), statusRejection);
        }
        var eligibilityRejection = eligibilityRejection(existing, command);
        if (eligibilityRejection != null) {
            recordAttempt(command, codeHash, codeLast4, eligibilityRejection);
            return RedemptionResult.rejected(command.documentId(), eligibilityRejection);
        }
        if (existing.minimumAmount() != null
                && command.pendingDocumentAmount().compareTo(existing.minimumAmount()) < 0) {
            recordAttempt(command, codeHash, codeLast4, CouponRejectReason.MINIMUM_NOT_REACHED);
            return RedemptionResult.rejected(command.documentId(), CouponRejectReason.MINIMUM_NOT_REACHED);
        }

        var redeemed = redeemableAmount(existing, command.pendingDocumentAmount());
        if (redeemed.signum() <= 0) {
            recordAttempt(command, codeHash, codeLast4, CouponRejectReason.DOCUMENT_NOT_ELIGIBLE);
            return RedemptionResult.rejected(command.documentId(), CouponRejectReason.DOCUMENT_NOT_ELIGIBLE);
        }

        existing.use(command.storeId(), command.documentId(), now());
        var replacement = replacementForRemainingBalance(existing, command, redeemed);
        return RedemptionResult.accepted(
                command.documentId(),
                existing.id(),
                existing.promotionId(),
                existing.codeLast4(),
                redeemed,
                replacement.map(result -> result));
    }

    /**
     * Evaluates a coupon without consuming it. Checkout must evaluate again through
     * {@link #redeem(RedemptionCommand)} in the same transaction that creates the
     * fiscal document; a quote never reserves credit by itself.
     */
    @Transactional(readOnly = true)
    public EvaluationResult evaluate(RedemptionCommand command) {
        validateRedemption(command);
        var coupon = coupons.findByEmpresaIdAndCodigoHash(command.companyId(), hash(command.code()));
        if (coupon.isEmpty()) {
            return EvaluationResult.rejected(CouponRejectReason.NOT_FOUND);
        }
        var existing = coupon.orElseThrow();
        var statusRejection = previewStatusRejection(existing, currentDate());
        if (statusRejection != null) {
            return EvaluationResult.rejected(statusRejection);
        }
        var eligibilityRejection = eligibilityRejection(existing, command);
        if (eligibilityRejection != null) {
            return EvaluationResult.rejected(eligibilityRejection);
        }
        if (existing.minimumAmount() != null
                && command.pendingDocumentAmount().compareTo(existing.minimumAmount()) < 0) {
            return EvaluationResult.rejected(CouponRejectReason.MINIMUM_NOT_REACHED);
        }
        var amount = redeemableAmount(existing, command.pendingDocumentAmount());
        if (amount.signum() <= 0) {
            return EvaluationResult.rejected(CouponRejectReason.DOCUMENT_NOT_ELIGIBLE);
        }
        return EvaluationResult.accepted(
                existing.id(), existing.promotionId(), existing.codeLast4(), amount);
    }

    @Transactional(readOnly = true)
    public List<CouponView> list(UUID companyId, PromotionalCouponStatus status, String codeLast4) {
        Objects.requireNonNull(companyId, "companyId");
        var normalizedLast4 = codeLast4 == null || codeLast4.isBlank() ? null : last4(codeLast4);
        var source = status == null
                ? coupons.findByEmpresaId(companyId)
                : coupons.findByEmpresaIdAndEstado(companyId, status);
        return source.stream()
                .filter(coupon -> normalizedLast4 == null || coupon.codeLast4().equals(normalizedLast4))
                .sorted(Comparator.comparing(PromotionalCoupon::validUntil)
                        .thenComparing(PromotionalCoupon::codeLast4))
                .map(CouponView::from)
                .toList();
    }

    @Transactional
    public CouponView cancel(AdminActionCommand command) {
        var coupon = coupon(command);
        coupon.cancel(command.userId(), command.reason(), now());
        return CouponView.from(coupon);
    }

    @Transactional
    public CouponView reactivate(AdminActionCommand command) {
        var coupon = coupon(command);
        coupon.reactivate(command.userId(), command.reason(), currentDate(), now());
        return CouponView.from(coupon);
    }

    String hashForTest(String code) {
        return hash(code);
    }

    private PromotionalCoupon coupon(AdminActionCommand command) {
        validateAdminAction(command);
        return coupons.findByIdAndEmpresaId(command.couponId(), command.companyId())
                .orElseThrow(() -> new IllegalArgumentException("message.coupon.not_found"));
    }

    private PromotionalCoupon createCoupon(CreationCommand command, String code, Instant createdAt) {
        var validFrom = toLocalDate(command.validFrom());
        var validUntil = toLocalDate(command.validUntil());
        if (command.benefitType() == PromotionalCouponBenefitType.AMOUNT) {
            return PromotionalCoupon.amount(
                    command.companyId(),
                    command.generatedStoreId(),
                    command.promotionId(),
                    command.generatedDocumentId(),
                    hash(code),
                    last4(code),
                    customerId(command),
                    memberId(command),
                    command.amount(),
                    command.minimumAmount(),
                    validFrom,
                    validUntil,
                    createdAt);
        }
        return PromotionalCoupon.percent(
                command.companyId(),
                command.generatedStoreId(),
                command.promotionId(),
                command.generatedDocumentId(),
                hash(code),
                last4(code),
                customerId(command),
                memberId(command),
                command.percent(),
                command.maximumDiscount(),
                command.minimumAmount(),
                validFrom,
                validUntil,
                createdAt);
    }

    private Optional<CreationResult> replacementForRemainingBalance(
            PromotionalCoupon existing,
            RedemptionCommand command,
            BigDecimal redeemed) {
        if (existing.benefitType() != PromotionalCouponBenefitType.AMOUNT) {
            return Optional.empty();
        }
        var remaining = Money.euros(existing.amount().subtract(redeemed));
        if (remaining.signum() <= 0) {
            return Optional.empty();
        }
        var replacementCode = codeGenerator.generate();
        var replacement = PromotionalCoupon.amount(
                existing.companyId(),
                command.storeId(),
                existing.promotionId(),
                command.documentId(),
                hash(replacementCode),
                last4(replacementCode),
                existing.customerId(),
                existing.memberId(),
                remaining,
                existing.minimumAmount(),
                existing.validFrom(),
                existing.validUntil(),
                now());
        coupons.save(replacement);
        return Optional.of(new CreationResult(
                replacement.id(),
                replacementCode,
                replacement.codeLast4(),
                replacement.validFrom(),
                replacement.validUntil()));
    }

    private CouponRejectReason statusRejection(PromotionalCoupon coupon, LocalDate currentDate) {
        if (coupon.status() == PromotionalCouponStatus.USED) {
            return CouponRejectReason.USED;
        }
        if (coupon.status() == PromotionalCouponStatus.CANCELLED) {
            return CouponRejectReason.CANCELLED;
        }
        if (coupon.status() == PromotionalCouponStatus.EXPIRED || currentDate.isAfter(coupon.validUntil())) {
            coupon.expire(now());
            return CouponRejectReason.EXPIRED;
        }
        if (currentDate.isBefore(coupon.validFrom())) {
            return CouponRejectReason.DOCUMENT_NOT_ELIGIBLE;
        }
        return null;
    }

    private static CouponRejectReason previewStatusRejection(
            PromotionalCoupon coupon,
            LocalDate currentDate) {
        if (coupon.status() == PromotionalCouponStatus.USED) {
            return CouponRejectReason.USED;
        }
        if (coupon.status() == PromotionalCouponStatus.CANCELLED) {
            return CouponRejectReason.CANCELLED;
        }
        if (coupon.status() == PromotionalCouponStatus.EXPIRED
                || currentDate.isAfter(coupon.validUntil())) {
            return CouponRejectReason.EXPIRED;
        }
        if (currentDate.isBefore(coupon.validFrom())) {
            return CouponRejectReason.DOCUMENT_NOT_ELIGIBLE;
        }
        return null;
    }

    private CouponRejectReason eligibilityRejection(PromotionalCoupon coupon, RedemptionCommand command) {
        if (coupon.customerId() != null && !coupon.customerId().equals(command.customerId())) {
            return CouponRejectReason.CUSTOMER_MISMATCH;
        }
        if (coupon.memberId() != null && !coupon.memberId().equals(command.memberId())) {
            return CouponRejectReason.CUSTOMER_MISMATCH;
        }
        return null;
    }

    private BigDecimal redeemableAmount(PromotionalCoupon coupon, BigDecimal pendingAmount) {
        var documentAmount = Money.euros(pendingAmount);
        if (coupon.benefitType() == PromotionalCouponBenefitType.AMOUNT) {
            return coupon.amount().min(documentAmount);
        }
        var discount = documentAmount.multiply(coupon.percent())
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        if (coupon.maximumDiscount() != null) {
            discount = discount.min(coupon.maximumDiscount());
        }
        return discount.min(documentAmount);
    }

    private void recordAttempt(
            RedemptionCommand command,
            String codeHash,
            String codeLast4,
            CouponRejectReason reason) {
        attempts.save(new PromotionalCouponAttempt(
                command.companyId(),
                command.storeId(),
                command.userId(),
                command.terminalId(),
                command.documentId(),
                codeHash,
                codeLast4,
                reason,
                now()));
    }

    private UUID customerId(CreationCommand command) {
        if (command.customerSegment() == PromotionCustomerSegment.IDENTIFIED_CUSTOMERS) {
            return Objects.requireNonNull(command.customerId(), "customerId");
        }
        return command.customerId();
    }

    private UUID memberId(CreationCommand command) {
        if (command.customerSegment() == PromotionCustomerSegment.MEMBERS_ONLY
                || command.customerSegment() == PromotionCustomerSegment.MEMBER_CATEGORY) {
            Objects.requireNonNull(command.memberId(), "memberId");
        }
        if (command.customerSegment() == PromotionCustomerSegment.MEMBER_CATEGORY) {
            Objects.requireNonNull(command.memberCategoryId(), "memberCategoryId");
        }
        return command.memberId();
    }

    private void validateCreation(CreationCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.companyId(), "companyId");
        Objects.requireNonNull(command.generatedStoreId(), "generatedStoreId");
        Objects.requireNonNull(command.promotionId(), "promotionId");
        Objects.requireNonNull(command.generatedDocumentId(), "generatedDocumentId");
        Objects.requireNonNull(command.customerSegment(), "customerSegment");
        Objects.requireNonNull(command.benefitType(), "benefitType");
        Objects.requireNonNull(command.validFrom(), "validFrom");
        Objects.requireNonNull(command.validUntil(), "validUntil");
    }

    private void validateRedemption(RedemptionCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.companyId(), "companyId");
        Objects.requireNonNull(command.storeId(), "storeId");
        Objects.requireNonNull(command.documentId(), "documentId");
        Objects.requireNonNull(command.code(), "code");
        if (command.code().isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        if (Money.euros(command.pendingDocumentAmount()).signum() <= 0) {
            throw new IllegalArgumentException("pendingDocumentAmount debe ser positivo");
        }
    }

    private void validateAuthorizedRedemption(AuthorizedRedemptionCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.companyId(), "companyId");
        Objects.requireNonNull(command.storeId(), "storeId");
        Objects.requireNonNull(command.documentId(), "documentId");
        Objects.requireNonNull(command.couponId(), "couponId");
        if (Money.euros(command.pendingDocumentAmount()).signum() <= 0) {
            throw new IllegalArgumentException("pendingDocumentAmount debe ser positivo");
        }
    }

    private void validateAdminAction(AdminActionCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.companyId(), "companyId");
        Objects.requireNonNull(command.couponId(), "couponId");
        Objects.requireNonNull(command.userId(), "userId");
        if (command.reason() == null || command.reason().isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
    }

    private LocalDate toLocalDate(Instant instant) {
        return Objects.requireNonNull(instant, "instant").atZone(clock.getZone()).toLocalDate();
    }

    private LocalDate currentDate() {
        return LocalDate.now(clock);
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static String last4(String code) {
        var normalized = Objects.requireNonNull(code, "code").trim();
        if (normalized.length() < 4) {
            throw new IllegalArgumentException("code debe tener al menos 4 caracteres");
        }
        return normalized.substring(normalized.length() - 4);
    }

    private static String hash(String code) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(code, "code").trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    @FunctionalInterface
    interface CouponCodeGenerator {
        String generate();
    }

    private static final class SecureRandomCouponCodeGenerator implements CouponCodeGenerator {
        private final SecureRandom random = new SecureRandom();

        @Override
        public String generate() {
            var bytes = new byte[12];
            random.nextBytes(bytes);
            return "CP-" + HexFormat.of().formatHex(bytes).toUpperCase();
        }
    }

    public record CreationCommand(
            UUID companyId,
            UUID generatedStoreId,
            UUID promotionId,
            UUID generatedDocumentId,
            UUID customerId,
            UUID memberId,
            PromotionCustomerSegment customerSegment,
            UUID memberCategoryId,
            PromotionalCouponBenefitType benefitType,
            BigDecimal amount,
            BigDecimal percent,
            BigDecimal maximumDiscount,
            BigDecimal minimumAmount,
            Instant validFrom,
            Instant validUntil) {
    }

    public record CreationResult(
            UUID couponId,
            String code,
            String codeLast4,
            LocalDate validFrom,
            LocalDate validUntil) {
    }

    public record RedemptionCommand(
            UUID companyId,
            UUID storeId,
            UUID documentId,
            UUID userId,
            UUID terminalId,
            UUID customerId,
            UUID memberId,
            UUID memberCategoryId,
            String code,
            BigDecimal pendingDocumentAmount) {
    }

    public record AuthorizedRedemptionCommand(
            UUID companyId,
            UUID storeId,
            UUID documentId,
            UUID userId,
            UUID terminalId,
            UUID customerId,
            UUID memberId,
            UUID memberCategoryId,
            UUID couponId,
            BigDecimal pendingDocumentAmount) {
    }

    public record AdminActionCommand(
            UUID companyId,
            UUID couponId,
            UUID userId,
            String reason) {
    }

    public record CouponView(
            UUID id,
            String codeLast4,
            PromotionalCouponStatus status,
            LocalDate validFrom,
            LocalDate validUntil,
            UUID promotionId,
            UUID generatedStoreId,
            UUID redeemedStoreId,
            UUID generatedDocumentId,
            UUID redeemedDocumentId,
            UUID customerId,
            UUID memberId,
            PromotionalCouponBenefitType benefitType,
            BigDecimal amount,
            BigDecimal percent,
            BigDecimal maximumDiscount,
            BigDecimal minimumAmount) {

        static CouponView from(PromotionalCoupon coupon) {
            return new CouponView(
                    coupon.id(),
                    coupon.codeLast4(),
                    coupon.status(),
                    coupon.validFrom(),
                    coupon.validUntil(),
                    coupon.promotionId(),
                    coupon.generatedStoreId(),
                    coupon.redeemedStoreId(),
                    coupon.generatedDocumentId(),
                    coupon.redeemedDocumentId(),
                    coupon.customerId(),
                    coupon.memberId(),
                    coupon.benefitType(),
                    coupon.amount(),
                    coupon.percent(),
                    coupon.maximumDiscount(),
                    coupon.minimumAmount());
        }
    }

    public record RedemptionResult(
            UUID redeemedDocumentId,
            UUID couponId,
            UUID promotionId,
            String codeLast4,
            BigDecimal redeemedAmount,
            CouponRejectReason rejectionReason,
            Optional<CreationResult> replacementCoupon) {

        static RedemptionResult rejected(UUID documentId, CouponRejectReason reason) {
            return new RedemptionResult(
                    documentId, null, null, null, BigDecimal.ZERO, reason, Optional.empty());
        }

        static RedemptionResult accepted(
                UUID documentId,
                UUID couponId,
                UUID promotionId,
                String codeLast4,
                BigDecimal redeemedAmount,
                Optional<CreationResult> replacementCoupon) {
            return new RedemptionResult(
                    documentId,
                    couponId,
                    promotionId,
                    codeLast4,
                    Money.euros(redeemedAmount),
                    null,
                    replacementCoupon);
        }
    }

    public record EvaluationResult(
            UUID couponId,
            UUID promotionId,
            String codeLast4,
            BigDecimal discountAmount,
            CouponRejectReason rejectionReason) {

        static EvaluationResult rejected(CouponRejectReason reason) {
            return new EvaluationResult(null, null, null, BigDecimal.ZERO, reason);
        }

        static EvaluationResult accepted(
                UUID couponId,
                UUID promotionId,
                String codeLast4,
                BigDecimal discountAmount) {
            return new EvaluationResult(
                    couponId,
                    promotionId,
                    codeLast4,
                    Money.euros(discountAmount),
                    null);
        }

        public boolean accepted() {
            return rejectionReason == null;
        }
    }
}
