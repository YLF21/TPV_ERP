package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoucherServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-17T10:00:00Z");

    @Mock
    private VoucherRepository vouchers;
    @Mock
    private CurrentOrganization organization;
    @Mock
    private VoucherEventRepository voucherEvents;

    private VoucherService service;
    private Store store;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        store = new Store(
                new Company("B00000000", "Company", address),
                "Store", address, "hash", "Atlantic/Canary", "EUR", "es-ES");
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        lenient().when(organization.currentStore()).thenReturn(store);
        service = new VoucherService(
                vouchers, voucherEvents, organization,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void negativeTicketIssuesVoucherAndPartialUseCreatesReplacementWithLineage() {
        when(vouchers.save(any())).thenAnswer(call -> call.getArgument(0));
        when(vouchers.findAllByTiendaIdOrderByCreatedAtDesc(store.getId()))
                .thenReturn(List.of());
        var sourceTicket = ticket("001-260617-00001", "-100.00");

        var issued = service.issueFromNegativeTicket(sourceTicket);
        when(vouchers.findLockedByTiendaIdAndCode(store.getId(), issued.code()))
                .thenReturn(Optional.of(issued));

        var purchase = ticket("001-260617-00002", "20.00");
        var result = service.consume(
                issued.code(), new BigDecimal("20.00"), purchase);

        assertThat(issued.balance()).isZero();
        assertThat(issued.status()).isEqualTo(VoucherStatus.CONSUMED);
        assertThat(result.consumedAmount()).isEqualByComparingTo("20.00");
        assertThat(result.replacement()).isPresent().get().satisfies(replacement -> {
            assertThat(replacement.code()).isNotEqualTo(issued.code());
            assertThat(replacement.balance()).isEqualByComparingTo("80.00");
            assertThat(replacement.status()).isEqualTo(VoucherStatus.ACTIVE);
            assertThat(replacement.originTickets())
                    .containsExactly("001-260617-00001", purchase.getNumero());
        });
        verify(vouchers).findLockedByTiendaIdAndCode(store.getId(), issued.code());
    }

    @Test
    void cancellingThePurchaseRestoresTheOriginalVoucher() {
        var voucher = new Voucher(
                store.getId(), "VKEEP", new BigDecimal("100.00"),
                List.of("001-260617-00001"), NOW);
        var purchase = ticket("001-260617-00002", "20.00");
        purchase.addPayment(new DocumentPayment(
                purchase,
                new PaymentMethod(store.getEmpresa().getId(), "VALE", true),
                1, new BigDecimal("20.00"), true,
                null, null, voucher.code(), NOW));
        voucher.closeForReplacement();
        when(vouchers.findAllByOriginTicket(store.getId(), purchase.getNumero()))
                .thenReturn(List.of());
        when(vouchers.findLockedByTiendaIdAndCode(store.getId(), voucher.code()))
                .thenReturn(Optional.of(voucher));
        when(vouchers.findAllLockedByOriginTicket(store.getId(), purchase.getNumero()))
                .thenReturn(List.of());

        var result = service.compensateCancellation(purchase, UUID.randomUUID());

        assertThat(voucher.balance()).isEqualByComparingTo("100.00");
        assertThat(voucher.status()).isEqualTo(VoucherStatus.ACTIVE);
        assertThat(result.restored()).containsExactly(voucher);
        verify(voucherEvents).save(any(VoucherEvent.class));
    }

    @Test
    void cancellingALegacyPartialUseRestoresTheOriginalAndInvalidatesItsReplacement() {
        var original = new Voucher(
                store.getId(), "VLEGACY", new BigDecimal("100.00"),
                List.of("001-260617-00001"), NOW);
        original.consume(new BigDecimal("100.00"));
        var purchase = ticket("001-260617-00002", "20.00");
        purchase.addPayment(new DocumentPayment(
                purchase,
                new PaymentMethod(store.getEmpresa().getId(), "VALE", true),
                1, new BigDecimal("20.00"), true,
                null, null, original.code(), NOW));
        var replacement = new Voucher(
                store.getId(), "VLEGACYNEW", new BigDecimal("80.00"),
                List.of("001-260617-00001", purchase.getNumero()), NOW);
        when(vouchers.findAllByOriginTicket(store.getId(), purchase.getNumero()))
                .thenReturn(List.of(replacement));
        when(vouchers.findLockedByTiendaIdAndCode(store.getId(), original.code()))
                .thenReturn(Optional.of(original));
        when(vouchers.findAllLockedByOriginTicket(store.getId(), purchase.getNumero()))
                .thenReturn(List.of(replacement));

        service.compensateCancellation(purchase, UUID.randomUUID());

        assertThat(original.balance()).isEqualByComparingTo("100.00");
        assertThat(original.status()).isEqualTo(VoucherStatus.ACTIVE);
        assertThat(replacement.balance()).isZero();
        assertThat(replacement.status()).isEqualTo(VoucherStatus.INVALIDATED);
    }

    @Test
    void sameNegativeTicketCannotIssueVoucherTwice() {
        var sourceTicket = ticket("001-260617-00001", "-100.00");
        var existing = new Voucher(
                store.getId(), "VEXISTING", new BigDecimal("100.00"),
                List.of(sourceTicket.getNumero()), NOW);
        when(vouchers.findAllByTiendaIdOrderByCreatedAtDesc(store.getId()))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.issueFromNegativeTicket(sourceTicket))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya tiene vale");
    }

    @Test
    void voucherCanOnlyBeConsumedWithNumberedPurchaseTicket() {
        var voucher = new Voucher(
                store.getId(), "VABC123", new BigDecimal("100.00"),
                List.of("001-260617-00001"), NOW);
        var draftTicket = draftTicket("20.00");

        assertThatThrownBy(() -> service.consume(
                voucher.code(), new BigDecimal("20.00"), draftTicket))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticket numerado");
    }

    @Test
    void detectsVoucherImpactFromGeneratedOrUsedVoucher() {
        var generated = ticket("001-260617-00001", "-100.00");
        when(vouchers.findAllByTiendaIdOrderByCreatedAtDesc(store.getId()))
                .thenReturn(List.of(new Voucher(
                        store.getId(), "VGENERATED", new BigDecimal("100.00"),
                        List.of(generated.getNumero()), NOW)));

        assertThat(service.hasVoucherImpact(generated)).isTrue();

        var paidWithVoucher = ticket("001-260617-00002", "20.00");
        paidWithVoucher.addPayment(new DocumentPayment(
                paidWithVoucher,
                new PaymentMethod(store.getEmpresa().getId(), "VALE", true),
                1,
                new BigDecimal("20.00"),
                true,
                null,
                null,
                "VGENERATED",
                NOW));

        assertThat(service.hasVoucherImpact(paidWithVoucher)).isTrue();
    }

    @Test
    void exactConsumptionRejectsInsufficientBalanceUnderTheStoreLock() {
        var voucher = new Voucher(
                store.getId(), "VLOCKED", new BigDecimal("10.00"),
                List.of("001-260617-00001"), NOW);
        when(vouchers.findLockedByTiendaIdAndCode(store.getId(), "VLOCKED"))
                .thenReturn(Optional.of(voucher));

        assertThatThrownBy(() -> service.consumeExact(
                "VLOCKED", new BigDecimal("20.00"), ticket("001-260617-00002", "20.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("saldo de vale insuficiente");

        assertThat(voucher.balance()).isEqualByComparingTo("10.00");
    }

    @Test
    void findsVoucherByCodeWithoutConsumingIt() {
        var voucher = new Voucher(
                store.getId(), "VABC123", new BigDecimal("25.00"),
                List.of("001-260617-00001"), NOW);
        when(vouchers.findByTiendaIdAndCodeIgnoreCase(store.getId(), "vabc123"))
                .thenReturn(Optional.of(voucher));

        var found = service.findByCode("  vabc123  ");

        assertThat(found).contains(voucher);
        assertThat(found.orElseThrow().balance()).isEqualByComparingTo("25.00");
        assertThat(found.orElseThrow().status()).isEqualTo(VoucherStatus.ACTIVE);
        verify(vouchers).findByTiendaIdAndCodeIgnoreCase(store.getId(), "vabc123");
    }

    @Test
    void blankVoucherCodeDoesNotQueryTheRepository() {
        assertThat(service.findByCode("   ")).isEmpty();
    }

    private CommercialDocument draftTicket(String total) {
        var document = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 6, 17), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, 1,
                "P-1", "Producto", "VENTA", new BigDecimal(total),
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21")));
        return document;
    }

    private CommercialDocument ticket(String number, String total) {
        var document = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 6, 17), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, new BigDecimal(total).signum(),
                "P-1", "Producto", "VENTA", new BigDecimal(total).abs(),
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21")));
        document.confirm(number, UUID.randomUUID(), NOW, false);
        return document;
    }
}
