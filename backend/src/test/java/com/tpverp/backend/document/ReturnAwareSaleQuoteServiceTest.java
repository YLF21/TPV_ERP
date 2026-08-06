package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.promotion.AuthoritativePromotionPricing;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReturnAwareSaleQuoteServiceTest {

    private final TicketReturnService ticketReturns = mock(TicketReturnService.class);
    private final ReturnAwareSaleQuoteService service =
            new ReturnAwareSaleQuoteService(ticketReturns);

    @Test
    void addsExplicitPromotionLossAdjustmentToPartialThreeForTwoReturn() {
        var fixture = quoteWithReturn("2.000", "10.00");
        when(ticketReturns.value(eq(fixture.sourceCode()), anyList()))
                .thenReturn(valuation("20.00", "10.00", "10.00"));

        var result = service.apply(fixture.quote(), List.of(fixture.command()));

        assertThat(result.getTotal()).isEqualByComparingTo("-10.00");
        assertThat(result.getLineas()).hasSize(2);
        assertThat(result.getLineas().getFirst().getTotal())
                .isEqualByComparingTo("-20.00");
        assertThat(result.getLineas().getLast().getLineType())
                .isEqualTo(DocumentLineType.RETURN_ADJUSTMENT);
        assertThat(result.getLineas().getLast().getTotal())
                .isEqualByComparingTo("10.00");
    }

    @Test
    void oneThreeForTwoUnitProducesZeroValueQuoteWithoutHidingItsLines() {
        var fixture = quoteWithReturn("1.000", "10.00");
        when(ticketReturns.value(eq(fixture.sourceCode()), anyList()))
                .thenReturn(valuation("10.00", "10.00", "0.00"));

        var result = service.apply(fixture.quote(), List.of(fixture.command()));

        assertThat(result.getTotal()).isEqualByComparingTo("0.00");
        assertThat(result.getLineas())
                .extracting(DocumentLine::getTotal)
                .containsExactly(new BigDecimal("-10.00"), new BigDecimal("10.00"));

        var product = mock(Product.class);
        when(product.getSalePrice()).thenReturn(new BigDecimal("10.00"));
        var request = new PosCashController.SaleRequest(
                null,
                List.of(new PosCashController.LineRequest(
                        fixture.productId(), BigDecimal.ONE.negate(), BigDecimal.ZERO)));
        var view = PosCashService.Quote.from(
                result,
                request,
                Map.of(fixture.productId(), product),
                AuthoritativePromotionPricing.CustomerContext.anonymous());

        assertThat(view.lineBreakdown())
                .extracting(PosCashService.AuthoritativeLineBreakdown::lineType)
                .containsExactly(DocumentLineType.PRODUCT, DocumentLineType.RETURN_ADJUSTMENT);
        assertThat(view.lineBreakdown())
                .extracting(PosCashService.AuthoritativeLineBreakdown::finalSubtotal)
                .containsExactly(new BigDecimal("-10.00"), new BigDecimal("10.00"));
    }

    @Test
    void reconcilesAnOriginalGlobalDiscountThatIsNotCopiedToTheNewSale() {
        var fixture = quoteWithReturn("1.000", "10.00");
        when(ticketReturns.value(eq(fixture.sourceCode()), anyList()))
                .thenReturn(valuation("8.00", "0.00", "8.00"));

        var result = service.apply(fixture.quote(), List.of(fixture.command()));

        assertThat(result.getTotal()).isEqualByComparingTo("-8.00");
        assertThat(result.getLineas().getLast().getLineType())
                .isEqualTo(DocumentLineType.RETURN_ADJUSTMENT);
        assertThat(result.getLineas().getLast().getTotal())
                .isEqualByComparingTo("2.00");
    }

    private static Fixture quoteWithReturn(String quantity, String unitPrice) {
        var storeId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var sourceTicketId = UUID.randomUUID();
        var sourceLineId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var sourceCode = "001-260806-00003";
        var quote = new CommercialDocument(
                storeId, warehouseId, CommercialDocumentType.TICKET,
                LocalDate.of(2026, 8, 6), UUID.randomUUID(), BigDecimal.ZERO);
        var command = new DocumentLineCommand(
                productId,
                new BigDecimal(quantity).negate(),
                "P-1",
                "Producto 3x2",
                "VENTA",
                new BigDecimal(unitPrice),
                BigDecimal.ZERO,
                true,
                "IVA",
                new BigDecimal("21.00"),
                DocumentLineType.PRODUCT,
                null,
                null,
                null,
                List.of(),
                false,
                false,
                TicketReturnService.ReturnSourceType.TICKET,
                sourceCode,
                sourceTicketId,
                sourceLineId,
                null);
        quote.addLine(command.toEntity(quote, 1));
        return new Fixture(quote, command, sourceCode, productId);
    }

    private static TicketReturnValuationService.Valuation valuation(
            String selectedGross,
            String lostBenefits,
            String refundable) {
        return new TicketReturnValuationService.Valuation(
                new BigDecimal(selectedGross),
                new BigDecimal(lostBenefits),
                new BigDecimal(refundable),
                new BigDecimal(refundable),
                new BigDecimal(refundable),
                new BigDecimal(refundable),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(new TicketReturnValuationService.TaxAdjustment(
                        true,
                        "IVA",
                        new BigDecimal("21.00"),
                        new BigDecimal(lostBenefits))));
    }

    private record Fixture(
            CommercialDocument quote,
            DocumentLineCommand command,
            String sourceCode,
            UUID productId) {
    }
}
