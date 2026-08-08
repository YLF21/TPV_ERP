package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductPriceHistory;
import com.tpverp.backend.catalog.ProductPriceHistoryRepository;
import com.tpverp.backend.catalog.ProductPriceHistoryType;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.StoreTax;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.MemberDocumentLoyaltyLine;
import com.tpverp.backend.party.MemberDocumentLoyaltyLineRepository;
import com.tpverp.backend.party.MemberDocumentLoyaltySettlement;
import com.tpverp.backend.party.MemberDocumentLoyaltySettlementRepository;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;

class PreviousTicketImportServiceTest {

    private static final Instant CONFIRMED_AT = Instant.parse("2026-08-07T10:15:30Z");

    @Test
    void previewsConfirmedTicketForCurrentRepricingAndDropsHistoricalPromotion() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        var adjustment = fixture.adjustment(DocumentLineType.PROMOTION, "Promocion", "-1.00");
        var historicalCoupon = fixture.adjustment(
                DocumentLineType.PROMOTIONAL_COUPON, "Cupon historico", "-2.00");
        when(historicalCoupon.getPromotionalCouponId()).thenReturn(UUID.randomUUID());
        when(fixture.ticket().getLineas()).thenReturn(
                List.of(adjustment, historicalCoupon, fixture.line()));

        var result = fixture.service().preview(fixture.authentication());

