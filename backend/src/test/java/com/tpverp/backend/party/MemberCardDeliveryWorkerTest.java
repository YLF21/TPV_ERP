package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import java.time.*;
import java.util.*;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class MemberCardDeliveryWorkerTest {
    @Mock MemberCardDeliveryRepository deliveries;
    @Mock MemberCardSender sender;
    @Mock PlatformTransactionManager transactions;
    private final Instant now = Instant.parse("2026-07-02T12:00:00Z");

    @Test void commitsClaimBeforeSendingAndMarksAccepted() {
        var delivery = delivery();
        prepare(delivery);
        doAnswer(invocation -> {
            // Recovery and claim have committed before the first SMTP call; result persistence has not run.
            verify(transactions, times(2)).commit(any());
            assertThat(delivery.getStatus()).isEqualTo(MemberCardDeliveryStatus.ENVIANDO);
            verify(deliveries, never()).findById(delivery.getId());
            return null;
        }).when(sender).send(delivery);
        assertThat(worker().runOnce()).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(MemberCardDeliveryStatus.ENVIADO);
        assertThat(delivery.getSentAt()).isEqualTo(now);
        verify(sender).send(delivery);
        verify(deliveries).findById(delivery.getId());
    }

    @Test void retriesConnectionRefusalWithDelay() {
        var delivery = delivery();
        prepare(delivery);
        doThrow(new MailSendException("private details", new ConnectException())).when(sender).send(delivery);
        assertThat(worker().runOnce()).isZero();
        assertThat(delivery.getStatus()).isEqualTo(MemberCardDeliveryStatus.PENDIENTE);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(now.plusSeconds(60));
        assertThat(delivery.getErrorMessage()).isEqualTo("message.smtp.retry_pending");
    }

    @Test void uncertainSmtpFailureIsNeverBlindlyRetried() {
        var delivery = delivery();
        prepare(delivery);
        doThrow(new MailSendException("connection lost after DATA")).when(sender).send(delivery);
        worker().runOnce();
        assertThat(delivery.getStatus()).isEqualTo(MemberCardDeliveryStatus.INCIERTO);
        assertThatThrownBy(delivery::retry).isInstanceOf(IllegalStateException.class);
    }

    @Test void staleClaimBecomesUncertainWithoutAnotherSend() {
        var delivery = delivery();
        delivery.claim(now.minusSeconds(601));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(deliveries.findByStatusAndClaimedAtBefore(MemberCardDeliveryStatus.ENVIANDO, now.minusSeconds(600)))
                .thenReturn(List.of(delivery));
        worker().runOnce();
        assertThat(delivery.getStatus()).isEqualTo(MemberCardDeliveryStatus.INCIERTO);
        verifyNoInteractions(sender);
    }

    @Test void retriesAreBounded() {
        var delivery = delivery();
        for (int i = 0; i < 4; i++) { delivery.claim(now); delivery.retryLater(now); }
        assertThat(delivery.getStatus()).isEqualTo(MemberCardDeliveryStatus.ERROR);
        assertThat(delivery.getAttemptCount()).isEqualTo(4);
    }

    @Test void unexpectedFailureIsUncertainAndProviderDetailsAreNotStored() {
        var delivery = delivery();
        prepare(delivery);
        doThrow(new IllegalStateException("password secret@example.org")).when(sender).send(delivery);
        worker().runOnce();
        assertThat(delivery.getStatus()).isEqualTo(MemberCardDeliveryStatus.INCIERTO);
        assertThat(delivery.getErrorMessage()).isEqualTo("message.smtp.delivery_uncertain");
    }

    @Test void retryingAlreadyPendingDeliveryDoesNotResetBackoffOrAttemptBudget() {
        var delivery = delivery();
        delivery.claim(now);
        delivery.retryLater(now);
        delivery.retry();
        delivery.retry();
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(now.plusSeconds(60));
        assertThat(delivery.getErrorMessage()).isEqualTo("message.smtp.retry_pending");
    }

    @Test void completedSendCannotOverwriteConcurrentUncertainResolution() {
        var delivery = delivery();
        prepare(delivery);
        doAnswer(invocation -> { delivery.markUncertain(); return null; }).when(sender).send(delivery);
        worker().runOnce();
        assertThat(delivery.getStatus()).isEqualTo(MemberCardDeliveryStatus.INCIERTO);
        assertThat(delivery.getSentAt()).isNull();
    }

    @Test void claimCommitFailureNeverCallsTransport() {
        var delivery = delivery();
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(deliveries.claimNext(now)).thenReturn(Optional.of(delivery));
        doNothing().doThrow(new org.springframework.transaction.TransactionSystemException("database unavailable"))
                .when(transactions).commit(any());
        assertThatThrownBy(() -> worker().runOnce()).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        verifyNoInteractions(sender);
    }

    @Test void resultPersistenceFailureLeavesClaimForUncertainRecoveryAndDoesNotResend() {
        var delivery = delivery();
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(deliveries.claimNext(now)).thenReturn(Optional.of(delivery));
        when(deliveries.findById(delivery.getId())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"));
        assertThatThrownBy(() -> worker().runOnce()).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        verify(sender, times(1)).send(delivery);
        assertThat(delivery.getStatus()).isEqualTo(MemberCardDeliveryStatus.ENVIANDO);
        assertThatThrownBy(delivery::retry).isInstanceOf(IllegalStateException.class);
    }

    @Test void successfulSendAndUnknownOutcomeCannotBeRetriedAsFailures() {
        var accepted = delivery();
        accepted.markSent(now);
        assertThatThrownBy(accepted::retry).hasMessage("message.member_card_delivery.retry_unsafe");
        var uncertain = delivery();
        uncertain.markUncertain();
        assertThatThrownBy(uncertain::retry).hasMessage("message.member_card_delivery.retry_unsafe");
    }

    private void prepare(MemberCardDelivery delivery) {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(deliveries.claimNext(now)).thenReturn(Optional.of(delivery), Optional.empty());
        when(deliveries.findById(delivery.getId())).thenReturn(Optional.of(delivery));
    }
    private MemberCardDeliveryWorker worker() {
        return new MemberCardDeliveryWorker(deliveries, sender, Clock.fixed(now, ZoneOffset.UTC), transactions);
    }
    private MemberCardDelivery delivery() {
        var customer = new Customer(PartyTestData.company(), "Cliente", DocumentType.NIF, "1",
                null, null, "cliente@example.com", null, CustomerRate.VENTA, java.math.BigDecimal.ZERO);
        return new MemberCardDelivery(new Member(customer, "M-001-000001", LocalDate.of(2026,7,2)),
                "cliente@example.com", "Tarjeta", "Codigo", MemberCardCodeFormat.QR, now);
    }
}
