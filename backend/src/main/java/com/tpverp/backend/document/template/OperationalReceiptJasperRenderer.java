package com.tpverp.backend.document.template;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Renders the immutable database-backed receipts used by operational POS flows. */
@Service
public class OperationalReceiptJasperRenderer {

    private static final String RESOURCE_DIRECTORY = "reports/operational-receipts/";

    private final DataSource dataSource;
    private final Map<Template, byte[]> compiled = new ConcurrentHashMap<>();

    public OperationalReceiptJasperRenderer(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public RenderedReceipt renderCancellation(UUID ticketId) {
        Objects.requireNonNull(ticketId, "ticketId");
        return render(Template.CANCELLATION, Map.of(
                "DOCUMENTO_ID", ticketId.toString()));
    }

    public RenderedReceipt renderPendingCollection(UUID documentId, UUID paymentId) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(paymentId, "paymentId");
        return render(Template.PENDING_COLLECTION, Map.of(
                "DOCUMENTO_ID", documentId.toString(),
                "PAGO_ID", paymentId.toString()));
    }

    private RenderedReceipt render(Template template, Map<String, Object> values) {
        var parameters = new LinkedHashMap<String, Object>(values);
        try (var connection = dataSource.getConnection()) {
            var print = JasperFillManager.getInstance(SafeJrxmlCompiler.secureContext()).fill(
                    new ByteArrayInputStream(compiled(template)), parameters, connection);
            return new RenderedReceipt(
                    JasperExportManager.getInstance(SafeJrxmlCompiler.secureContext())
                            .exportToPdf(print),
                    InvoiceJasperRenderer.ticketRaster(print));
        } catch (IOException | SQLException | JRException exception) {
            throw new IllegalStateException(
                    "operational_receipt_jasper_render_failed:" + template.name(), exception);
        }
    }

    private byte[] compiled(Template template) {
        return compiled.computeIfAbsent(template, this::compile);
    }

    private byte[] compile(Template template) {
        var resource = new ClassPathResource(RESOURCE_DIRECTORY + template.filename);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "operational_receipt_jasper_source_missing:" + template.filename);
        }
        try (var input = resource.getInputStream();
                var output = new ByteArrayOutputStream()) {
            JasperCompileManager.getInstance(SafeJrxmlCompiler.secureContext())
                    .compileToStream(input, output);
            return output.toByteArray();
        } catch (IOException | JRException exception) {
            throw new IllegalStateException(
                    "operational_receipt_jasper_compile_failed:" + template.filename,
                    exception);
        }
    }

    public record RenderedReceipt(byte[] pdf, byte[] png) {
        public RenderedReceipt {
            Objects.requireNonNull(pdf, "pdf");
            Objects.requireNonNull(png, "png");
        }
    }

    private enum Template {
        CANCELLATION("ticket_anulado.jrxml"),
        PENDING_COLLECTION("cobro_pendiente.jrxml");

        private final String filename;

        Template(String filename) {
            this.filename = filename;
        }
    }
}
