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

    private static final String RESOURCE_ROOT = "reports/documents/";
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
        int version = currentVersion(type, format);
        var value = compiled(type, format, version);
        return new InvoicePresentationSnapshot.TemplateReference(
                null,
                code(type, format),
                version,
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
                || reference.version() < 1
                || reference.version() > currentVersion(type, format)
                || reference.dataSchemaVersion() != SafeJrxmlCompiler.DATA_SCHEMA_VERSION) {
            throw new IllegalStateException("document_builtin_template_reference_mismatch");
        }
        var value = compiled(type, format, reference.version());
        if (reference.sha256() != null && !value.sha256().equals(reference.sha256())) {
            throw new IllegalStateException("document_builtin_template_reference_mismatch");
        }
        return value.compiled();
    }

    private SafeJrxmlCompiler.CompiledTemplate compiled(
            DocumentTemplateType type, DocumentTemplateFormat format, int version) {
        var key = new Key(type, format, version);
        return compiled.computeIfAbsent(key, ignored -> compiler.compile(source(type, format, version)));
    }

    private static byte[] source(
            DocumentTemplateType type, DocumentTemplateFormat format, int version) {
        String filename = filename(type, format);
        var resource = new ClassPathResource(RESOURCE_ROOT + "v" + version + "/" + filename);
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
                DocumentTemplateFormat.A4, "ALBARAN_VENTA_A4.jrxml",
                DocumentTemplateFormat.TICKET_80, "ALBARAN_VENTA_TICKET_80.jrxml"));
        result.put(DocumentTemplateType.VALE, Map.of(
                DocumentTemplateFormat.TICKET_80, "VALE_TICKET_80.jrxml"));
        result.put(DocumentTemplateType.TICKET_REGALO, Map.of(
                DocumentTemplateFormat.TICKET_80, "TICKET_REGALO_TICKET_80.jrxml"));
        result.put(DocumentTemplateType.ENTRADA_CAJA, Map.of(
                DocumentTemplateFormat.TICKET_80, "ENTRADA_CAJA_TICKET_80.jrxml"));
        result.put(DocumentTemplateType.RETIRADA_CAJA, Map.of(
                DocumentTemplateFormat.TICKET_80, "RETIRADA_CAJA_TICKET_80.jrxml"));
        result.put(DocumentTemplateType.RECTIFICATIVA_VENTA, Map.of(
                DocumentTemplateFormat.A4, "RECTIFICATIVA_VENTA_A4.jrxml",
                DocumentTemplateFormat.TICKET_80, "RECTIFICATIVA_VENTA_TICKET_80.jrxml"));
        result.put(DocumentTemplateType.SALIDA_ALMACEN, Map.of(
                DocumentTemplateFormat.A4, "SALIDA_ALMACEN_A4.jrxml"));
        result.put(DocumentTemplateType.ENTRADA_ALMACEN, Map.of(
                DocumentTemplateFormat.A4, "ENTRADA_ALMACEN_A4.jrxml"));
        result.put(DocumentTemplateType.ALBARAN_ENTRADA, Map.of(
                DocumentTemplateFormat.A4, "ALBARAN_ENTRADA_A4.jrxml"));
        result.put(DocumentTemplateType.FACTURA_ENTRADA, Map.of(
                DocumentTemplateFormat.A4, "FACTURA_ENTRADA_A4.jrxml"));
        result.put(DocumentTemplateType.HISTORIAL_VENTAS_PRODUCTO, Map.of(
                DocumentTemplateFormat.A4, "HISTORIAL_VENTAS_PRODUCTO_A4.jrxml"));
        return Map.copyOf(result);
    }

    private static int currentVersion(DocumentTemplateType type, DocumentTemplateFormat format) {
        if (type == DocumentTemplateType.FACTURA_VENTA) return 2;
        if (format != DocumentTemplateFormat.TICKET_80) return 1;
        return switch (type) {
            case ALBARAN_VENTA, VALE, TICKET_REGALO, ENTRADA_CAJA,
                    RETIRADA_CAJA, RECTIFICATIVA_VENTA -> 2;
            default -> 1;
        };
    }

    private record Key(DocumentTemplateType type, DocumentTemplateFormat format, int version) {
    }
}
