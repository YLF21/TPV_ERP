package com.tpverp.backend.document.template;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import javax.sql.DataSource;
import com.tpverp.backend.organization.StoreDocumentPrintConfigurationService;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import org.springframework.stereotype.Service;

/** Renders the built-in, database-backed thermal ticket reports. */
@Service
public class TicketJasperRenderer {

    private final DataSource dataSource;
    private final DocumentTemplateResolver templates;
    private final DocumentTemplateArtifactStorage storage;
    private final StoreDocumentPrintConfigurationService printConfiguration;
    private final BuiltInTicketJasperBundle builtInBundle;

    public TicketJasperRenderer(
            DataSource dataSource,
            DocumentTemplateResolver templates,
            DocumentTemplateArtifactStorage storage,
            StoreDocumentPrintConfigurationService printConfiguration,
            BuiltInTicketJasperBundle builtInBundle) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.printConfiguration = Objects.requireNonNull(
                printConfiguration, "printConfiguration");
        this.builtInBundle = Objects.requireNonNull(builtInBundle, "builtInBundle");
    }

    public Template selectedTemplate() {
        return Template.valueOf(printConfiguration.ticketStyle().name());
    }

    public byte[] render(CommercialDocument document, Template template) {
        return renderDocument(document, template).pdf();
    }

    public RenderedTicket renderForPrint(CommercialDocument document) {
        return renderDocument(document, selectedTemplate());
    }

    private RenderedTicket renderDocument(CommercialDocument document, Template template) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(template, "template");
        if (document.getTipo() != CommercialDocumentType.TICKET) {
            throw new IllegalArgumentException("ticket_jasper_requires_ticket");
        }
        if (document.getTiendaId() == null || document.getId() == null) {
            throw new IllegalArgumentException("ticket_jasper_document_not_persisted");
        }

        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("TIENDA_ID", document.getTiendaId().toString());
        parameters.put("DOCUMENTO_ID", document.getId().toString());
        parameters.put("PLANTILLA", template.reportValue());

        var resolved = templates.resolve(DocumentTemplateType.TICKET);
        try (var connection = dataSource.getConnection()) {
            boolean useBuiltInBundle = resolved.builtIn()
                    || !storage.isBundle(resolved.artifactReference());
            java.nio.file.Path master;
            if (useBuiltInBundle) {
                master = builtInBundle.compiledMaster();
            } else {
                var sources = storage.readBundleSources(resolved.artifactReference());
                if (!TicketJrxmlBundleCompiler.bundleSha256(sources)
                        .equals(resolved.sha256())) {
                    throw new IllegalStateException(
                            "document_template_artifact_integrity_failed");
                }
                master = storage.compiledBundleMaster(
                        resolved.artifactReference(), TicketJrxmlBundleCompiler.MASTER_FILENAME);
            }
            parameters.put(TicketJrxmlBundleCompiler.SUBREPORT_DIRECTORY_PARAMETER,
                    master.getParent().toAbsolutePath().toString()
                            + java.io.File.separator);
            var print = JasperFillManager.fillReport(
                    master.toAbsolutePath().toString(), parameters, connection);
            return new RenderedTicket(
                    JasperExportManager.exportReportToPdf(print),
                    InvoiceJasperRenderer.ticketRaster(print));
        } catch (IOException | SQLException | JRException exception) {
            throw new IllegalStateException("ticket_jasper_render_failed", exception);
        }
    }

    public record RenderedTicket(byte[] pdf, byte[] png) {
        public RenderedTicket {
            Objects.requireNonNull(pdf, "pdf");
            Objects.requireNonNull(png, "png");
        }
    }

    public enum Template {
        PRINCIPAL,
        COMPACTA,
        MINIMALISTA;

        public static Template parse(String value) {
            if (value == null || value.isBlank()) return PRINCIPAL;
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if ("COMPACTO".equals(normalized)) normalized = "COMPACTA";
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("ticket_jasper_template_invalid", exception);
            }
        }

        String reportValue() {
            return name();
        }
    }
}
