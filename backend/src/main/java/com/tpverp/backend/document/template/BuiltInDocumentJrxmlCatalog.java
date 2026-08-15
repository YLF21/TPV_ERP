package com.tpverp.backend.document.template;

import com.tpverp.backend.document.InvoicePresentationSnapshot;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Loads and verifies the immutable JRXML models shipped with the application. */
@Component
public class BuiltInDocumentJrxmlCatalog {

    private static final int VERSION = 1;
    private static final String RESOURCE_ROOT = "reports/documents/v1/";
    private static final Map<DocumentTemplateType, Map<DocumentTemplateFormat, String>> FILES =
            files();

    private final SafeJrxmlCompiler compiler;
    private final Map<Key, SafeJrxmlCompiler.CompiledTemplate> compiled =
            new ConcurrentHashMap<>();

    public BuiltInDocumentJrxmlCatalog(SafeJrxmlCompiler compiler) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    public InvoicePresentationSnapshot.TemplateReference reference(
            DocumentTemplateType type, DocumentTemplateFormat format) {
        var value = compiled(type, format);
        return new InvoicePresentationSnapshot.TemplateReference(
                null,
                code(type, format),
                VERSION,
                SafeJrxmlCompiler.DATA_SCHEMA_VERSION,
                value.sha256(),
                true);
    }

    public byte[] compiled(
            InvoicePresentationSnapshot.TemplateReference reference,
            DocumentTemplateType type,
            DocumentTemplateFormat format) {
        Objects.requireNonNull(reference, "reference");
        if (!reference.builtIn()
                || !code(type, format).equals(reference.code())
                || reference.version() != VERSION
                || reference.dataSchemaVersion() != SafeJrxmlCompiler.DATA_SCHEMA_VERSION) {
            throw new IllegalStateException("document_builtin_template_reference_mismatch");
        }
        var value = compiled(type, format);
        if (reference.sha256() != null && !value.sha256().equals(reference.sha256())) {
            throw new IllegalStateException("document_builtin_template_reference_mismatch");
        }
        return value.compiled();
    }

    private SafeJrxmlCompiler.CompiledTemplate compiled(
            DocumentTemplateType type, DocumentTemplateFormat format) {
        var key = new Key(type, format);
        return compiled.computeIfAbsent(key, ignored -> compiler.compile(source(type, format)));
    }

    private static byte[] source(
            DocumentTemplateType type, DocumentTemplateFormat format) {
        String filename = filename(type, format);
        var resource = new ClassPathResource(RESOURCE_ROOT + filename);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "document_builtin_template_source_missing:" + filename);
        }
        try (var input = resource.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "document_builtin_template_source_unreadable:" + filename, exception);
        }
    }

    private static String filename(
            DocumentTemplateType type, DocumentTemplateFormat format) {
        String filename = FILES.getOrDefault(type, Map.of()).get(format);
        if (filename == null) {
            throw new IllegalArgumentException("document_builtin_template_not_supported");
        }
        return filename;
    }

    private static String code(
            DocumentTemplateType type, DocumentTemplateFormat format) {
        filename(type, format);
        return "INTEGRATED_" + type.name() + "_" + format.name();
    }

    private static Map<DocumentTemplateType, Map<DocumentTemplateFormat, String>> files() {
        var result = new EnumMap<DocumentTemplateType,
                Map<DocumentTemplateFormat, String>>(DocumentTemplateType.class);
        result.put(DocumentTemplateType.FACTURA_VENTA, Map.of(
                DocumentTemplateFormat.A4, "FACTURA_VENTA_A4.jrxml",
                DocumentTemplateFormat.TICKET_80, "FACTURA_VENTA_TICKET_80.jrxml"));
        result.put(DocumentTemplateType.ALBARAN_VENTA, Map.of(
                DocumentTemplateFormat.A4, "ALBARAN_VENTA_A4.jrxml"));
        result.put(DocumentTemplateType.VALE, Map.of(
                DocumentTemplateFormat.TICKET_80, "VALE_TICKET_80.jrxml"));
        return Map.copyOf(result);
    }

    private record Key(DocumentTemplateType type, DocumentTemplateFormat format) {
    }
}