        assertThat(result.ticketId()).isEqualTo(fixture.ticketId());
        assertThat(result.ticketNumber()).isEqualTo("001-260807-00001");
        assertThat(result.status()).isEqualTo(DocumentStatus.CONFIRMADO);
        assertThat(result.pricingMode())
                .isEqualTo(PreviousTicketImportPricingMode.CURRENT_REPRICING);
        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.sourceLineId()).isEqualTo(fixture.lineId());
            assertThat(line.productId()).isEqualTo(fixture.productId());
            assertThat(line.code()).isEqualTo("A");
            assertThat(line.quantity()).isEqualByComparingTo("1.000");
            assertThat(line.base()).isEqualByComparingTo("8.26");
            assertThat(line.tax()).isEqualByComparingTo("1.74");
            assertThat(line.total()).isEqualByComparingTo("10.00");
            assertThat(line.serialNumbers()).isEmpty();
            assertThat(line.productType()).isEqualTo(ProductType.UNIT);
            assertThat(line.manualPricePreserved()).isFalse();
            assertThat(line.temporaryPriceAuthorizationRequired()).isFalse();
            assertThat(line.requiresNewSerialNumbers()).isTrue();
        });
        assertThat(result.adjustments()).isEmpty();
        verify(fixture.repository()).findLatestPositiveConfirmedTicketIds(
                fixture.storeId(), fixture.terminalId(), PageRequest.of(0, 1));
    }

    @Test
    void cancelledSourceReusesOriginalSerialsAndRejectsOverrides() {
        var fixture = fixture(DocumentStatus.ANULADO, TaxRegime.IVA);
        var preview = fixture.service().preview(fixture.authentication());

        assertThat(preview.lines().getFirst().serialNumbers()).containsExactly("ORIGINAL-1");
        assertThat(preview.lines().getFirst().requiresNewSerialNumbers()).isFalse();

        assertThatThrownBy(() -> fixture.service().resolve(
                new PosCashController.PreviousTicketImportRequest(
                        fixture.ticketId(), preview.fingerprint(),
                        Map.of(fixture.lineId(), List.of("NEW-1"))),
                null, fixture.authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.previous_ticket_cancelled_serial_override");

        when(fixture.repository().usedSerialNumbers(
                fixture.storeId(), java.util.Set.of("ORIGINAL-1")))
                .thenReturn(List.of("ORIGINAL-1"));
        assertThatThrownBy(() -> fixture.service().resolve(
                new PosCashController.PreviousTicketImportRequest(
                        fixture.ticketId(), preview.fingerprint(), Map.of()),
                null, fixture.authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.previous_ticket_new_serials_required");

        when(fixture.repository().usedSerialNumbers(
                fixture.storeId(), java.util.Set.of("ORIGINAL-1")))
                .thenReturn(List.of());
        var resolved = fixture.service().resolve(
                new PosCashController.PreviousTicketImportRequest(
                        fixture.ticketId(), preview.fingerprint(), Map.of()),
                null, fixture.authentication());
        assertThat(resolved.commands().getFirst().serialNumbers())
                .containsExactly("ORIGINAL-1");
    }

    @Test
    void confirmedSourceRequiresUnusedSerialsDifferentFromTheOriginals() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        var preview = fixture.service().preview(fixture.authentication());

        assertThatThrownBy(() -> fixture.service().resolve(
                request(fixture, preview, Map.of()), null, fixture.authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.previous_ticket_new_serials_required");
        assertThatThrownBy(() -> fixture.service().resolve(
                request(fixture, preview,
                        Map.of(fixture.lineId(), List.of("original-1"))),
                null, fixture.authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.previous_ticket_new_serials_required");

        when(fixture.repository().usedSerialNumbers(
                fixture.storeId(), java.util.Set.of("NEW-1")))
                .thenReturn(List.of("NEW-1"));
        assertThatThrownBy(() -> fixture.service().resolve(
                request(fixture, preview,
                        Map.of(fixture.lineId(), List.of("NEW-1"))),
                null, fixture.authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.document.previous_ticket_new_serials_required");

        when(fixture.repository().usedSerialNumbers(
                fixture.storeId(), java.util.Set.of("NEW-2")))
                .thenReturn(List.of());
        var resolved = fixture.service().resolve(
                request(fixture, preview,
                        Map.of(fixture.lineId(), List.of("NEW-2"))),
                null, fixture.authentication());
        var command = resolved.commands().getFirst();
        assertThat(command.serialNumbers()).containsExactly("NEW-2");
        assertThat(command.frozenBase()).isNull();
        assertThat(command.frozenTax()).isNull();
        assertThat(command.frozenTotal()).isNull();
        assertThat(command.temporaryPriceOverride()).isFalse();
        assertThat(resolved.pricingMode())
                .isEqualTo(PreviousTicketImportPricingMode.CURRENT_REPRICING);
        assertThat(resolved.historicalLoyaltyLines()).isEmpty();
    }

    @Test
    void rejectsAPartialOrForeignHistoricalLoyaltySnapshot() {
        var fixture = fixture(DocumentStatus.ANULADO, TaxRegime.IVA);
        var preview = fixture.service().preview(fixture.authentication());
        var foreign = mock(MemberDocumentLoyaltyLine.class);
        when(foreign.getDocumentLineId()).thenReturn(UUID.randomUUID());
        when(foreign.getEligibleAmount()).thenReturn(BigDecimal.ZERO);
        when(fixture.loyaltyLines().findByDocumentId(fixture.ticketId()))
                .thenReturn(List.of(foreign));

        assertThatThrownBy(() -> fixture.service().resolve(
                request(fixture, preview, Map.of()),
                null, fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.document.previous_ticket_loyalty_snapshot_invalid");
    }

    @Test
    void confirmedTicketUsesTheCurrentIgicRate() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IGIC);
        when(fixture.line().getRegimenImpuesto()).thenReturn("IGIC");
        assertThat(fixture.service().preview(fixture.authentication()).lines())
                .hasSize(1);

        when(fixture.tax().getPercentage()).thenReturn(new BigDecimal("7.00"));
        assertThat(fixture.service().preview(fixture.authentication()).lines())
                .singleElement()
                .satisfies(line -> assertThat(line.taxPercent())
                        .isEqualByComparingTo("7.00"));
    }

    @Test
    void cancelledTicketRejectsAChangedTaxRate() {
        var fixture = fixture(DocumentStatus.ANULADO, TaxRegime.IVA);
        when(fixture.tax().getPercentage()).thenReturn(new BigDecimal("7.00"));

        assertThatThrownBy(() -> fixture.service().preview(fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.document.previous_ticket_tax_changed");
    }

    @Test
    void rejectsAFormerFractionalQuantityWhenTheCurrentProductIsUnitBased() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        when(fixture.line().getCantidad()).thenReturn(new BigDecimal("1.500"));

        assertThatThrownBy(() -> fixture.service().preview(fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.document.previous_ticket_product_changed");
    }

    @Test
    void confirmedTicketRejectsAnAmbiguousPercentageDiscountAtomically() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        when(fixture.line().getDescuento()).thenReturn(new BigDecimal("10.00"));

        assertThatThrownBy(() -> fixture.service().preview(fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "message.document.previous_ticket_discount_origin_ambiguous");
    }

    @Test
    void confirmedTicketPreservesTemporaryPriceAndRequiresAuthorization() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        when(fixture.line().getTarifa()).thenReturn("TEMPORAL");
        when(fixture.line().getPrecioUnitario()).thenReturn(new BigDecimal("7.00"));
        var preview = fixture.service().preview(fixture.authentication());

        assertThat(preview.lines()).singleElement().satisfies(line -> {
            assertThat(line.unitPrice()).isEqualByComparingTo("7.00");
            assertThat(line.manualPricePreserved()).isTrue();
            assertThat(line.temporaryPriceAuthorizationRequired()).isTrue();
        });

        when(fixture.repository().usedSerialNumbers(
                fixture.storeId(), java.util.Set.of("NEW-TEMP")))
                .thenReturn(List.of());
        var resolved = fixture.service().resolve(
                request(fixture, preview,
                        Map.of(fixture.lineId(), List.of("NEW-TEMP"))),
                null, fixture.authentication());

        assertThat(resolved.hasTemporaryPriceOverride()).isTrue();
        assertThat(resolved.commands()).singleElement().satisfies(line -> {
            assertThat(line.precioUnitario()).isEqualByComparingTo("7.00");
            assertThat(line.temporaryPriceOverride()).isTrue();
        });
    }

    @Test
    void confirmedTicketPreservesHistoricalOpenPriceWithoutAuthorization() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        when(fixture.product().getSalePrice()).thenReturn(new BigDecimal("12.00"));
        when(fixture.line().getPrecioUnitario()).thenReturn(new BigDecimal("7.00"));
        historicalSalePrice(fixture, "0.00");
        var preview = fixture.service().preview(fixture.authentication());

        assertThat(preview.lines()).singleElement().satisfies(line -> {
            assertThat(line.unitPrice()).isEqualByComparingTo("7.00");
            assertThat(line.manualPricePreserved()).isTrue();
            assertThat(line.temporaryPriceAuthorizationRequired()).isFalse();
        });

        when(fixture.repository().usedSerialNumbers(
                fixture.storeId(), java.util.Set.of("NEW-OPEN")))
                .thenReturn(List.of());
        var resolved = fixture.service().resolve(
                request(fixture, preview,
                        Map.of(fixture.lineId(), List.of("NEW-OPEN"))),
                null, fixture.authentication());

        assertThat(resolved.hasTemporaryPriceOverride()).isFalse();
        assertThat(resolved.commands()).singleElement().satisfies(line -> {
            assertThat(line.precioUnitario()).isEqualByComparingTo("7.00");
            assertThat(line.temporaryPriceOverride()).isFalse();
        });
    }

    @Test
    void replayedOpenPriceRemainsImportableWithoutHistoryOrAuthorization() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        when(fixture.line().getTarifa()).thenReturn("OPEN_PRICE");
        when(fixture.line().getPrecioUnitario()).thenReturn(new BigDecimal("7.00"));
        when(fixture.product().getSalePrice()).thenReturn(new BigDecimal("12.00"));
        var preview = fixture.service().preview(fixture.authentication());

        assertThat(preview.lines()).singleElement().satisfies(line -> {
            assertThat(line.rate()).isEqualTo("VENTA");
            assertThat(line.unitPrice()).isEqualByComparingTo("7.00");
            assertThat(line.manualPricePreserved()).isTrue();
            assertThat(line.temporaryPriceAuthorizationRequired()).isFalse();
        });

        when(fixture.repository().usedSerialNumbers(
                fixture.storeId(), java.util.Set.of("NEW-OPEN-REPLAY")))
                .thenReturn(List.of());
        var resolved = fixture.service().resolve(
                request(fixture, preview,
                        Map.of(fixture.lineId(), List.of("NEW-OPEN-REPLAY"))),
                null, fixture.authentication());

        assertThat(resolved.hasTemporaryPriceOverride()).isFalse();
        assertThat(resolved.commands()).singleElement().satisfies(line -> {
            assertThat(line.tarifa()).isEqualTo("OPEN_PRICE");
            assertThat(line.precioUnitario()).isEqualByComparingTo("7.00");
            assertThat(line.historicalOpenPriceOverride()).isTrue();
        });
    }

    @Test
    void confirmedTicketRepricesARegularHistoricalSalePrice() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        historicalSalePrice(fixture, "10.00");
        when(fixture.product().getSalePrice()).thenReturn(new BigDecimal("12.00"));

        var preview = fixture.service().preview(fixture.authentication());

        assertThat(preview.lines()).singleElement().satisfies(line -> {
            assertThat(line.unitPrice()).isEqualByComparingTo("12.00");
            assertThat(line.manualPricePreserved()).isFalse();
            assertThat(line.temporaryPriceAuthorizationRequired()).isFalse();
        });
    }

    @Test
    void confirmedTicketRejectsASalePriceDifferentFromKnownHistory() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        historicalSalePrice(fixture, "10.00");
        when(fixture.line().getPrecioUnitario()).thenReturn(new BigDecimal("7.00"));

        assertThatThrownBy(() -> fixture.service().preview(fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.document.previous_ticket_price_origin_ambiguous");
    }

    @Test
    void confirmedLegacyTicketWithoutHistoryIsAcceptedOnlyWhenPricesMatch() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        assertThat(fixture.service().preview(fixture.authentication()).lines())
                .singleElement()
                .satisfies(line -> assertThat(line.unitPrice())
                        .isEqualByComparingTo("10.00"));

        when(fixture.product().getSalePrice()).thenReturn(new BigDecimal("12.00"));
        assertThatThrownBy(() -> fixture.service().preview(fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.document.previous_ticket_price_origin_ambiguous");
    }

    @Test
    void confirmedTicketRequiresANewEntryWhenRegularPriceIsNowOpen() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        historicalSalePrice(fixture, "10.00");
        when(fixture.product().getSalePrice()).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> fixture.service().preview(fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "message.document.previous_ticket_open_price_requires_new_entry");
    }

    @Test
    void confirmedLegacyOpenPriceWithoutHistoryFailsClosed() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        when(fixture.product().getSalePrice()).thenReturn(BigDecimal.ZERO);
        when(fixture.line().getPrecioUnitario()).thenReturn(new BigDecimal("7.00"));

        assertThatThrownBy(() -> fixture.service().preview(fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.document.previous_ticket_price_origin_ambiguous");
    }

    @Test
    void memberAndOfferRatesAlwaysUseCurrentRepricing() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        historicalSalePrice(fixture, "0.00");
        when(fixture.product().getSalePrice()).thenReturn(new BigDecimal("12.00"));
        when(fixture.line().getTarifa()).thenReturn("OFERTA");
        when(fixture.line().getPrecioUnitario()).thenReturn(new BigDecimal("7.00"));

        assertThat(fixture.service().preview(fixture.authentication()).lines())
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.unitPrice()).isEqualByComparingTo("12.00");
                    assertThat(line.manualPricePreserved()).isFalse();
                });

        when(fixture.line().getTarifa()).thenReturn("MEMBER");
        assertThat(fixture.service().preview(fixture.authentication()).lines())
                .singleElement()
                .satisfies(line -> assertThat(line.unitPrice())
                        .isEqualByComparingTo("12.00"));
    }

    @Test
    void conflictingHistoryAtTheSameInstantFailsClosed() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        var updatedAt = CONFIRMED_AT.minusSeconds(60);
        var zero = new ProductPriceHistory(
                fixture.productId(), ProductPriceHistoryType.VENTA,
                BigDecimal.ZERO, updatedAt);
        var ten = new ProductPriceHistory(
                fixture.productId(), ProductPriceHistoryType.VENTA,
                new BigDecimal("10.00"), updatedAt);
        when(fixture.priceHistory().findPriceEvidenceAtOrBefore(
                List.of(fixture.productId()), ProductPriceHistoryType.VENTA,
                CONFIRMED_AT)).thenReturn(List.of(zero, ten));

        assertThatThrownBy(() -> fixture.service().preview(fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.document.previous_ticket_price_origin_ambiguous");
    }

    @Test
    void currentPriceIsPartOfTheImportFingerprint() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        historicalSalePrice(fixture, "10.00");
        when(fixture.product().getSalePrice()).thenReturn(new BigDecimal("12.00"));
        var preview = fixture.service().preview(fixture.authentication());
        when(fixture.product().getSalePrice()).thenReturn(new BigDecimal("13.00"));

        assertThatThrownBy(() -> fixture.service().resolve(
                request(fixture, preview,
                        Map.of(fixture.lineId(), List.of("NEW-PRICE"))),
                null, fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.document.previous_ticket_changed");
    }

    @Test
    void selectedHistoricalEvidenceIsPartOfTheImportFingerprint() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        var first = historicalSalePrice(fixture, "10.00");
        var preview = fixture.service().preview(fixture.authentication());
        var replacement = new ProductPriceHistory(
                fixture.productId(), ProductPriceHistoryType.VENTA,
                new BigDecimal("10.00"), first.getUpdatedAt().plusSeconds(1));
        when(fixture.priceHistory().findPriceEvidenceAtOrBefore(
                List.of(fixture.productId()), ProductPriceHistoryType.VENTA,
                CONFIRMED_AT)).thenReturn(List.of(replacement, first));

        assertThatThrownBy(() -> fixture.service().resolve(
                request(fixture, preview,
                        Map.of(fixture.lineId(), List.of("NEW-HISTORY"))),
                null, fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.document.previous_ticket_changed");
    }

    @Test
    void currentRepricingLoadsHistoricalSalePricesInOneBulkQuery() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        var secondProductId = UUID.randomUUID();
        var secondLine = mock(DocumentLine.class);
        when(secondLine.getId()).thenReturn(UUID.randomUUID());
        when(secondLine.getPosicion()).thenReturn(2);
        when(secondLine.getLineType()).thenReturn(DocumentLineType.PRODUCT);
        when(secondLine.getProductoId()).thenReturn(secondProductId);
        when(secondLine.getCantidad()).thenReturn(BigDecimal.ONE);
        when(secondLine.getCodigo()).thenReturn("B");
        when(secondLine.getNombre()).thenReturn("Producto B");
        when(secondLine.getTarifa()).thenReturn("VENTA");
        when(secondLine.getPrecioUnitario()).thenReturn(new BigDecimal("20.00"));
        when(secondLine.getDescuento()).thenReturn(BigDecimal.ZERO);
        when(secondLine.isImpuestosIncluidos()).thenReturn(true);
        when(secondLine.getRegimenImpuesto()).thenReturn("IVA");
        when(secondLine.getPorcentajeImpuesto()).thenReturn(new BigDecimal("21.00"));
        when(secondLine.getSerialNumbers()).thenReturn(List.of());
        when(fixture.ticket().getLineas()).thenReturn(List.of(fixture.line(), secondLine));

        var secondProduct = mock(Product.class);
        when(secondProduct.getId()).thenReturn(secondProductId);
        when(secondProduct.getCode()).thenReturn("B");
        when(secondProduct.getName()).thenReturn("Producto B");
        when(secondProduct.getSalePrice()).thenReturn(new BigDecimal("20.00"));
        var taxId = fixture.product().getTaxId();
        when(secondProduct.getTaxId()).thenReturn(taxId);
        when(secondProduct.isActive()).thenReturn(true);
        when(secondProduct.isTaxesIncluded()).thenReturn(true);
        when(secondProduct.getProductType()).thenReturn(ProductType.UNIT);
        var productIds = List.of(fixture.productId(), secondProductId);
        when(fixture.products().findAllByStoreIdAndIdIn(
                fixture.storeId(), productIds))
                .thenReturn(List.of(fixture.product(), secondProduct));

        assertThat(fixture.service().preview(fixture.authentication()).lines())
                .hasSize(2);
        verify(fixture.priceHistory()).findPriceEvidenceAtOrBefore(
                productIds, ProductPriceHistoryType.VENTA, CONFIRMED_AT);
    }

    @Test
    void confirmedTicketKeepsOnlyUnambiguousFixedManualDiscounts() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        var manual = fixture.adjustment(
                DocumentLineType.MANUAL_DISCOUNT, "Descuento manual", "-1.00");
        var promotion = fixture.adjustment(
                DocumentLineType.PROMOTION, "Promocion historica", "-2.00");
        when(fixture.ticket().getLineas())
                .thenReturn(List.of(fixture.line(), manual, promotion));
        var preview = fixture.service().preview(fixture.authentication());

        assertThat(preview.preservedManualDiscountAmount())
                .isEqualByComparingTo("1.00");
        assertThat(preview.manualDiscountAuthorizationRequired()).isTrue();
        assertThat(preview.adjustments()).singleElement()
                .satisfies(line -> assertThat(line.lineType())
                        .isEqualTo(DocumentLineType.MANUAL_DISCOUNT));

        when(fixture.repository().usedSerialNumbers(
                fixture.storeId(), java.util.Set.of("NEW-MANUAL")))
                .thenReturn(List.of());
        var resolved = fixture.service().resolve(
                request(fixture, preview,
                        Map.of(fixture.lineId(), List.of("NEW-MANUAL"))),
                null, fixture.authentication());
        var metadata = resolved.metadata(
                new BigDecimal("9.00"), List.of(), List.of());

        assertThat(resolved.commands()).hasSize(1);
        assertThat(resolved.preservedManualDiscountAmount())
                .isEqualByComparingTo("1.00");
        assertThat(metadata.historicalLineCount()).isZero();
        assertThat(metadata.historicalTotal()).isEqualByComparingTo("0.00");
        assertThat(metadata.historicalLoyaltyLines()).isEmpty();
    }

    @Test
    void fingerprintSafelySeparatesTextAndIncludesDateAndCurrency() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        when(fixture.line().getCodigo()).thenReturn("A:B");
        when(fixture.line().getNombre()).thenReturn("C");
        var first = fixture.service().preview(fixture.authentication()).fingerprint();

        // These pairs produced the same delimiter sequence in the old canonical form.
        when(fixture.line().getCodigo()).thenReturn("A");
        when(fixture.line().getNombre()).thenReturn("B:C");
        var differentText = fixture.service().preview(fixture.authentication()).fingerprint();

        when(fixture.ticket().getFecha()).thenReturn(LocalDate.of(2026, 8, 8));
        var differentDate = fixture.service().preview(fixture.authentication()).fingerprint();
        when(fixture.ticket().getFecha()).thenReturn(LocalDate.of(2026, 8, 7));
        when(fixture.ticket().getMoneda()).thenReturn("USD");
        var differentCurrency = fixture.service().preview(fixture.authentication()).fingerprint();

        assertThat(first).isNotEqualTo(differentText);
        assertThat(differentText).isNotEqualTo(differentDate);
        assertThat(differentText).isNotEqualTo(differentCurrency);
    }

    @Test
    void replayRedistributesALegacyOverstatedLoyaltySnapshotToTheSettlementTotal() {
        var fixture = fixture(DocumentStatus.ANULADO, TaxRegime.IVA);
        var historicalCouponId = UUID.randomUUID();
        var adjustment = fixture.adjustment(
                DocumentLineType.PROMOTIONAL_COUPON, "Cupon historico", "-10.00");
        when(adjustment.getPromotionalCouponId()).thenReturn(historicalCouponId);
        when(adjustment.getBase()).thenReturn(new BigDecimal("-8.26"));
        when(adjustment.getImpuesto()).thenReturn(new BigDecimal("-1.74"));
        when(fixture.line().getPrecioUnitario()).thenReturn(new BigDecimal("100.00"));
        when(fixture.line().getBase()).thenReturn(new BigDecimal("82.64"));
        when(fixture.line().getImpuesto()).thenReturn(new BigDecimal("17.36"));
        when(fixture.line().getTotal()).thenReturn(new BigDecimal("100.00"));
        when(fixture.ticket().getLineas()).thenReturn(List.of(fixture.line(), adjustment));
        when(fixture.ticket().getBaseTotal()).thenReturn(new BigDecimal("74.38"));
        when(fixture.ticket().getImpuestoTotal()).thenReturn(new BigDecimal("15.62"));
        when(fixture.ticket().getTotal()).thenReturn(new BigDecimal("90.00"));
        var loyalty = mock(MemberDocumentLoyaltyLine.class);
        when(loyalty.getDocumentLineId()).thenReturn(fixture.lineId());
        when(loyalty.isEligible()).thenReturn(true);
        // Legacy snapshots could contain the gross product amount while the
        // settlement already held the correct post-coupon eligible total.
        when(loyalty.getEligibleAmount()).thenReturn(new BigDecimal("100.00"));
        when(fixture.loyaltyLines().findByDocumentId(fixture.ticketId()))
                .thenReturn(List.of(loyalty));
        settlement(fixture, "90.00");
        var preview = fixture.service().preview(fixture.authentication());

        var resolved = fixture.service().resolve(
                request(fixture, preview, Map.of()),
                null, fixture.authentication());

        assertThat(resolved.total()).isEqualByComparingTo("90.00");
        assertThat(resolved.commands()).hasSize(2);
        assertThat(resolved.commands().get(1).promotionalCouponId())
                .isEqualTo(historicalCouponId);
        assertThat(resolved.historicalLoyaltyLines()).singleElement()
                .satisfies(line -> assertThat(line.eligibleAmount())
                        .isEqualByComparingTo("90.00"));
    }

    @Test
    void rejectsACompleteHistoricalLoyaltySnapshotWithoutItsSettlement() {
        var fixture = fixture(DocumentStatus.ANULADO, TaxRegime.IVA);
        var loyalty = mock(MemberDocumentLoyaltyLine.class);
        when(loyalty.getDocumentLineId()).thenReturn(fixture.lineId());
        when(loyalty.isEligible()).thenReturn(true);
        when(loyalty.getEligibleAmount()).thenReturn(new BigDecimal("10.00"));
        when(fixture.loyaltyLines().findByDocumentId(fixture.ticketId()))
                .thenReturn(List.of(loyalty));
        var preview = fixture.service().preview(fixture.authentication());

        assertThatThrownBy(() -> fixture.service().resolve(
                request(fixture, preview, Map.of()),
                null, fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.document.previous_ticket_loyalty_snapshot_invalid");
    }

    @Test
    void rejectsReturnsMixedTicketsAndAChangedLatestSource() {
        var fixture = fixture(DocumentStatus.CONFIRMADO, TaxRegime.IVA);
        when(fixture.line().getOriginalDocumentLineId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> fixture.service().preview(fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.document.previous_ticket_not_importable");

        when(fixture.repository().findLatestPositiveConfirmedTicketIds(
                fixture.storeId(), fixture.terminalId(), PageRequest.of(0, 1)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> fixture.service().preview(fixture.authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.document.previous_ticket_not_found");
    }

    private static PosCashController.PreviousTicketImportRequest request(
            Fixture fixture,
            PreviousTicketImportView preview,
            Map<UUID, List<String>> serials) {
        return new PosCashController.PreviousTicketImportRequest(
                fixture.ticketId(), preview.fingerprint(), serials);
    }

    private static void settlement(Fixture fixture, String eligibleTotal) {
        var settlement = mock(MemberDocumentLoyaltySettlement.class);
        when(settlement.getEligibleDocumentAmount())
                .thenReturn(new BigDecimal(eligibleTotal));
        when(fixture.loyaltySettlements().findById(fixture.ticketId()))
                .thenReturn(Optional.of(settlement));
    }

    private static ProductPriceHistory historicalSalePrice(
            Fixture fixture,
            String amount) {
        var history = new ProductPriceHistory(
                fixture.productId(), ProductPriceHistoryType.VENTA,
                new BigDecimal(amount), CONFIRMED_AT.minusSeconds(60));
        when(fixture.priceHistory().findPriceEvidenceAtOrBefore(
                List.of(fixture.productId()), ProductPriceHistoryType.VENTA,
                CONFIRMED_AT)).thenReturn(List.of(history));
        return history;
    }

    private static Fixture fixture(DocumentStatus status, TaxRegime regime) {
        var storeId = UUID.randomUUID();
        var companyId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var ticketId = UUID.randomUUID();
        var lineId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var taxId = UUID.randomUUID();
        var authentication = mock(Authentication.class);

        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        var company = mock(Company.class);
        when(company.getId()).thenReturn(companyId);
        var organization = mock(CurrentOrganization.class);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentCompany()).thenReturn(company);
        var terminal = mock(CurrentTerminal.class);
        when(terminal.terminalId(authentication)).thenReturn(terminalId);

        var repository = mock(CommercialDocumentRepository.class);
        when(repository.findLatestPositiveConfirmedTicketIds(
                storeId, terminalId, PageRequest.of(0, 1)))
                .thenReturn(List.of(ticketId));
        when(repository.isExchangeSale(ticketId)).thenReturn(false);

        var line = mock(DocumentLine.class);
        when(line.getId()).thenReturn(lineId);
        when(line.getPosicion()).thenReturn(1);
        when(line.getLineType()).thenReturn(DocumentLineType.PRODUCT);
        when(line.getProductoId()).thenReturn(productId);
        when(line.getCantidad()).thenReturn(new BigDecimal("1.000"));
        when(line.getCodigo()).thenReturn("A");
        when(line.getNombre()).thenReturn("Producto A");
        when(line.getTarifa()).thenReturn("VENTA");
        when(line.getPrecioUnitario()).thenReturn(new BigDecimal("10.00"));
        when(line.getDescuento()).thenReturn(BigDecimal.ZERO);
        when(line.isImpuestosIncluidos()).thenReturn(true);
        when(line.getRegimenImpuesto()).thenReturn(regime.name());
        when(line.getPorcentajeImpuesto()).thenReturn(new BigDecimal("21.00"));
        when(line.getBase()).thenReturn(new BigDecimal("8.26"));
        when(line.getImpuesto()).thenReturn(new BigDecimal("1.74"));
        when(line.getTotal()).thenReturn(new BigDecimal("10.00"));
        when(line.getSerialNumbers()).thenReturn(List.of("ORIGINAL-1"));

        var ticket = mock(CommercialDocument.class);
        when(ticket.getId()).thenReturn(ticketId);
        when(ticket.getNumero()).thenReturn("001-260807-00001");
        when(ticket.getFecha()).thenReturn(LocalDate.of(2026, 8, 7));
        when(ticket.getEstado()).thenReturn(status);
        when(ticket.getConfirmadoEn()).thenReturn(CONFIRMED_AT);
        when(ticket.getTipo()).thenReturn(CommercialDocumentType.TICKET);
        when(ticket.getTiendaId()).thenReturn(storeId);
        when(ticket.getTerminalOrigenId()).thenReturn(terminalId);
        when(ticket.getLineas()).thenReturn(List.of(line));
        when(ticket.getMoneda()).thenReturn("EUR");
        when(ticket.getDescuentoGlobal()).thenReturn(BigDecimal.ZERO);
        when(ticket.getBaseTotal()).thenReturn(new BigDecimal("8.26"));
        when(ticket.getImpuestoTotal()).thenReturn(new BigDecimal("1.74"));
        when(ticket.getTotal()).thenReturn(new BigDecimal("10.00"));
        when(repository.findByIdAndTiendaId(ticketId, storeId))
                .thenReturn(Optional.of(ticket));

        var product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getCode()).thenReturn("A");
        when(product.getName()).thenReturn("Producto A");
        when(product.getSalePrice()).thenReturn(new BigDecimal("10.00"));
        when(product.getTaxId()).thenReturn(taxId);
        when(product.isActive()).thenReturn(true);
        when(product.isTaxesIncluded()).thenReturn(true);
        when(product.getProductType()).thenReturn(ProductType.UNIT);
        var productRepository = mock(ProductRepository.class);
        when(productRepository.findAllByStoreIdAndIdIn(storeId, List.of(productId)))
                .thenReturn(List.of(product));
        var priceHistory = mock(ProductPriceHistoryRepository.class);

        var tax = mock(StoreTax.class);
        when(tax.getId()).thenReturn(taxId);
        when(tax.getStoreId()).thenReturn(storeId);
        when(tax.isActive()).thenReturn(true);
        when(tax.getPercentage()).thenReturn(new BigDecimal("21.00"));
        var taxRepository = mock(StoreTaxRepository.class);
        when(taxRepository.findAllById(List.of(taxId))).thenReturn(List.of(tax));

        var license = mock(License.class);
        when(license.isActiva()).thenReturn(true);
        when(license.getTiendaId()).thenReturn(storeId);
        when(license.getRegimenImpuesto()).thenReturn(regime);
        var licenseRepository = mock(LicenseRepository.class);
        when(licenseRepository.findByTiendaIdOrderByValidaDesdeDesc(storeId))
                .thenReturn(List.of(license));
        var customerRepository = mock(CustomerRepository.class);
        var installationStatus = mock(InstallationStatusService.class);
        var loyaltyLines = mock(MemberDocumentLoyaltyLineRepository.class);
        when(loyaltyLines.findByDocumentId(ticketId)).thenReturn(List.of());
        var loyaltySettlements = mock(MemberDocumentLoyaltySettlementRepository.class);

        return new Fixture(
                new PreviousTicketImportService(
                        repository, productRepository, priceHistory, taxRepository,
                        licenseRepository,
                        installationStatus, customerRepository, loyaltyLines,
                        loyaltySettlements,
                        organization, terminal),
                repository, productRepository, priceHistory,
                loyaltyLines, loyaltySettlements,
                authentication, ticket, line, product, tax,
                storeId, terminalId, ticketId, lineId, productId);
    }

    private record Fixture(
            PreviousTicketImportService service,
            CommercialDocumentRepository repository,
            ProductRepository products,
            ProductPriceHistoryRepository priceHistory,
            MemberDocumentLoyaltyLineRepository loyaltyLines,
            MemberDocumentLoyaltySettlementRepository loyaltySettlements,
            Authentication authentication,
            CommercialDocument ticket,
            DocumentLine line,
            Product product,
            StoreTax tax,
            UUID storeId,
            UUID terminalId,
            UUID ticketId,
            UUID lineId,
            UUID productId) {

        DocumentLine adjustment(
                DocumentLineType lineType,
                String name,
                String total) {
            var line = mock(DocumentLine.class);
            when(line.getId()).thenReturn(UUID.randomUUID());
            when(line.getPosicion()).thenReturn(2);
            when(line.getLineType()).thenReturn(lineType);
            when(line.getNombre()).thenReturn(name);
            when(line.getPrecioUnitario()).thenReturn(new BigDecimal(total));
            when(line.getDescuento()).thenReturn(BigDecimal.ZERO);
            when(line.getCantidad()).thenReturn(BigDecimal.ONE);
            when(line.isImpuestosIncluidos()).thenReturn(true);
            when(line.getRegimenImpuesto()).thenReturn("IVA");
            when(line.getPorcentajeImpuesto()).thenReturn(new BigDecimal("21.00"));
            when(line.getBase()).thenReturn(new BigDecimal("-0.83"));
            when(line.getImpuesto()).thenReturn(new BigDecimal("-0.17"));
            when(line.getTotal()).thenReturn(new BigDecimal(total));
            return line;
        }
    }
}
