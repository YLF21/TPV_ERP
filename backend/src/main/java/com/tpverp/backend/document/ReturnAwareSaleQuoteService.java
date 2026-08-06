package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Applies the historical value of ticket-return lines to a transient sale quote.
 *
 * <p>The ordinary pricing engine values the selected products at their gross
 * historical unit price. Partial returns from grouped promotions may be worth
 * less because the basket kept by the customer must be repriced. The difference
 * is represented as an explicit positive return-adjustment line so cart totals,
 * payment reservation and the final rectification share the same tax-aware
 * valuation.</p>
 */
@Service
public class ReturnAwareSaleQuoteService {

    static final String ADJUSTMENT_DESCRIPTION = "AJUSTE POR PERDIDA DE PROMOCION";

    private final TicketReturnService ticketReturns;

    public ReturnAwareSaleQuoteService(TicketReturnService ticketReturns) {
        this.ticketReturns = ticketReturns;
    }

    public CommercialDocument apply(
            CommercialDocument quote,
            List<DocumentLineCommand> requestedLines) {
        Objects.requireNonNull(quote, "quote");
        var returnLines = List.copyOf(requestedLines == null ? List.of() : requestedLines)
                .stream()
                .filter(line -> line.lineType() == DocumentLineType.PRODUCT)
                .filter(line -> line.cantidad().signum() < 0)
                .filter(line -> line.originalDocumentLineId() != null)
                .toList();
        if (returnLines.isEmpty()) {
            return quote;
        }

        var sourceCodes = returnLines.stream()
                .map(DocumentLineCommand::returnSourceCode)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (sourceCodes.size() != 1) {
            throw new IllegalArgumentException("refund_requires_single_source_ticket");
        }

        var selected = new LinkedHashMap<java.util.UUID, BigDecimal>();
        returnLines.forEach(line -> selected.merge(
                line.originalDocumentLineId(), line.cantidad().abs(), BigDecimal::add));
        var selections = selected.entrySet().stream()
                .map(entry -> new TicketReturnService.ReturnSelection(
                        entry.getKey(), entry.getValue()))
                .toList();
        var valuation = ticketReturns.value(sourceCodes.getFirst(), selections);

        var quotedReturnLines = quote.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .filter(line -> line.getCantidad().signum() < 0)
                .filter(line -> line.getOriginalDocumentLineId() != null)
                .toList();
        var quotedReturnGross = Money.euros(quotedReturnLines.stream()
                .map(DocumentLine::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .abs()
                .multiply(BigDecimal.ONE.subtract(
                        quote.getDescuentoGlobal().movePointLeft(2))));
        var requiredAdjustment = Money.euros(
                quotedReturnGross.subtract(valuation.refundableAmount()));
        if (requiredAdjustment.signum() < 0) {
            throw new IllegalStateException("return_quote_valuation_mismatch");
        }
        var adjustments = quoteAdjustments(
                valuation.taxAdjustments(),
                quotedReturnLines,
                Money.euros(quotedReturnGross.subtract(valuation.selectedGross())));

        var globalFactor = BigDecimal.ONE.subtract(
                quote.getDescuentoGlobal().movePointLeft(2));
        if (globalFactor.signum() <= 0 && requiredAdjustment.signum() > 0) {
            throw new IllegalStateException(
                    "No se puede valorar una devolucion con descuento global del 100%");
        }
        for (var adjustment : adjustments) {
            if (adjustment.amount().signum() == 0) {
                continue;
            }
            var amountBeforeGlobal = Money.euros(adjustment.amount()
                    .divide(globalFactor, Money.SCALE + 6, Money.ROUNDING));
            quote.addLine(DocumentLine.returnAdjustment(
                    quote,
                    quote.getLineas().size() + 1,
                    ADJUSTMENT_DESCRIPTION,
                    amountBeforeGlobal,
                    adjustment.taxesIncluded(),
                    adjustment.taxRegime(),
                    adjustment.taxPercentage()));
        }

        var representedAdjustment = Money.euros(adjustments.stream()
                .map(TicketReturnValuationService.TaxAdjustment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .multiply(globalFactor));
        if (representedAdjustment.compareTo(requiredAdjustment) != 0) {
            throw new IllegalStateException("return_quote_valuation_mismatch");
        }
        return quote;
    }

    private static List<TicketReturnValuationService.TaxAdjustment> quoteAdjustments(
            List<TicketReturnValuationService.TaxAdjustment> historical,
            List<DocumentLine> quotedReturnLines,
            BigDecimal representationDelta) {
        var amounts = new LinkedHashMap<TaxKey, BigDecimal>();
        historical.forEach(adjustment -> amounts.merge(
                new TaxKey(adjustment.taxesIncluded(), adjustment.taxRegime(),
                        adjustment.taxPercentage()),
                adjustment.amount(),
                BigDecimal::add));
        var remaining = Money.euros(representationDelta);
        if (remaining.signum() < 0) {
            throw new IllegalStateException("return_quote_valuation_mismatch");
        }
        var weights = new LinkedHashMap<TaxKey, BigDecimal>();
        quotedReturnLines.forEach(line -> weights.merge(
                new TaxKey(line.isImpuestosIncluidos(), line.getRegimenImpuesto(),
                        line.getPorcentajeImpuesto()),
                line.getTotal().abs(),
                BigDecimal::add));
        var weightTotal = weights.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var entries = List.copyOf(weights.entrySet());
        for (int index = 0; index < entries.size() && remaining.signum() > 0; index++) {
            var entry = entries.get(index);
            var amount = index == entries.size() - 1
                    ? remaining
                    : Money.euros(representationDelta.multiply(entry.getValue())
                            .divide(weightTotal, Money.SCALE + 6, Money.ROUNDING));
            amounts.merge(entry.getKey(), amount, BigDecimal::add);
            remaining = Money.euros(remaining.subtract(amount));
        }
        return amounts.entrySet().stream()
                .filter(entry -> entry.getValue().signum() != 0)
                .map(entry -> new TicketReturnValuationService.TaxAdjustment(
                        entry.getKey().taxesIncluded(),
                        entry.getKey().taxRegime(),
                        entry.getKey().taxPercentage(),
                        Money.euros(entry.getValue())))
                .toList();
    }

    private record TaxKey(
            boolean taxesIncluded,
            String taxRegime,
            BigDecimal taxPercentage) {
    }
}
