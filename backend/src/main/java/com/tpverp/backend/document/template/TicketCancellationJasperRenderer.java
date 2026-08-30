package com.tpverp.backend.document.template;

import com.tpverp.backend.document.Money;
import com.tpverp.backend.document.PaymentMethodPrintLabel;
import com.tpverp.backend.document.TicketCancellationService;
import com.tpverp.backend.organization.Store;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.stream.Collectors;
import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Generates the non-fiscal receipt produced after a full ticket cancellation. */
@Service
public class TicketCancellationJasperRenderer {

    private static final String TEMPLATE =
            "reports/tickets/TICKET_ANULADO_TICKET_80.jrxml";
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final SafeJrxmlCompiler compiler;
    private volatile byte[] compiledTemplate;

    public TicketCancellationJasperRenderer(SafeJrxmlCompiler compiler) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    public RenderedCancellation render(
            TicketCancellationService.CancellationReceipt receipt,
            Store store) {
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(store, "store");
        var company = store.getEmpresa();
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("ISSUER_NAME", company.getRazonSocial());
        parameters.put("STORE_NAME", store.getNombreEfectivo());
        parameters.put("ISSUER_TAX_ID", company.getTaxId());
        parameters.put("ORIGINAL_TICKET_NUMBER", text(receipt.originalTicketNumber()));
        parameters.put("ORIGINAL_ISSUED_AT", date(receipt.originalIssuedAt(), store));
        parameters.put("CANCELLED_AT", date(receipt.cancelledAt(), store));
        parameters.put("TOTAL_FORMATTED", Money.euros(receipt.total())
                .setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " " + store.getMoneda());
        parameters.put("REASON", text(receipt.reason()));
        parameters.put("OPERATOR_USERNAME", text(receipt.operatorUsername()));
        parameters.put("AUTHORIZER_USERNAME", text(receipt.authorizerUsername()));
        parameters.put("AUTHORIZATION_TYPE", receipt.delegated()
                ? "Autorización delegada" : "Autorización del propio operador");
        parameters.put("PAYMENTS_TEXT", paymentLines(receipt));
        parameters.put("OPERATION_ID", receipt.operationId().toString());

        try {
            var context = SafeJrxmlCompiler.secureContext();
            var print = JasperFillManager.getInstance(context).fill(
                    new ByteArrayInputStream(compiledTemplate()),
                    parameters,
                    new JREmptyDataSource(1));
            return new RenderedCancellation(
                    JasperExportManager.getInstance(context).exportToPdf(print),
                    InvoiceJasperRenderer.ticketRaster(print));
        } catch (JRException | IOException exception) {
            throw new IllegalStateException(
                    "ticket_cancellation_jasper_render_failed", exception);
        }
    }

    private byte[] compiledTemplate() throws IOException {
        byte[] current = compiledTemplate;
        if (current != null) return current;
        synchronized (this) {
            current = compiledTemplate;
            if (current != null) return current;
            var resource = new ClassPathResource(TEMPLATE);
            if (!resource.exists()) {
                throw new IOException("ticket_cancellation_jasper_template_missing");
            }
            try (var input = resource.getInputStream()) {
                compiledTemplate = compiler.compile(input.readAllBytes()).compiled();
                return compiledTemplate;
            }
        }
    }

    private static String paymentLines(
            TicketCancellationService.CancellationReceipt receipt) {
        if (receipt.payments() == null || receipt.payments().isEmpty()) return "Sin pagos";
        return receipt.payments().stream()
                .map(payment -> PaymentMethodPrintLabel.format(payment.method()) + "  "
                        + Money.euros(payment.amount()).setScale(2, RoundingMode.HALF_UP)
                                .toPlainString()
                        + (payment.reference() == null || payment.reference().isBlank()
                                ? "" : "  Ref: " + payment.reference().trim()))
                .collect(Collectors.joining("\n"));
    }

    private static String date(Instant value, Store store) {
        return value == null ? "—" : DATE_TIME.withZone(ZoneId.of(store.getTimezone()))
                .format(value);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "—" : value.trim();
    }

    public record RenderedCancellation(byte[] pdf, byte[] png) {
        public RenderedCancellation {
            Objects.requireNonNull(pdf, "pdf");
            Objects.requireNonNull(png, "png");
        }
    }
}
