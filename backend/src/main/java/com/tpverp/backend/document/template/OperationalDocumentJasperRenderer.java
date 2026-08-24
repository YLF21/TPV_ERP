package com.tpverp.backend.document.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.StoreDocumentPrintConfigurationService;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Renders non-fiscal operational documents through the same safe JSON/Jasper pipeline. */
@Service
public class OperationalDocumentJasperRenderer {

    private final CurrentOrganization organization;
    private final ObjectMapper mapper;
    private final DocumentTemplateResolver resolver;
    private final BuiltInDocumentJrxmlCatalog builtIns;
    private final InvoiceJasperRenderer renderer;
    private final StoreDocumentPrintConfigurationService printConfiguration;

    public OperationalDocumentJasperRenderer(
            CurrentOrganization organization,
            ObjectMapper mapper,
            DocumentTemplateResolver resolver,
            BuiltInDocumentJrxmlCatalog builtIns,
            InvoiceJasperRenderer renderer,
            StoreDocumentPrintConfigurationService printConfiguration) {
        this.organization = organization;
        this.mapper = mapper;
        this.resolver = resolver;
        this.builtIns = builtIns;
        this.renderer = renderer;
        this.printConfiguration = printConfiguration;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public RenderedDocumentView render(
            DocumentTemplateType type,
            DocumentTemplateFormat format,
            ObjectNode data,
            String fileName) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(data, "data");
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var resolved = resolver.resolve(store, type, format);
        var reference = resolved.builtIn()
                ? builtIns.reference(type, format)
                : new com.tpverp.backend.document.InvoicePresentationSnapshot.TemplateReference(
                        resolved.id(), resolved.code(), resolved.version(),
                        resolved.schemaVersion(), resolved.sha256(), false);
        var presentation = printConfiguration.presentation(type);
        String logo = presentation.logo() == null ? null
                : printConfiguration.logoDataUri(store.getId(), presentation.logo());
        var rendered = renderer.renderPayload(reference, type, format, store, company, data, logo)
                .orElseThrow(() -> new IllegalStateException("document_jasper_render_empty"));
        return RenderedDocumentView.from(resolved, rendered, fileName);
    }
}
