package com.tpverp.backend.document;

import java.util.Base64;

public record SalesActivityRenderedReportView(
        RenderedFile renderedPdf,
        RenderedFile renderedImage) {

    public static SalesActivityRenderedReportView from(byte[] pdf, byte[] image) {
        return new SalesActivityRenderedReportView(
                new RenderedFile("application/pdf", Base64.getEncoder().encodeToString(pdf)),
                image == null ? null : new RenderedFile(
                        "image/png", Base64.getEncoder().encodeToString(image)));
    }

    public record RenderedFile(String contentType, String base64) {
    }
}
