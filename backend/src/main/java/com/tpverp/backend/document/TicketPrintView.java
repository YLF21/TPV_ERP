package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import java.util.UUID;

public record TicketPrintView(
        UUID documentId,
        String documentNumber,
        Instant issuedAt,
        List<Line> lines,
        List<Payment> payments,
        BigDecimal total,
        BigDecimal baseTotal,
        BigDecimal taxTotal,
        BigDecimal checkoutDiscountTotal,
        BigDecimal memberBalanceTotal,
        String observations,
        String logo,
        String qrUrl,
        String qrImage,
        FiscalPrintView fiscal,
        RenderedPdf ticketRenderedPdf,
        RenderedImage ticketRenderedImage,
        boolean nonFiscalSummary) {

    public TicketPrintView(
            UUID documentId,
            String documentNumber,
            Instant issuedAt,
            List<Line> lines,
            List<Payment> payments,
            BigDecimal total,
            BigDecimal baseTotal,
            BigDecimal taxTotal,
            BigDecimal checkoutDiscountTotal,
            BigDecimal memberBalanceTotal,
            String observations,
            String logo,
            String qrUrl,
            String qrImage,
            FiscalPrintView fiscal,
            RenderedPdf ticketRenderedPdf,
            RenderedImage ticketRenderedImage) {
        this(documentId, documentNumber, issuedAt, lines, payments, total,
                baseTotal, taxTotal, checkoutDiscountTotal, memberBalanceTotal,
                observations, logo, qrUrl, qrImage, fiscal,
                ticketRenderedPdf, ticketRenderedImage, false);
    }

    public TicketPrintView(
            UUID documentId,
            String documentNumber,
            Instant issuedAt,
            List<Line> lines,
            List<Payment> payments,
            BigDecimal total,
            BigDecimal baseTotal,
            BigDecimal taxTotal,
            BigDecimal checkoutDiscountTotal,
            BigDecimal memberBalanceTotal,
            String observations,
            String logo,
            String qrUrl,
            String qrImage,
            RenderedPdf ticketRenderedPdf,
            RenderedImage ticketRenderedImage) {
        this(documentId, documentNumber, issuedAt, lines, payments, total,
                baseTotal, taxTotal, checkoutDiscountTotal, memberBalanceTotal,
                observations, logo, qrUrl, qrImage, null,
                ticketRenderedPdf, ticketRenderedImage, false);
    }

    public TicketPrintView(
            UUID documentId,
            String documentNumber,
            Instant issuedAt,
            List<Line> lines,
            List<Payment> payments,
            BigDecimal total) {
        this(documentId, documentNumber, issuedAt, lines, payments, total,
                null, null, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null, null, null);
    }

    public TicketPrintView(
            UUID documentId,
            String documentNumber,
            Instant issuedAt,
            List<Line> lines,
            List<Payment> payments,
            BigDecimal total,
            BigDecimal baseTotal,
            BigDecimal taxTotal) {
        this(documentId, documentNumber, issuedAt, lines, payments, total,
                baseTotal, taxTotal, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null, null, null);
    }

    public static TicketPrintView from(CommercialDocument document) {
        return from(document, List.of());
    }

    public static TicketPrintView from(CommercialDocument document, List<RefundTender> refundPayouts) {
        requirePrintable(document);
        return new TicketPrintView(
                document.getId(), document.getNumero(), document.getConfirmadoEn(),
                document.getLineas().stream()
                        .filter(TicketPrintView::isPrintableDetail)
                        .map(line -> new Line(line.getNombre(), line.getCantidad(),
                                line.getPrecioUnitario(), line.getTotal(),
                                line.getSerialNumbers(), line.getCodigo(),
                                line.getCodigoBarras()))
                        .toList(),
                refundPayouts == null || refundPayouts.isEmpty()
                        ? document.getPagos().stream()
                                .map(payment -> new Payment(
                                        PaymentMethodPrintLabel.format(
                                                payment.getMetodoPago().getNombre()),
                                        payment.getImporte()))
                                .toList()
                        : refundPayouts.stream()
                                .map(payout -> new Payment(
                                        PaymentMethodPrintLabel.format(switch (payout.getType()) {
                                            case CASH -> "EFECTIVO";
                                            case CARD -> "TARJETA";
                                            case VOUCHER -> "VALE";
                                            case TRANSFER -> "TRANSFERENCIA";
                                            case EXCHANGE -> PaymentMethodService.EXCHANGE_COMPENSATION_METHOD;
                                            case MEMBER_CREDIT -> "CREDITO_DEVOLUCION";
                                        }), payout.getAmount().negate()))
                                .toList(),
                document.getTotal(),
                document.getBaseTotal(),
                document.getImpuestoTotal(),
                checkoutDiscountTotal(document.getLineas()),
                DocumentLineTotals.memberBalanceTotal(document.getLineas()),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false);
    }

    /**
     * Prints one customer-facing exchange receipt while keeping the fiscal
     * rectification and the new sale as two independently traceable documents.
     */
    public static TicketPrintView fromExchange(
            CommercialDocument sale,
            CommercialDocument refund) {
        requirePrintable(sale);
        requirePrintable(refund);
        var lines = Stream.concat(refund.getLineas().stream(), sale.getLineas().stream())
                .filter(TicketPrintView::isPrintableDetail)
                .map(line -> new Line(line.getNombre(), line.getCantidad(),
                        line.getPrecioUnitario(), line.getTotal(), line.getSerialNumbers(),
                        line.getCodigo(), line.getCodigoBarras()))
                .toList();
        var payments = sale.getPagos().stream()
                .filter(payment -> !PaymentMethodService.EXCHANGE_COMPENSATION_METHOD
                        .equals(payment.getMetodoPago().getNombre()))
                .map(payment -> new Payment(
                        PaymentMethodPrintLabel.format(payment.getMetodoPago().getNombre()),
                        payment.getImporte()))
                .toList();
        return new TicketPrintView(
                sale.getId(), sale.getNumero(), sale.getConfirmadoEn(), lines, payments,
                Money.euros(sale.getTotal().add(refund.getTotal())),
                Money.euros(sale.getBaseTotal().add(refund.getBaseTotal())),
                Money.euros(sale.getImpuestoTotal().add(refund.getImpuestoTotal())),
                Money.euros(checkoutDiscountTotal(sale.getLineas())
                        .add(checkoutDiscountTotal(refund.getLineas()))),
                Money.euros(DocumentLineTotals.memberBalanceTotal(sale.getLineas())
                        .add(DocumentLineTotals.memberBalanceTotal(refund.getLineas()))),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true);
    }

    public TicketPrintView withPresentation(String observations, String logo) {
        return new TicketPrintView(
                documentId, documentNumber, issuedAt, lines, payments, total,
                baseTotal, taxTotal, checkoutDiscountTotal, memberBalanceTotal,
                observations, logo,
                qrUrl, qrImage, fiscal,
                ticketRenderedPdf, ticketRenderedImage, nonFiscalSummary);
    }

    public TicketPrintView withFiscalQr(String qrUrl, String qrImage) {
        return withFiscalQr(qrUrl, qrImage, fiscal);
    }

    public TicketPrintView withFiscalQr(
            String qrUrl, String qrImage, FiscalPrintView fiscal) {
        if (nonFiscalSummary && (qrUrl != null || qrImage != null || fiscal != null)) {
            throw new IllegalStateException("non_fiscal_summary_cannot_contain_fiscal_qr");
        }
        return new TicketPrintView(
                documentId, documentNumber, issuedAt, lines, payments, total,
                baseTotal, taxTotal, checkoutDiscountTotal, memberBalanceTotal,
                observations, logo, qrUrl, qrImage, fiscal,
                ticketRenderedPdf, ticketRenderedImage, nonFiscalSummary);
    }

    public TicketPrintView withRenderedDocument(byte[] pdf, byte[] png) {
        return new TicketPrintView(
                documentId, documentNumber, issuedAt, lines, payments, total,
                baseTotal, taxTotal, checkoutDiscountTotal, memberBalanceTotal,
                observations, logo,
                qrUrl, qrImage, fiscal,
                new RenderedPdf("application/pdf", Base64.getEncoder().encodeToString(pdf)),
                new RenderedImage("image/png", Base64.getEncoder().encodeToString(png)),
                nonFiscalSummary);
    }

    private static BigDecimal checkoutDiscountTotal(List<DocumentLine> lines) {
        return Money.euros(lines.stream()
                .filter(line -> line.getLineType() == DocumentLineType.MANUAL_DISCOUNT)
                .map(DocumentLine::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .negate());
    }

    private static boolean isPrintableDetail(DocumentLine line) {
        return line.getLineType() != DocumentLineType.MANUAL_DISCOUNT
                && line.getLineType() != DocumentLineType.MEMBER_BALANCE;
    }

    private static void requirePrintable(CommercialDocument document) {
        var status = document.getEstado();
        boolean finalized = status == DocumentStatus.CONFIRMADO
                || status == DocumentStatus.PENDIENTE
                || status == DocumentStatus.PARCIAL
                || status == DocumentStatus.PAGADO;
        if (!finalized || document.getConfirmadoEn() == null) {
            throw new IllegalArgumentException(
                    "message.document.print_ticket_requires_confirmed_document");
        }
    }

    public record Line(
            String name,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal total,
            List<String> serialNumbers,
            String code,
            String barcode) {
        public Line(String name, BigDecimal quantity, BigDecimal price,
                BigDecimal total, List<String> serialNumbers) {
            this(name, quantity, price, total, serialNumbers, null, null);
        }

        public Line(String name, BigDecimal quantity, BigDecimal price, BigDecimal total) {
            this(name, quantity, price, total, List.of(), null, null);
        }
    }

    public record Payment(String method, BigDecimal amount) {}

    public record RenderedPdf(String contentType, String base64) {}

    public record RenderedImage(String contentType, String base64) {}
}
