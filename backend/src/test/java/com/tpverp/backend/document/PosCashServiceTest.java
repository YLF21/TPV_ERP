package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.StoreTax;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.promotion.AuthoritativePromotionPricing;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class PosCashServiceTest {

    @Test
    void authoritativeQuoteReconcilesMemberManualPromotionCouponTaxAndRoundingPerProduct() {
        var storeId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getSalePrice()).thenReturn(new BigDecimal("12.00"));
        when(product.getMemberPrice()).thenReturn(new BigDecimal("10.00"));
        var ticket = new CommercialDocument(
                storeId, UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 21), UUID.randomUUID(), BigDecimal.ZERO);
        ticket.setParties(customerId, null, null);
        ticket.addLine(new DocumentLine(
                ticket, productId, 1, new BigDecimal("2.000"), "SKU-1", "Cafe socio",
                "MEMBER", new BigDecimal("10.00"), new BigDecimal("10.00"),
                true, "IVA", new BigDecimal("21.00")));
        ticket.addLine(DocumentLine.special(
                ticket, 2, "PROMOCION", new BigDecimal("-2.00"), true,
                "IVA", new BigDecimal("21.00"), UUID.randomUUID(), UUID.randomUUID(), null));
        ticket.addLine(DocumentLine.special(
                ticket, 3, "CUPON ****1234", new BigDecimal("-1.00"), true,
                "IVA", new BigDecimal("21.00"), UUID.randomUUID(), null, UUID.randomUUID()));
        var request = new PosCashController.SaleRequest(
                customerId,
                List.of(new PosCashController.LineRequest(
                        productId, new BigDecimal("2.000"), new BigDecimal("5.00"))),
                null,
                "CUPON-1234");
        var customer = new AuthoritativePromotionPricing.CustomerContext(
                customerId, UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"));

        var quote = PosCashService.Quote.from(ticket, request, Map.of(productId, product), customer);

        assertThat(quote.pricingVersion()).isEqualTo(1);
        assertThat(quote.total()).isEqualByComparingTo("15.00");
        assertThat(quote.discountTotal()).isEqualByComparingTo("5.00");
        assertThat(quote.lineBreakdown()).singleElement().satisfies(line -> {
            assertThat(line.lineId()).isEqualTo("product:" + productId + ":1");
            assertThat(line.normalUnitPrice()).isEqualByComparingTo("12.00");
            assertThat(line.memberUnitPrice()).isEqualByComparingTo("10.00");
            assertThat(line.baseUnitPrice()).isEqualByComparingTo("10.00");
            assertThat(line.priceSource()).isEqualTo("MEMBER");
            assertThat(line.memberPriceSaving()).isEqualByComparingTo("4.00");
            assertThat(line.memberDiscountPercent()).isEqualByComparingTo("10.00");
            assertThat(line.memberDiscount()).isEqualByComparingTo("2.00");
            assertThat(line.manualDiscount()).isZero();
            assertThat(line.promotionDiscount()).isEqualByComparingTo("2.00");
            assertThat(line.couponDiscount()).isEqualByComparingTo("1.00");
            assertThat(line.taxBase()).isEqualByComparingTo("12.40");
            assertThat(line.tax()).isEqualByComparingTo("2.60");
            assertThat(line.baseSubtotal()).isEqualByComparingTo("20.00");
            assertThat(line.roundingAdjustment()).isZero();
            assertThat(line.finalSubtotal()).isEqualByComparingTo("15.00");
        });
    }

    @Test
    void cashIdempotencyHashKeepsLegacyCanonicalWhenCouponIsAbsent() throws Exception {
        var productId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var request = new PosCashController.CashRequest(
                UUID.randomUUID(),
                new PosCashController.SaleRequest(customerId, List.of(
                        new PosCashController.LineRequest(
                                productId, new BigDecimal("2.000"), new BigDecimal("5.00")))),
                new BigDecimal("30.00"),
                new BigDecimal("20.00"));
        var canonical = "v1|" + customerId + "|30.00|20.00|" + productId + ":2:5";
        var expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));

        assertThat(PosCashService.requestHash(request)).isEqualTo(expected);
    }

    @Test
    void cashIdempotencyHashSeparatesCouponCodeFromLegacyRequests() {
        var line = new PosCashController.LineRequest(
                UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO);
        var checkoutId = UUID.randomUUID();
        var legacy = new PosCashController.CashRequest(
                checkoutId, new PosCashController.SaleRequest(null, List.of(line)),
                BigDecimal.TEN, BigDecimal.ONE);
        var coupon = new PosCashController.CashRequest(
                checkoutId, new PosCashController.SaleRequest(
                null, List.of(line), null, "PROMO-1234"),
                BigDecimal.TEN, BigDecimal.ONE);

        assertThat(PosCashService.requestHash(coupon))
                .isNotEqualTo(PosCashService.requestHash(legacy));
    }

    @Test
    void chargeReturnsSnapshotOfTheConfirmedDocumentCreatedByDocumentService() {
        var documents = mock(DocumentService.class);
        var products = mock(ProductRepository.class);
        var taxes = mock(StoreTaxRepository.class);
        var warehouses = mock(WarehouseRepository.class);
        var paymentMethods = mock(PaymentMethodRepository.class);
        var organization = mock(CurrentOrganization.class);
        var checkouts = mock(PosCashCheckoutRepository.class);
        var snapshots = new PosCashTicketSnapshot();
        var currentTerminal = mock(CurrentTerminal.class);
        var authentication = mock(Authentication.class);
        var user = mock(UserAccount.class);
        var store = mock(Store.class);
        var company = mock(Company.class);
        var product = mock(Product.class);
        var storeId = UUID.randomUUID();
        var companyId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var warehouse = Warehouse.general(storeId);
        var tax = new StoreTax(storeId, BigDecimal.valueOf(21), true);
        var cash = new PaymentMethod(companyId, "EFECTIVO", true);
        var issuedAt = Instant.parse("2026-07-15T10:15:30Z");
        var ticket = confirmedTicket(storeId, warehouse.getId(), productId, cash, issuedAt);
        var quoted = mock(CommercialDocument.class);
        when(quoted.getTotal()).thenReturn(new BigDecimal("7.00"));
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Europe/Madrid");
        when(company.getId()).thenReturn(companyId);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        when(authentication.getPrincipal()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        when(currentTerminal.terminalId(authentication)).thenReturn(terminalId);
        when(checkouts.reserve(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(warehouses.findByStoreIdAndPredeterminadoTrue(storeId))
                .thenReturn(Optional.of(warehouse));
        when(product.getId()).thenReturn(productId);
        when(product.getStoreId()).thenReturn(storeId);
        when(product.getTaxId()).thenReturn(tax.getId());
        when(product.getCode()).thenReturn("REQUEST-CODE");
        when(product.getName()).thenReturn("Request name");
        when(product.getSalePrice()).thenReturn(new BigDecimal("99.00"));
        when(product.isTaxesIncluded()).thenReturn(true);
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(taxes.findById(tax.getId())).thenReturn(Optional.of(tax));
        when(paymentMethods.findByEmpresaIdAndNombreAndActivoTrue(companyId, "EFECTIVO"))
                .thenReturn(Optional.of(cash));
        when(documents.quoteTicket(any(DocumentCommand.class), any())).thenReturn(quoted);
        when(documents.createTicket(any(DocumentCommand.class), anyList(), any()))
                .thenReturn(ticket);
        var customerId = UUID.randomUUID();
        var service = new PosCashService(
                documents, products, taxes, warehouses, paymentMethods, organization,
                checkouts, snapshots, currentTerminal);
        var sale = new PosCashController.SaleRequest(
                customerId, List.of(new PosCashController.LineRequest(
                        productId, BigDecimal.valueOf(2), BigDecimal.ZERO)));

        var result = service.charge(new PosCashController.CashRequest(
                UUID.randomUUID(), sale, BigDecimal.TEN, new BigDecimal("7.00")),
                authentication);

        assertThat(result.printTicket()).isNotNull();
        assertThat(result.printTicket().documentId()).isEqualTo(ticket.getId());
        assertThat(result.printTicket().documentNumber()).isEqualTo("001-260715-000001");
        assertThat(result.printTicket().issuedAt()).isEqualTo(issuedAt);
        assertThat(result.printTicket().lines()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("Authoritative Cafe");
            assertThat(line.quantity()).isEqualByComparingTo("2");
            assertThat(line.price()).isEqualByComparingTo("3.50");
            assertThat(line.total()).isEqualByComparingTo("7.00");
        });
        assertThat(result.printTicket().payments()).singleElement().satisfies(payment -> {
            assertThat(payment.method()).isEqualTo("EFECTIVO");
            assertThat(payment.amount()).isEqualByComparingTo("7.00");
        });
        assertThat(result.printTicket().total()).isEqualByComparingTo("7.00");
        verify(documents).createTicket(any(DocumentCommand.class), anyList(),
                org.mockito.ArgumentMatchers.same(authentication));
        var command = org.mockito.ArgumentCaptor.forClass(DocumentCommand.class);
        verify(documents).quoteTicket(command.capture(),
                org.mockito.ArgumentMatchers.same(authentication));
        assertThat(command.getValue().lineas()).singleElement().satisfies(line -> {
            assertThat(line.precioUnitario()).isEqualByComparingTo("99.00");
            assertThat(line.descuento()).isZero();
        });
        verify(checkouts).save(any(PosCashCheckout.class));
    }

    @Test
    void identicalReplayReturnsPersistedConfirmedResultWithoutCreatingAnotherTicket() {
        var fixture = replayFixture("hash-placeholder");
        var request = fixture.request();
        var expectedHash = PosCashService.requestHash(request);
        var snapshot = new TicketPrintView(
                UUID.randomUUID(), "T-REPLAY", Instant.parse("2026-07-15T10:15:30Z"),
                List.of(), List.of(), new BigDecimal("7.00"));
        var checkout = PosCashCheckout.reserve(
                UUID.randomUUID(), request.checkoutId(), fixture.companyId(), fixture.storeId(),
                fixture.terminalId(), fixture.userId(), expectedHash, Instant.now());
        checkout.complete(UUID.randomUUID(), "T-REPLAY", new BigDecimal("7.00"),
                new BigDecimal("10.00"), new BigDecimal("3.00"),
                fixture.snapshots().serialize(snapshot), Instant.now());
        when(fixture.checkouts().reserve(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(fixture.checkouts().findScopedForUpdate(
                request.checkoutId(), fixture.companyId(), fixture.storeId(),
                fixture.terminalId(), fixture.userId())).thenReturn(Optional.of(checkout));

        var result = fixture.service().charge(request, fixture.authentication());

        assertThat(result.number()).isEqualTo("T-REPLAY");
        assertThat(result.printTicket()).isEqualTo(snapshot);
        verify(fixture.documents(), org.mockito.Mockito.never())
                .createTicket(any(), anyList(), any());
    }

    @Test
    void replayWithDifferentEconomicRequestIsRejected() {
        var fixture = replayFixture("different-request-hash");
        var request = fixture.request();
        var checkout = PosCashCheckout.reserve(
                UUID.randomUUID(), request.checkoutId(), fixture.companyId(), fixture.storeId(),
                fixture.terminalId(), fixture.userId(), "0".repeat(64), Instant.now());
        when(fixture.checkouts().reserve(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(fixture.checkouts().findScopedForUpdate(
                request.checkoutId(), fixture.companyId(), fixture.storeId(),
                fixture.terminalId(), fixture.userId())).thenReturn(Optional.of(checkout));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> fixture.service().charge(request, fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cash_checkout_idempotency_conflict");
        verify(fixture.documents(), org.mockito.Mockito.never())
                .createTicket(any(), anyList(), any());
    }

    private static ReplayFixture replayFixture(String ignored) {
        var documents = mock(DocumentService.class);
        var products = mock(ProductRepository.class);
        var taxes = mock(StoreTaxRepository.class);
        var warehouses = mock(WarehouseRepository.class);
        var methods = mock(PaymentMethodRepository.class);
        var organization = mock(CurrentOrganization.class);
        var checkouts = mock(PosCashCheckoutRepository.class);
        var snapshots = new PosCashTicketSnapshot();
        var currentTerminal = mock(CurrentTerminal.class);
        var authentication = mock(Authentication.class);
        var user = mock(UserAccount.class);
        var store = mock(Store.class);
        var company = mock(Company.class);
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        when(authentication.getPrincipal()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        when(currentTerminal.terminalId(authentication)).thenReturn(terminalId);
        var service = new PosCashService(documents, products, taxes, warehouses, methods,
                organization, checkouts, snapshots, currentTerminal);
        var request = new PosCashController.CashRequest(
                UUID.randomUUID(), new PosCashController.SaleRequest(null, List.of(
                        new PosCashController.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO))),
                new BigDecimal("10.00"), new BigDecimal("7.00"));
        return new ReplayFixture(service, documents, checkouts, snapshots, authentication,
                request, companyId, storeId, terminalId, userId);
    }

    private record ReplayFixture(
            PosCashService service, DocumentService documents, PosCashCheckoutRepository checkouts,
            PosCashTicketSnapshot snapshots, Authentication authentication,
            PosCashController.CashRequest request, UUID companyId, UUID storeId,
            UUID terminalId, UUID userId) {}

    private static CommercialDocument confirmedTicket(
            UUID storeId,
            UUID warehouseId,
            UUID productId,
            PaymentMethod cash,
            Instant issuedAt) {
        var ticket = new CommercialDocument(
                storeId, warehouseId, CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 15), UUID.randomUUID(), BigDecimal.ZERO);
        ticket.addLine(new DocumentLine(
                ticket, productId, 1, BigDecimal.valueOf(2), "AUTHORITATIVE-CODE",
                "Authoritative Cafe", null, new BigDecimal("3.50"), BigDecimal.ZERO,
                true, "IVA", BigDecimal.valueOf(21)));
        ticket.confirm("001-260715-000001", UUID.randomUUID(), issuedAt, false);
        ticket.addPayment(new DocumentPayment(
                ticket, cash, 1, new BigDecimal("7.00"), true,
                BigDecimal.TEN, new BigDecimal("3.00"), issuedAt));
        return ticket;
    }
}
