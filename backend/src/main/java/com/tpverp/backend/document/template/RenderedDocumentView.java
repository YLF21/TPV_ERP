package com.tpverp.backend.document.template;

import java.util.Base64;
import java.util.Objects;

/** Transport-neutral rendered artifacts used by APP VENTA and APP GESTIÓN. */
public record RenderedDocumentView(
        TemplateView template,
        RenderedArtifact renderedPdf,
        RenderedArtifact ticketRenderedImage,
        String fileName) {

    public RenderedDocumentView {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(renderedPdf, "renderedPdf");
        fileName = fileName == null || fileName.isBlank() ? "documento.pdf" : fileName;
    }

    public static RenderedDocumentView from(
            ResolvedDocumentTemplate resolved,
            InvoiceJasperRenderer.RenderedDocument rendered,
            String fileName) {
        var image = rendered.ticketRasterPng() == null ? null
                : new RenderedArtifact("image/png", Base64.getEncoder().encodeToString(
                        rendered.ticketRasterPng()));
        return new RenderedDocumentView(
                new TemplateView(resolved.type(), resolved.format(), resolved.code(),
                        resolved.version(), resolved.schemaVersion(), resolved.sha256(),
                        resolved.builtIn()),
                new RenderedArtifact("application/pdf",
                        Base64.getEncoder().encodeToString(rendered.pdf())),
                image,
                fileName);
    }

    public record TemplateView(
            DocumentTemplateType type,
            DocumentTemplateFormat format,
            String code,
            int version,
            int schemaVersion,
            String sha256,
            boolean builtIn) {
    }

    public record RenderedArtifact(String contentType, String base64) {
        public RenderedArtifact {
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(base64, "base64");
        }
    }
}
