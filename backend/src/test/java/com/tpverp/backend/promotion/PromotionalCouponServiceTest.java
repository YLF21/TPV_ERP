package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionalCouponServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-09T10:15:30Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Atlantic/Canary"));

    @Mock
    private PromotionalCouponRepository coupons;
    @Mock
    private PromotionalCouponAttemptRepository attempts;

    @Test
    void generationReturnsPlaintextOnceButPersistsOnlyHashAndLastFour() {
        when(coupons.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service("COUPON-SECRET-1234").generateAfterTicketConfirmation(amountCreation());

        var saved = ArgumentCaptor.forClass(PromotionalCoupon.class);
        verify(coupons).save(saved.capture());
        assertThat(created.code()).isEqualTo("COUPON-SECRET-1234");
        assertThat(saved.getValue().codeHash()).isNotEqualTo(created.code());
        assertThat(saved.getValue().codeHash()).hasSize(64);
        assertThat(saved.getValue().codeLast4()).isEqualTo("1234");
        assertThat(saved.getValue().amount()).isEqualByComparingTo("25.00");
        assertThat(saved.getValue().validFrom()).isEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(saved.getValue().validUntil()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void unknownRedemptionRegistersRejectedAttemptWithoutPlaintextCode() {
        var companyId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        when(coupons.findByEmpresaIdAndCodigoHash(companyId, service("MISSING-0000").hashForTest("MISSING-0000")))
                .thenReturn(Optional.empty());

        var result = service("unused").redeem(new PromotionalCouponService.RedemptionCommand(
                companyId,
                UUID.randomUUID(),
                documentId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                null,
                "MISSING-0000",
                new BigDecimal("10.00")));

        var attempt = ArgumentCaptor.forClass(PromotionalCouponAttempt.class);
        verify(attempts).save(attempt.capture());
        assertThat(result.redeemedAmount()).isZero();
        assertThat(result.rejectionReason()).isEqualTo(CouponRejectReason.NOT_FOUND);
        assertThat(attempt.getValue().reason()).isEqualTo(CouponRejectReason.NOT_FOUND);
        assertThat(attempt.getValue().documentId()).isEqualTo(documentId);
        assertThat(attempt.getValue().codeHash()).hasSize(64);
        assertThat(attempt.getValue().codeLast4()).isEqualTo("0000");
    }

    @Test
    void expiredCouponIsMarkedExpiredAndRejected() {
        var creation = amountCreation();
        var coupon = PromotionalCoupon.amount(
                creation.companyId(),
                creation.generatedStoreId(),
                creation.promotionId(),
                creation.generatedDocumentId(),
                service("EXPIRED-9999").hashForTest("EXPIRED-9999"),
                "9999",
                new BigDecimal("10.00"),
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30));
        when(coupons.findByEmpresaIdAndCodigoHash(creation.companyId(), coupon.codeHash()))
                .thenReturn(Optional.of(coupon));

        var result = service("unused").redeem(redemption(creation.companyId(), "EXPIRED-9999", "5.00"));

        verify(attempts).save(any(PromotionalCouponAttempt.class));
        assertThat(result.rejectionReason()).isEqualTo(CouponRejectReason.EXPIRED);
        assertThat(coupon.status()).isEqualTo(PromotionalCouponStatus.EXPIRED);
    }

    @Test
    void amountCouponOverDocumentTotalCreatesReplacementForRemainingBalance() {
        var creation = amountCreation();
        var originalCode = "BALANCE-1111";
        var replacementCode = "BALANCE-2222";
        var original = PromotionalCoupon.amount(
                creation.companyId(),
                creation.generatedStoreId(),
                creation.promotionId(),
                creation.generatedDocumentId(),
                service(originalCode).hashForTest(originalCode),
                "1111",
                new BigDecimal("25.00"),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31));
        when(coupons.findByEmpresaIdAndCodigoHash(creation.companyId(), original.codeHash()))
                .thenReturn(Optional.of(original));
        when(coupons.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service(replacementCode).redeem(redemption(creation.companyId(), originalCode, "10.00"));

        var saved = ArgumentCaptor.forClass(PromotionalCoupon.class);
        verify(coupons).save(saved.capture());
        assertThat(result.redeemedAmount()).isEqualByComparingTo("10.00");
        assertThat(result.rejectionReason()).isNull();
        assertThat(result.replacementCoupon()).isPresent();
        assertThat(result.replacementCoupon().get().code()).isEqualTo(replacementCode);
        assertThat(saved.getValue().amount()).isEqualByComparingTo("15.00");
        assertThat(saved.getValue().generatedDocumentId()).isEqualTo(result.redeemedDocumentId());
        assertThat(original.status()).isEqualTo(PromotionalCouponStatus.USED);
    }

    @Test
    void memberBoundCouponRejectsDifferentMemberAndRecordsAttemptContext() {
        var creation = amountCreation();
        var code = "MEMBER-3333";
        var memberId = UUID.randomUUID();
        var coupon = PromotionalCoupon.amount(
                creation.companyId(),
                creation.generatedStoreId(),
                creation.promotionId(),
                creation.generatedDocumentId(),
                service(code).hashForTest(code),
                "3333",
                null,
                memberId,
                new BigDecimal("10.00"),
                null,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                NOW);
        when(coupons.findByEmpresaIdAndCodigoHash(creation.companyId(), coupon.codeHash()))
                .thenReturn(Optional.of(coupon));

        var documentId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var result = service("unused").redeem(new PromotionalCouponService.RedemptionCommand(
                creation.companyId(),
                UUID.randomUUID(),
                documentId,
                userId,
                terminalId,
                null,
                UUID.randomUUID(),
                null,
                code,
                new BigDecimal("10.00")));

        var attempt = ArgumentCaptor.forClass(PromotionalCouponAttempt.class);
        verify(attempts).save(attempt.capture());
        assertThat(result.rejectionReason()).isEqualTo(CouponRejectReason.CUSTOMER_MISMATCH);
        assertThat(attempt.getValue().reason()).isEqualTo(CouponRejectReason.CUSTOMER_MISMATCH);
        assertThat(attempt.getValue().userId()).isEqualTo(userId);
        assertThat(attempt.getValue().terminalId()).isEqualTo(terminalId);
        assertThat(attempt.getValue().documentId()).isEqualTo(documentId);
        assertThat(coupon.status()).isEqualTo(PromotionalCouponStatus.ACTIVE);
    }

    @Test
    void listReturnsCouponViewsWithoutStoredHashOrPlaintextCode() {
        var creation = amountCreation();
        var coupon = PromotionalCoupon.amount(
                creation.companyId(),
                creation.generatedStoreId(),
                creation.promotionId(),
                creation.generatedDocumentId(),
                service("LIST-4444").hashForTest("LIST-4444"),
                "4444",
                new BigDecimal("10.00"),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31));
        when(coupons.findByEmpresaId(creation.companyId())).thenReturn(List.of(coupon));

        var views = service("unused").list(creation.companyId(), null, "4444");

        assertThat(views).singleElement()
                .satisfies(view -> {
                    assertThat(view.id()).isEqualTo(coupon.id());
                    assertThat(view.codeLast4()).isEqualTo("4444");
                    assertThat(view.status()).isEqualTo(PromotionalCouponStatus.ACTIVE);
                    assertThat(view.amount()).isEqualByComparingTo("10.00");
                });
        assertThat(PromotionalCouponService.CouponView.class.getRecordComponents())
                .extracting(component -> component.getName().toLowerCase())
                .noneMatch(name -> name.contains("hash") || name.equals("code") || name.contains("plaintext"));
    }

    @Test
    void cancelAndReactivateUseDomainRules() {
        var creation = amountCreation();
        var coupon = PromotionalCoupon.amount(
                creation.companyId(),
                creation.generatedStoreId(),
                creation.promotionId(),
                creation.generatedDocumentId(),
                service("ADMIN-5555").hashForTest("ADMIN-5555"),
                "5555",
                new BigDecimal("10.00"),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31));
        var userId = UUID.randomUUID();
        when(coupons.findByIdAndEmpresaId(coupon.id(), creation.companyId()))
                .thenReturn(Optional.of(coupon));

        var cancelled = service("unused").cancel(new PromotionalCouponService.AdminActionCommand(
                creation.companyId(), coupon.id(), userId, "Error de emision"));
        var reactivated = service("unused").reactivate(new PromotionalCouponService.AdminActionCommand(
                creation.companyId(), coupon.id(), userId, "Cliente recupera cupon"));

        assertThat(cancelled.status()).isEqualTo(PromotionalCouponStatus.CANCELLED);
        assertThat(reactivated.status()).isEqualTo(PromotionalCouponStatus.ACTIVE);
    }

    private PromotionalCouponService service(String code) {
        return new PromotionalCouponService(coupons, attempts, () -> code, CLOCK);
    }

    private PromotionalCouponService.CreationCommand amountCreation() {
        return new PromotionalCouponService.CreationCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                PromotionCustomerSegment.ALL,
                null,
                PromotionalCouponBenefitType.AMOUNT,
                new BigDecimal("25.00"),
                null,
                null,
                null,
                Instant.parse("2026-07-10T09:00:00Z"),
                Instant.parse("2026-07-31T22:59:59Z"));
    }

    private PromotionalCouponService.RedemptionCommand redemption(UUID companyId, String code, String total) {
        return new PromotionalCouponService.RedemptionCommand(
                companyId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                null,
                code,
                new BigDecimal(total));
    }
}
