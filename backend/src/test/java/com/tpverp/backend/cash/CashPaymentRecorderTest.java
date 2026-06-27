package com.tpverp.backend.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.document.DocumentPayment;
import com.tpverp.backend.document.PaymentMethod;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CashPaymentRecorderTest {

    private static final Instant NOW = Instant.parse("2026-06-26T10:15:00Z");

    @Test
    void requireOpenSessionRejectsWhenNoOpenCashSession() {
        var fixture = fixture();
        when(fixture.sessions.findByTerminalIdAndStatus(
                fixture.terminalId, CashSessionStatus.ABIERTA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.recorder.requireOpenSession(fixture.terminalId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sesion de caja abierta");
    }

    @Test
    void cashPaymentCreatesCashMovement() {
        var fixture = fixture();
        var session = openSession(fixture);
        var document = document(fixture);
        document.addPayment(payment(document, cashMethod(fixture), 1, "10.00", true));
        when(fixture.sessions.findByTerminalIdAndStatus(
                fixture.terminalId, CashSessionStatus.ABIERTA)).thenReturn(Optional.of(session));
        when(fixture.movements.existsByDocumentoPagoId(document.getPagos().getFirst().getId()))
                .thenReturn(false);

        fixture.recorder.recordDocumentPayments(fixture.terminalId, document);

        var saved = captureMovement(fixture);
        assertThat(saved.getType()).isEqualTo(CashMovementType.COBRO_EFECTIVO);
        assertThat(saved.getAmount()).isEqualByComparingTo("10.00");
        assertThat(saved.getStoreId()).isEqualTo(fixture.store.getId());
        assertThat(saved.getTerminalId()).isEqualTo(fixture.terminalId);
        assertThat(saved.getSessionId()).isEqualTo(session.getId());
        assertThat(saved.getUserId()).isEqualTo(fixture.user.getId());
        assertThat(saved.getDocumentId()).isEqualTo(document.getId());
        assertThat(saved.getDocumentoPagoId()).isEqualTo(document.getPagos().getFirst().getId());
    }

    @Test
    void mixedPaymentRecordsOnlyCashAmount() {
        var fixture = fixture();
        var document = document(fixture);
        document.addPayment(payment(document, cashMethod(fixture), 1, "4.00", true));
        document.addPayment(payment(document, new PaymentMethod(
                fixture.store.getEmpresa().getId(), "TARJETA", true), 2, "6.00", false));
        when(fixture.sessions.findByTerminalIdAndStatus(
                fixture.terminalId, CashSessionStatus.ABIERTA)).thenReturn(Optional.of(openSession(fixture)));
        when(fixture.movements.existsByDocumentoPagoId(document.getPagos().getFirst().getId()))
                .thenReturn(false);

        fixture.recorder.recordDocumentPayments(fixture.terminalId, document);

        var saved = captureMovement(fixture);
        assertThat(saved.getAmount()).isEqualByComparingTo("4.00");
        verify(fixture.movements).save(any(CashMovement.class));
    }

    @Test
    void paymentMethodThatDoesNotOpenDrawerDoesNotRequireCashSession() {
        var fixture = fixture();
        var document = document(fixture);
        document.addPayment(payment(document, new PaymentMethod(
                fixture.store.getEmpresa().getId(), "TARJETA", true), 1, "10.00", true));

        fixture.recorder.recordDocumentPayments(fixture.terminalId, document);

        verify(fixture.sessions, never()).findByTerminalIdAndStatus(any(), any());
        verify(fixture.movements, never()).save(any(CashMovement.class));
    }

    @Test
    void configuredDrawerMethodCreatesCashMovement() {
        var fixture = fixture();
        var session = openSession(fixture);
        var document = document(fixture);
        var custom = new PaymentMethod(fixture.store.getEmpresa().getId(), "OTRO", true, false, true);
        document.addPayment(payment(document, custom, 1, "10.00", true));
        when(fixture.sessions.findByTerminalIdAndStatus(
                fixture.terminalId, CashSessionStatus.ABIERTA)).thenReturn(Optional.of(session));
        when(fixture.movements.existsByDocumentoPagoId(document.getPagos().getFirst().getId()))
                .thenReturn(false);

        fixture.recorder.recordDocumentPayments(fixture.terminalId, document);

        assertThat(captureMovement(fixture).getAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void duplicatePaymentIsNotRecordedTwice() {
        var fixture = fixture();
        var document = document(fixture);
        document.addPayment(payment(document, cashMethod(fixture), 1, "10.00", true));
        when(fixture.sessions.findByTerminalIdAndStatus(
                fixture.terminalId, CashSessionStatus.ABIERTA)).thenReturn(Optional.of(openSession(fixture)));
        when(fixture.movements.existsByDocumentoPagoId(document.getPagos().getFirst().getId()))
                .thenReturn(true);

        fixture.recorder.recordDocumentPayments(fixture.terminalId, document);

        verify(fixture.movements, never()).save(any(CashMovement.class));
    }

    @Test
    void negativeDocumentPaymentRowsAreRejectedBeforeRecorderCanCreateRefundMovement() {
        var fixture = fixture();
        var document = document(fixture);

        assertThatThrownBy(() -> payment(
                document, cashMethod(fixture), 1, "-10.00", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("importe no puede ser negativo");
    }

    private static CashMovement captureMovement(Fixture fixture) {
        var captor = ArgumentCaptor.forClass(CashMovement.class);
        verify(fixture.movements).save(captor.capture());
        return captor.getValue();
    }

    private static CashSession openSession(Fixture fixture) {
        return CashSession.open(
                fixture.store.getId(), fixture.terminalId, fixture.user.getId(),
                NOW.minusSeconds(60), new BigDecimal("50.00"));
    }

    private static CommercialDocument document(Fixture fixture) {
        var document = new CommercialDocument(
                fixture.store.getId(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 6, 26), fixture.user.getId(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, 1, "P-1", "Producto", "VENTA",
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21")));
        document.confirm("001-260626-00001", fixture.user.getId(), NOW, false);
        return document;
    }

    private static DocumentPayment payment(
            CommercialDocument document, PaymentMethod method, int position, String amount, boolean principal) {
        return new DocumentPayment(
                document, method, position, new BigDecimal(amount), principal,
                null, null, NOW);
    }

    private static PaymentMethod cashMethod(Fixture fixture) {
        return new PaymentMethod(fixture.store.getEmpresa().getId(), "EFECTIVO", true);
    }

    private static Fixture fixture() {
        var store = store();
        var user = new UserAccount(store, "SELLER", "hash", new Role(store, "SELLER"));
        var terminalId = UUID.randomUUID();
        var sessions = mock(CashSessionRepository.class);
        var movements = mock(CashMovementRepository.class);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(any())).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "token"));
        var recorder = new CashPaymentRecorder(
                sessions, movements, organization, Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(recorder, sessions, movements, organization, store, user, terminalId);
    }

    private static Store store() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        return new Store(
                new Company("B00000000", "Company", address),
                "001", "Store", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }

    private record Fixture(
            CashPaymentRecorder recorder,
            CashSessionRepository sessions,
            CashMovementRepository movements,
            CurrentOrganization organization,
            Store store,
            UserAccount user,
            UUID terminalId) {
    }
}
