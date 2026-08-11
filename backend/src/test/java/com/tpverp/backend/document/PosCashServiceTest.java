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
import com.tpverp.backend.security.sales.SaleOperationCode;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class PosCashServiceTest {

    @Test
    void replayConfirmationRequiresTheAuthoritativeFiscalFingerprint() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 7), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, BigDecimal.ONE, "A", "A", "VENTA",
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21.00")));
        var service = new PosCashService(
                mock(DocumentService.class), mock(ProductRepository.class),
                mock(StoreTaxRepository.class), mock(WarehouseRepository.class),
                mock(PaymentMethodRepository.class), mock(CurrentOrganization.class),
                mock(PosCashCheckoutRepository.class), new PosCashTicketSnapshot(),
                mock(CurrentTerminal.class));
        var importRequest = new PosCashController.PreviousTicketImportRequest(
                UUID.randomUUID(), "source", Map.of());
        var missing = new PosCashController.SaleRequest(
                null, List.of(), null, null, null, null, Map.of(), importRequest, null);
        var stale = new PosCashController.SaleRequest(
                null, List.of(), null, null, null, null, Map.of(), importRequest,
                "stale-quote");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.validateQuoteFingerprint(missing, document))
                .hasMessage("message.document.previous_ticket_quote_required");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.validateQuoteFingerprint(stale, document))
                .hasMessage("message.document.previous_ticket_quote_changed");
    }

    @Test
    void materializesTheCurrentGlobalDiscountWithoutChangingItsFiscalTotals() {
        var storeId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var current = new CommercialDocument(
                storeId, warehouseId, CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 7), userId, new BigDecimal("33.33"));
        current.addLine(new DocumentLine(
                current, UUID.randomUUID(), 1, BigDecimal.ONE, "A", "A", "VENTA",
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21.00")));
        current.addLine(new DocumentLine(
                current, UUID.randomUUID(), 2, BigDecimal.ONE, "B", "B", "VENTA",
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("10.00")));

        var combined = new CommercialDocument(
                storeId, warehouseId, CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 7), userId, BigDecimal.ZERO);
        current.getLineas().stream()
                .map(DocumentLineCommand::from)
                .forEach(line -> combined.addLine(line.toEntity(combined)));

        PosCashService.materializeCurrentGlobalDiscount(current, combined);

        assertThat(combined.getDescuentoGlobal()).isZero();
        assertThat(combined.getBaseTotal()).isEqualByComparingTo(current.getBaseTotal());
        assertThat(combined.getImpuestoTotal())
                .isEqualByComparingTo(current.getImpuestoTotal());
        assertThat(combined.getTotal()).isEqualByComparingTo(current.getTotal());
        assertThat(combined.getLineas())
                .filteredOn(line -> line.getLineType() == DocumentLineType.MANUAL_DISCOUNT)
                .hasSize(2);
    }

    @Test
    void combinesExactHistoricalPromotionCouponAndGlobalDiscountWithACurrentLine() {
        var documents = mock(DocumentService.class);
        var organization = mock(CurrentOrganization.class);
        var currentTerminal = mock(CurrentTerminal.class);
        var store = mock(Store.class);
        var user = mock(UserAccount.class);
        var authentication = mock(Authentication.class);
        var storeId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var historicalProductId = UUID.randomUUID();
        var currentProductId = UUID.randomUUID();
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        when(authentication.getPrincipal()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        var historicalProduct = frozenProductCommand(
                historicalProductId, new BigDecimal("3.000"), "H", "Historico 3x2",
                new BigDecimal("10.00"), "24.79", "5.21", "30.00");
        var promotionId = UUID.randomUUID();
        var historicalPromotion = frozenAdjustmentCommand(
                DocumentLineType.PROMOTION, "Promocion 3x2", "-8.26", "-1.74", "-10.00",
                promotionId, UUID.randomUUID(), null);
        var historicalCoupon = frozenAdjustmentCommand(
                DocumentLineType.PROMOTIONAL_COUPON, "Cupon historico", "-1.65", "-0.35", "-2.00",
                promotionId, null, null);
        var historicalGlobal = frozenAdjustmentCommand(
                DocumentLineType.MANUAL_DISCOUNT, "Descuento global historico",
                "-1.49", "-0.31", "-1.80", null, null, null);
        var replay = new PreviousTicketImportService.ResolvedImport(
                UUID.randomUUID(), "T-ORIGEN", DocumentStatus.CONFIRMADO,
                PreviousTicketImportPricingMode.FROZEN_EXACT, null,
                "fingerprint", new BigDecimal("13.39"), new BigDecimal("2.81"),
                new BigDecimal("16.20"),
                List.of(historicalProduct, historicalPromotion, historicalCoupon,
                        historicalGlobal),
                1, BigDecimal.ZERO, List.of());
        var currentLine = new DocumentLineCommand(
                currentProductId, BigDecimal.ONE, "N", "Actual", "VENTA",
                new BigDecimal("5.70"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21.00"));
        var command = new DocumentCommand(
                warehouseId, CommercialDocumentType.TICKET, LocalDate.of(2026, 8, 7),
                null, null, null, BigDecimal.ZERO, true, List.of(currentLine));
        var currentQuote = new CommercialDocument(
                storeId, warehouseId, CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 7), userId, BigDecimal.ZERO);
        currentQuote.addLine(new DocumentLine(
                currentQuote, currentProductId, 1, BigDecimal.ONE, "N", "Actual",
                "VENTA", new BigDecimal("5.70"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21.00")));
        when(documents.quoteTicket(command, authentication)).thenReturn(currentQuote);
        var service = new PosCashService(
                documents, mock(ProductRepository.class), mock(StoreTaxRepository.class),
                mock(WarehouseRepository.class), mock(PaymentMethodRepository.class),
                organization, mock(PosCashCheckoutRepository.class),
                new PosCashTicketSnapshot(), currentTerminal);
        var prepared = new PosCashService.PreparedSale(
                command, Set.of(), List.of(), replay);
        var importRequest = new PosCashController.PreviousTicketImportRequest(
                replay.ticketId(), replay.fingerprint(), Map.of());
        var request = new PosCashController.SaleRequest(
                null,
                List.of(new PosCashController.LineRequest(
                        currentProductId, BigDecimal.ONE, BigDecimal.ZERO)),
                null, null, null, null, Map.of(), importRequest, null);

        var combined = service.quotePreparedSale(prepared, request, authentication);

        assertThat(combined.getBaseTotal()).isEqualByComparingTo("18.10");
        assertThat(combined.getImpuestoTotal()).isEqualByComparingTo("3.80");
        assertThat(combined.getTotal()).isEqualByComparingTo("21.90");
        assertThat(combined.getLineas()).hasSize(5);
        assertThat(combined.getLineas().get(1).getLineType())
                .isEqualTo(DocumentLineType.PROMOTION);
        assertThat(combined.getLineas().get(2).getLineType())
                .isEqualTo(DocumentLineType.PROMOTIONAL_COUPON);
        assertThat(combined.getLineas().get(2).getPromotionalCouponId()).isNull();
        assertThat(combined.getLineas().get(2).getTotal()).isEqualByComparingTo("-2.00");
        assertThat(combined.getLineas().get(3).getLineType())
                .isEqualTo(DocumentLineType.MANUAL_DISCOUNT);
        assertThat(combined.getLineas().getLast().getProductoId())
                .isEqualTo(currentProductId);
        assertThat(combined.getLineas().getLast().getTotal())
                .isEqualByComparingTo("5.70");
    }

    @Test
    void replaySnapshotFreezesCouponBaseBeforeTheCheckoutDiscount() {
        var documents = mock(DocumentService.class);
        var service = new PosCashService(
                documents, mock(ProductRepository.class), mock(StoreTaxRepository.class),
                mock(WarehouseRepository.class), mock(PaymentMethodRepository.class),
                mock(CurrentOrganization.class), mock(PosCashCheckoutRepository.class),
                new PosCashTicketSnapshot(), mock(CurrentTerminal.class));
        var storeId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var historicalProduct = frozenProductCommand(
                UUID.randomUUID(), BigDecimal.ONE, "H", "Historico",
                new BigDecimal("50.00"), "50.00", "0.00", "50.00");
        var currentProductId = UUID.randomUUID();
        var promotionId = UUID.randomUUID();
        var couponId = UUID.randomUUID();
        var quoted = new CommercialDocument(
                storeId, warehouseId, CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 7), UUID.randomUUID(), BigDecimal.ZERO);
        quoted.addLine(historicalProduct.toEntity(quoted));
        quoted.addLine(frozenProductCommand(
                currentProductId, BigDecimal.ONE, "N", "Actual",
                new BigDecimal("100.00"), "100.00", "0.00", "100.00")
                .toEntity(quoted));
        quoted.addLine(frozenAdjustmentCommand(
                DocumentLineType.PROMOTIONAL_COUPON, "CUPON ****1234",
                "-10.00", "0.00", "-10.00", promotionId, null, couponId)
                .toEntity(quoted));
        quoted.addLine(frozenAdjustmentCommand(
                DocumentLineType.MANUAL_DISCOUNT, "Descuento directo",
                "-5.00", "0.00", "-5.00", null, null, null)
                .toEntity(quoted));
        var replay = new PreviousTicketImportService.ResolvedImport(
                UUID.randomUUID(), "T-ORIGEN", DocumentStatus.CONFIRMADO,
                PreviousTicketImportPricingMode.FROZEN_EXACT, null,
                "fingerprint", new BigDecimal("50.00"), BigDecimal.ZERO,
                new BigDecimal("50.00"), List.of(historicalProduct), 1,
                BigDecimal.ZERO, List.of());
        var currentCommand = new DocumentCommand(
                warehouseId, CommercialDocumentType.TICKET, LocalDate.of(2026, 8, 7),
                null, null, null, BigDecimal.ZERO, true,
                List.of(new DocumentLineCommand(
                        currentProductId, BigDecimal.ONE, "N", "Actual", "VENTA",
                        new BigDecimal("100.00"), BigDecimal.ZERO, true, "IVA",
                        BigDecimal.ZERO)));
        var prepared = new PosCashService.PreparedSale(
                currentCommand, Set.of(), List.of(), replay);
        when(documents.historicalReplayGeneratedCoupons(
                quoted, 1, currentCommand.lineas())).thenReturn(List.of());

        var snapshot = service.snapshot(quoted, UUID.randomUUID(), prepared);

        assertThat(snapshot.historicalReplay().currentPendingBeforeCoupon())
                .isEqualByComparingTo("100.00");
    }

    @Test
    void currentRepricingQuotesImportedAndNewProductsTogetherAndPreservesFixedDiscount() {
        var documents = mock(DocumentService.class);
        var organization = mock(CurrentOrganization.class);
        var currentTerminal = mock(CurrentTerminal.class);
        var store = mock(Store.class);
        var user = mock(UserAccount.class);
        var authentication = mock(Authentication.class);
        var storeId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var importedProductId = UUID.randomUUID();
        var newProductId = UUID.randomUUID();
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        when(authentication.getPrincipal()).thenReturn(user);
        when(user.getId()).thenReturn(UUID.randomUUID());
        var imported = new DocumentLineCommand(
                importedProductId, BigDecimal.ONE, "IMP", "Importado", "VENTA",
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21.00"));
        var current = new DocumentLineCommand(
                newProductId, BigDecimal.ONE, "NEW", "Nuevo", "VENTA",
                new BigDecimal("5.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21.00"));
        var command = new DocumentCommand(
                warehouseId, CommercialDocumentType.TICKET, LocalDate.of(2026, 8, 7),
                null, null, null, BigDecimal.ZERO, true,
                List.of(imported, current));
        var replay = new PreviousTicketImportService.ResolvedImport(
                UUID.randomUUID(), "T-CONFIRMADO", DocumentStatus.CONFIRMADO,
                PreviousTicketImportPricingMode.CURRENT_REPRICING, null,
                "fingerprint-current", new BigDecimal("8.26"),
                new BigDecimal("1.74"), BigDecimal.ZERO, List.of(imported), 1,
                BigDecimal.ONE, List.of());
        var quoted = new CommercialDocument(
                storeId, warehouseId, CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 7), UUID.randomUUID(), BigDecimal.ZERO);
        quoted.addLine(imported.toEntity(quoted));
        quoted.addLine(current.toEntity(quoted));
        quoted.addLine(frozenAdjustmentCommand(
                DocumentLineType.PROMOTION, "Promocion actual", "-1.65", "-0.35",
                "-2.00", UUID.randomUUID(), UUID.randomUUID(), null)
                .toEntity(quoted));
        quoted.addLine(frozenAdjustmentCommand(
                DocumentLineType.MANUAL_DISCOUNT, "Descuento manual importado",
                "-0.83", "-0.17", "-1.00", null, null, null)
                .toEntity(quoted));
        when(documents.quoteTicket(
                command, null, new BigDecimal("1.00"), authentication))
                .thenReturn(quoted);
        when(documents.historicalReplayGeneratedCoupons(
                quoted, 0, command.lineas())).thenReturn(List.of());
        var service = new PosCashService(
                documents, mock(ProductRepository.class), mock(StoreTaxRepository.class),
                mock(WarehouseRepository.class), mock(PaymentMethodRepository.class),
                organization, mock(PosCashCheckoutRepository.class),
                new PosCashTicketSnapshot(), currentTerminal);
        var prepared = new PosCashService.PreparedSale(
                command, Set.of(SaleOperationCode.APPLY_CHECKOUT_DISCOUNT),
                List.of(), replay);
        var request = new PosCashController.SaleRequest(
                null,
                List.of(new PosCashController.LineRequest(
                        newProductId, BigDecimal.ONE, BigDecimal.ZERO)),
                null, null, null, null, Map.of(),
                new PosCashController.PreviousTicketImportRequest(
                        replay.ticketId(), replay.fingerprint(), Map.of()),
                null);

        var result = service.quotePreparedSale(prepared, request, authentication);
        var snapshot = service.snapshot(result, UUID.randomUUID(), prepared);

        assertThat(result).isSameAs(quoted);
        assertThat(result.getLineas())
                .extracting(DocumentLine::getLineType)
                .containsExactly(
                        DocumentLineType.PRODUCT, DocumentLineType.PRODUCT,
                        DocumentLineType.PROMOTION, DocumentLineType.MANUAL_DISCOUNT);
        assertThat(snapshot.historicalReplay().historicalLineCount()).isZero();
        assertThat(snapshot.historicalReplay().historicalTotal())
                .isEqualByComparingTo("0.00");
        assertThat(snapshot.historicalReplay().currentPendingBeforeCoupon())
                .isEqualByComparingTo("13.00");
        assertThat(snapshot.historicalReplay().historicalLoyaltyLines()).isEmpty();
    }

    @Test
    void preparedCurrentReplayClassifiesTemporaryPriceAndFixedDiscount() {
        var documents = mock(DocumentService.class);
        var products = mock(ProductRepository.class);
        var taxes = mock(StoreTaxRepository.class);
        var warehouses = mock(WarehouseRepository.class);
        var organization = mock(CurrentOrganization.class);
        var currentTerminal = mock(CurrentTerminal.class);
        var previousImports = mock(PreviousTicketImportService.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        var warehouse = Warehouse.general(storeId);
        var importedProductId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(organization.currentStore()).thenReturn(store);
        when(warehouses.findByStoreIdAndPredeterminadoTrue(storeId))
                .thenReturn(Optional.of(warehouse));
        var imported = new DocumentLineCommand(
                importedProductId, BigDecimal.ONE, "IMP", "Importado", "TEMPORAL",
                new BigDecimal("7.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21.00"), DocumentLineType.PRODUCT,
                null, null, null, List.of(), false, true);
        var replay = new PreviousTicketImportService.ResolvedImport(
                UUID.randomUUID(), "T-CONFIRMADO", DocumentStatus.CONFIRMADO,
                PreviousTicketImportPricingMode.CURRENT_REPRICING, null,
                "fingerprint-current", new BigDecimal("5.79"),
                new BigDecimal("1.21"), BigDecimal.ZERO, List.of(imported), 1,
                BigDecimal.ONE, List.of());
        when(previousImports.resolve(any(), any(), any())).thenReturn(replay);
        var service = new PosCashService(
                documents, products, taxes, warehouses,
                mock(PaymentMethodRepository.class), organization,
                mock(PosCashCheckoutRepository.class), new PosCashTicketSnapshot(),
                currentTerminal);
        service.setPreviousTicketImportService(previousImports);
        var importRequest = new PosCashController.PreviousTicketImportRequest(
                replay.ticketId(), replay.fingerprint(), Map.of());
        var request = new PosCashController.SaleRequest(
                null, List.of(), null, null, null, null, Map.of(), importRequest, null);

        var prepared = service.prepareSale(request, null);

        assertThat(prepared.command().lineas()).containsExactly(imported);
        assertThat(prepared.sensitiveOperations()).containsExactlyInAnyOrder(
                SaleOperationCode.TEMPORARY_PRICE_CHANGE,
                SaleOperationCode.APPLY_CHECKOUT_DISCOUNT);
    }

    @Test
    void ticketSnapshotRemainsCompatibleWithStoredPayloadsWithoutFiscalTotals() {
        var snapshots = new PosCashTicketSnapshot();

        var restored = snapshots.deserialize("""
                {"schemaVersion":1,"ticket":{
                  "documentId":"00000000-0000-0000-0000-000000000001",
                  "documentNumber":"T-OLD","issuedAt":"2026-07-15T10:15:30Z",
                  "lines":[],"payments":[],"total":7.00
                }}
                """);

        assertThat(restored.total()).isEqualByComparingTo("7.00");
        assertThat(restored.baseTotal()).isNull();
        assertThat(restored.taxTotal()).isNull();
    }

    @Test
    void checkoutFixedDiscountIsAuthorizedUsingItsEffectivePercentage() {
        var authorizations = mock(DiscountAuthorizationService.class);
        var authentication = mock(Authentication.class);
        var service = new PosCashService(
                mock(DocumentService.class),
                mock(ProductRepository.class),
                mock(StoreTaxRepository.class),
                mock(WarehouseRepository.class),
                mock(PaymentMethodRepository.class),
                mock(CurrentOrganization.class),
                mock(PosCashCheckoutRepository.class),
                new PosCashTicketSnapshot(),
                mock(CurrentTerminal.class),
                authorizations);
        var sale = new PosCashController.SaleRequest(
                null,
                List.of(new PosCashController.LineRequest(
                        UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO)),
                "manager-token",
                null,
                new BigDecimal("20.00"));

        service.authorizeCheckoutDiscount(sale, new BigDecimal("80.00"), authentication);

        verify(authorizations).enforce(
                new BigDecimal("20.00"), "manager-token", authentication);
    }

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
    void authoritativeQuoteKeepsPromotionOnItsProductsAndF11AsOneSummaryLine() {
        var storeId = UUID.randomUUID();
        var firstProductId = UUID.randomUUID();
        var promotedProductId = UUID.randomUUID();
        var firstProduct = mock(Product.class);
        var promotedProduct = mock(Product.class);
        when(firstProduct.getId()).thenReturn(firstProductId);
        when(firstProduct.getSalePrice()).thenReturn(new BigDecimal("10.00"));
        when(promotedProduct.getId()).thenReturn(promotedProductId);
        when(promotedProduct.getSalePrice()).thenReturn(new BigDecimal("20.00"));
        var ticket = new CommercialDocument(
                storeId, UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 9), UUID.randomUUID(), BigDecimal.ZERO);
        ticket.addLine(new DocumentLine(
                ticket, firstProductId, 1, BigDecimal.ONE, "A", "Sin promocion",
                "VENTA", new BigDecimal("10.00"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("21.00")));
        ticket.addLine(new DocumentLine(
                ticket, promotedProductId, 2, BigDecimal.ONE, "B", "Promocionado",
                "VENTA", new BigDecimal("20.00"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("21.00")));
        var promotion = DocumentLine.special(
                ticket, 3, "PROMOCION 2x1", new BigDecimal("-5.00"), true,
                "IVA", new BigDecimal("21.00"), UUID.randomUUID(),
                UUID.randomUUID(), null);
        promotion.assignPromotionAffectedPositions(List.of(2));
        ticket.addLine(promotion);
        ticket.addLine(DocumentLine.manualDiscount(
                ticket, 4, new BigDecimal("-1.00"), true,
                "IVA", new BigDecimal("21.00")));
        ticket.addLine(DocumentLine.manualDiscount(
                ticket, 5, new BigDecimal("-2.00"), true,
                "IVA", new BigDecimal("21.00")));
        var request = new PosCashController.SaleRequest(null, List.of(
                new PosCashController.LineRequest(
                        firstProductId, BigDecimal.ONE, BigDecimal.ZERO),
                new PosCashController.LineRequest(
                        promotedProductId, BigDecimal.ONE, BigDecimal.ZERO)));

        var quote = PosCashService.Quote.from(
                ticket, request,
                Map.of(firstProductId, firstProduct, promotedProductId, promotedProduct),
                AuthoritativePromotionPricing.CustomerContext.anonymous());

        assertThat(quote.total()).isEqualByComparingTo("22.00");
        assertThat(quote.lineBreakdown()).hasSize(3);
        assertThat(quote.lineBreakdown().get(0).promotionDiscount()).isZero();
        assertThat(quote.lineBreakdown().get(0).finalSubtotal())
                .isEqualByComparingTo("10.00");
        assertThat(quote.lineBreakdown().get(1).promotionDiscount())
                .isEqualByComparingTo("5.00");
        assertThat(quote.lineBreakdown().get(1).finalSubtotal())
                .isEqualByComparingTo("15.00");
        assertThat(quote.lineBreakdown().get(2).lineType())
                .isEqualTo(DocumentLineType.MANUAL_DISCOUNT);
        assertThat(quote.lineBreakdown().get(2).finalSubtotal())
                .isEqualByComparingTo("-3.00");
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
    void cashIdempotencyHashIncludesNormalizedInternalCommentWithoutChangingBlankRequests() {
        var line = new PosCashController.LineRequest(
                UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO);
        var checkoutId = UUID.randomUUID();
        var legacy = new PosCashController.CashRequest(
                checkoutId,
                new PosCashController.SaleRequest(null, List.of(line)),
                BigDecimal.TEN,
                BigDecimal.ONE);
        var blank = new PosCashController.CashRequest(
                checkoutId,
                new PosCashController.SaleRequest(
                        null, List.of(line), null, null, null, "   "),
                BigDecimal.TEN,
                BigDecimal.ONE);
        var first = new PosCashController.CashRequest(
                checkoutId,
                new PosCashController.SaleRequest(
                        null, List.of(line), null, null, null, "  Entregar tarde  "),
                BigDecimal.TEN,
                BigDecimal.ONE);
        var sameNormalized = new PosCashController.CashRequest(
                checkoutId,
                new PosCashController.SaleRequest(
                        null, List.of(line), null, null, null, "Entregar tarde"),
                BigDecimal.TEN,
                BigDecimal.ONE);
        var different = new PosCashController.CashRequest(
                checkoutId,
                new PosCashController.SaleRequest(
                        null, List.of(line), null, null, null, "Entregar manana"),
                BigDecimal.TEN,
                BigDecimal.ONE);

        assertThat(PosCashService.requestHash(blank))
                .isEqualTo(PosCashService.requestHash(legacy));
        assertThat(PosCashService.requestHash(first))
                .isEqualTo(PosCashService.requestHash(sameNormalized))
                .isNotEqualTo(PosCashService.requestHash(different))
                .isNotEqualTo(PosCashService.requestHash(legacy));
    }

    @Test
    void suppliedPriceIsOpenForZeroCatalogPriceAndTemporaryOtherwise() {
        assertThat(PosCashService.authoritativeUnitPrice(
                BigDecimal.ZERO, new BigDecimal("7.25")))
                .isEqualByComparingTo("7.25");

        assertThat(PosCashService.authoritativeUnitPrice(
                new BigDecimal("10.00"), new BigDecimal("7.25")))
                .isEqualByComparingTo("7.25");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> PosCashService.authoritativeUnitPrice(
                        new BigDecimal("10.00"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mayor que 0");
    }

    @Test
    void zeroPricedProductRequiresPositiveOpenPriceWithAtMostTwoDecimals() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> PosCashService.authoritativeUnitPrice(BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Debe indicar el precio");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> PosCashService.authoritativeUnitPrice(
                        BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mayor que 0");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> PosCashService.authoritativeUnitPrice(
                        BigDecimal.ZERO, new BigDecimal("1.001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximo de 2 decimales");
    }

    @Test
    void authoritativeQuoteKeepsRepeatedOpenPriceProductAsSeparateLines() {
        var storeId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getSalePrice()).thenReturn(BigDecimal.ZERO);
        var ticket = new CommercialDocument(
                storeId, UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 27), UUID.randomUUID(), BigDecimal.ZERO);
        ticket.addLine(new DocumentLine(
                ticket, productId, 1, BigDecimal.ONE, "OPEN-1", "Producto abierto",
                null, new BigDecimal("1.00"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("21.00")));
        ticket.addLine(new DocumentLine(
                ticket, productId, 2, BigDecimal.ONE, "OPEN-1", "Producto abierto",
                null, new BigDecimal("2.00"), BigDecimal.ZERO,
                true, "IVA", new BigDecimal("21.00")));
        var request = new PosCashController.SaleRequest(null, List.of(
                new PosCashController.LineRequest(
                        productId, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("1.00")),
                new PosCashController.LineRequest(
                        productId, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("2.00"))));

        var quote = PosCashService.Quote.from(
                ticket, request, Map.of(productId, product),
                AuthoritativePromotionPricing.CustomerContext.anonymous());

        assertThat(quote.lineBreakdown())
                .extracting(
                        PosCashService.AuthoritativeLineBreakdown::lineId,
                        PosCashService.AuthoritativeLineBreakdown::baseUnitPrice)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "product:" + productId + ":1", new BigDecimal("1.00")),
                        org.assertj.core.groups.Tuple.tuple(
                                "product:" + productId + ":2", new BigDecimal("2.00")));
    }

    @Test
    void cashIdempotencyHashIncludesNormalizedTemporaryName() {
        var productId = UUID.randomUUID();
        var checkoutId = UUID.randomUUID();
        var catalogName = new PosCashController.CashRequest(
                checkoutId,
                new PosCashController.SaleRequest(null, List.of(
                        new PosCashController.LineRequest(
                                productId, BigDecimal.ONE, BigDecimal.ZERO))),
                BigDecimal.TEN,
                BigDecimal.TEN);
        var first = new PosCashController.CashRequest(
                checkoutId,
                new PosCashController.SaleRequest(null, List.of(
                        new PosCashController.LineRequest(
                                productId, BigDecimal.ONE, BigDecimal.ZERO,
                                null, List.of(), "  Nombre temporal  "))),
                BigDecimal.TEN,
                BigDecimal.TEN);
        var sameNormalized = new PosCashController.CashRequest(
                checkoutId,
                new PosCashController.SaleRequest(null, List.of(
                        new PosCashController.LineRequest(
                                productId, BigDecimal.ONE, BigDecimal.ZERO,
                                null, List.of(), "Nombre temporal"))),
                BigDecimal.TEN,
                BigDecimal.TEN);

        assertThat(PosCashService.requestHash(first))
                .isEqualTo(PosCashService.requestHash(sameNormalized))
                .isNotEqualTo(PosCashService.requestHash(catalogName));
    }

    @Test
    void preparedSaleClassifiesEverySensitiveLineOperation() {
        var documents = mock(DocumentService.class);
        var products = mock(ProductRepository.class);
        var taxes = mock(StoreTaxRepository.class);
        var warehouses = mock(WarehouseRepository.class);
        var methods = mock(PaymentMethodRepository.class);
        var organization = mock(CurrentOrganization.class);
        var checkouts = mock(PosCashCheckoutRepository.class);
        var currentTerminal = mock(CurrentTerminal.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        var warehouse = Warehouse.general(storeId);
        var tax = new StoreTax(storeId, new BigDecimal("21.00"), true);
        var regular = mock(Product.class);
        var open = mock(Product.class);
        var regularId = UUID.randomUUID();
        var openId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(organization.currentStore()).thenReturn(store);
        when(warehouses.findByStoreIdAndPredeterminadoTrue(storeId))
                .thenReturn(Optional.of(warehouse));
        configureSaleProduct(
                regular, regularId, storeId, tax.getId(), "REG", "Regular",
                new BigDecimal("10.00"));
        configureSaleProduct(
                open, openId, storeId, tax.getId(), "OPEN", "Abierto",
                BigDecimal.ZERO);
        when(products.findById(regularId)).thenReturn(Optional.of(regular));
        when(products.findById(openId)).thenReturn(Optional.of(open));
        when(taxes.findById(tax.getId())).thenReturn(Optional.of(tax));
        var service = new PosCashService(
                documents, products, taxes, warehouses, methods, organization,
                checkouts, new PosCashTicketSnapshot(), currentTerminal);
        var request = new PosCashController.SaleRequest(
                null,
                List.of(
                        new PosCashController.LineRequest(
                                regularId, BigDecimal.ONE.negate(),
                                new BigDecimal("10.00"), new BigDecimal("8.00"),
                                List.of(), "Nombre temporal"),
                        new PosCashController.LineRequest(
                                openId, BigDecimal.ONE, BigDecimal.ZERO,
                                new BigDecimal("2.00"))),
                null,
                null,
                BigDecimal.ONE);

        var prepared = service.prepareSale(request, null);

        assertThat(prepared.sensitiveOperations()).containsExactlyInAnyOrder(
                SaleOperationCode.MANUAL_RETURN_WITHOUT_TICKET,
                SaleOperationCode.TEMPORARY_NAME,
                SaleOperationCode.TEMPORARY_PRICE_CHANGE,
                SaleOperationCode.OPEN_PRICE_PRODUCT,
                SaleOperationCode.APPLY_SALE_DISCOUNT,
                SaleOperationCode.APPLY_CHECKOUT_DISCOUNT);
        assertThat(prepared.command().lineas().get(0)).satisfies(line -> {
            assertThat(line.nombre()).isEqualTo("Nombre temporal");
            assertThat(line.precioUnitario()).isEqualByComparingTo("8.00");
            assertThat(line.temporaryNameOverride()).isTrue();
            assertThat(line.temporaryPriceOverride()).isTrue();
        });
    }

    @Test
    void cashIdempotencyHashIncludesOpenPriceWithoutChangingLegacyCanonical() {
        var checkoutId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var legacy = new PosCashController.CashRequest(
                checkoutId,
                new PosCashController.SaleRequest(null, List.of(
                        new PosCashController.LineRequest(
                                productId, BigDecimal.ONE, BigDecimal.ZERO))),
                BigDecimal.TEN,
                new BigDecimal("7.25"));
        var openPrice = new PosCashController.CashRequest(
                checkoutId,
                new PosCashController.SaleRequest(null, List.of(
                        new PosCashController.LineRequest(
                                productId, BigDecimal.ONE, BigDecimal.ZERO,
                                new BigDecimal("7.25")))),
                BigDecimal.TEN,
                new BigDecimal("7.25"));
        var changedOpenPrice = new PosCashController.CashRequest(
                checkoutId,
                new PosCashController.SaleRequest(null, List.of(
                        new PosCashController.LineRequest(
                                productId, BigDecimal.ONE, BigDecimal.ZERO,
                                new BigDecimal("8.25")))),
                BigDecimal.TEN,
                new BigDecimal("7.25"));

        assertThat(PosCashService.requestHash(openPrice))
                .isNotEqualTo(PosCashService.requestHash(legacy))
                .isNotEqualTo(PosCashService.requestHash(changedOpenPrice));
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
        when(documents.ticketPrintView(ticket)).thenReturn(TicketPrintView.from(ticket));
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
        assertThat(result.printTicket().baseTotal()).isEqualByComparingTo("5.79");
        assertThat(result.printTicket().taxTotal()).isEqualByComparingTo("1.21");
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

    private static ReplayFixture replayFixture(String sourceFingerprint) {
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
        var previousTicketImport = new PosCashController.PreviousTicketImportRequest(
                UUID.randomUUID(), sourceFingerprint, Map.of());
        var sale = new PosCashController.SaleRequest(
                null,
                List.of(new PosCashController.LineRequest(
                        UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO)),
                null, null, null, null, Map.of(), previousTicketImport, null);
        var request = new PosCashController.CashRequest(
                UUID.randomUUID(), sale, new BigDecimal("10.00"), new BigDecimal("7.00"));
        return new ReplayFixture(service, documents, checkouts, snapshots, authentication,
                request, companyId, storeId, terminalId, userId);
    }

    private record ReplayFixture(
            PosCashService service, DocumentService documents, PosCashCheckoutRepository checkouts,
            PosCashTicketSnapshot snapshots, Authentication authentication,
            PosCashController.CashRequest request, UUID companyId, UUID storeId,
            UUID terminalId, UUID userId) {}

    private static DocumentLineCommand frozenProductCommand(
            UUID productId,
            BigDecimal quantity,
            String code,
            String name,
            BigDecimal unitPrice,
            String base,
            String tax,
            String total) {
        return new DocumentLineCommand(
                productId, quantity, code, name, "VENTA", unitPrice,
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21.00"),
                DocumentLineType.PRODUCT, null, null, null, List.of(), false, false,
                null, null, null, null, null,
                new BigDecimal(base), new BigDecimal(tax), new BigDecimal(total));
    }

    private static DocumentLineCommand frozenAdjustmentCommand(
            DocumentLineType type,
            String name,
            String base,
            String tax,
            String total,
            UUID promotionId,
            UUID promotionVersionId,
            UUID couponId) {
        return new DocumentLineCommand(
                null, BigDecimal.ONE, name, name, null, new BigDecimal(total),
                BigDecimal.ZERO, true, "IVA", new BigDecimal("21.00"),
                type, promotionId, promotionVersionId, couponId, List.of(), false, false,
                null, null, null, null, null,
                new BigDecimal(base), new BigDecimal(tax), new BigDecimal(total));
    }

    private static void configureSaleProduct(
            Product product,
            UUID productId,
            UUID storeId,
            UUID taxId,
            String code,
            String name,
            BigDecimal salePrice) {
        when(product.getId()).thenReturn(productId);
        when(product.getStoreId()).thenReturn(storeId);
        when(product.getTaxId()).thenReturn(taxId);
        when(product.getCode()).thenReturn(code);
        when(product.getName()).thenReturn(name);
        when(product.getSalePrice()).thenReturn(salePrice);
        when(product.isTaxesIncluded()).thenReturn(true);
    }

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
