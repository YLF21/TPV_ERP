package com.tpverp.backend.document.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.document.InvoicePresentationSnapshot;
import com.tpverp.backend.document.Money;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Collections;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.json.query.JsonQueryExecuterFactory;
import org.springframework.stereotype.Service;

@Service
public class InvoiceJasperRenderer {

    static final String ISSUER_LOGO_STREAM_PARAMETER = "TPV_ISSUER_LOGO_STREAM";
    static final String ISSUER_LOGO_PRESENT_PARAMETER = "TPV_ISSUER_LOGO_PRESENT";

    private final DocumentTemplateRepository templates;
    private final DocumentTemplateArtifactStorage storage;
    private final SafeJrxmlCompiler compiler;
    private final ObjectMapper mapper;
    private final Map<CompiledCacheKey, byte[]> compiledCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<CompiledCacheKey, byte[]> eldest) {
                    return size() > 64;
                }
            });

    public InvoiceJasperRenderer(
            DocumentTemplateRepository templates,
            DocumentTemplateArtifactStorage storage,
            SafeJrxmlCompiler compiler,
            ObjectMapper mapper) {
        this.templates = templates;
        this.storage = storage;
        this.compiler = compiler;
        this.mapper = mapper;
    }

    public Optional<byte[]> render(
            CommercialDocument document,
            Store store,
            Company company,
            Customer customer,
            InvoicePresentationSnapshot presentation,
            String qrUrl) {
        return render(document, store, company, customer, presentation, qrUrl, null);
    }

    public Optional<byte[]> render(
            CommercialDocument document,
            Store store,
            Company company,
            Customer customer,
            InvoicePresentationSnapshot presentation,
            String qrUrl,
            String logoDataUri) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(company, "company");
        Objects.requireNonNull(presentation, "presentation");
        var reference = presentation.template();
        var templateType = templateType(document.getTipo());
        if (templateType == null || reference == null || reference.builtIn()) {
            return Optional.empty();
        }
        if (templateType == DocumentTemplateType.FACTURA_VENTA && customer == null) {
            throw new IllegalArgumentException("invoice_print_customer_required");
        }
        var template = templates.findPrintableTemplate(
                        reference.id(), company.getId(), store.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "invoice_print_template_not_available"));
        verifyReference(template, reference, templateType);
        try {
            byte[] source = storage.readSource(template.getArtifactReference());
            if (!SafeJrxmlCompiler.sha256(source).equals(template.getSha256())) {
                throw new IllegalStateException(
                        "document_template_artifact_integrity_failed");
            }
            byte[] compiled = compiled(template, source);
            byte[] json = mapper.writeValueAsBytes(
                    data(document, store, company, customer, presentation, qrUrl,
                            logoDataUri));
            var parameters = new LinkedHashMap<String, Object>();
            parameters.put(JsonQueryExecuterFactory.JSON_INPUT_STREAM,
                    new ByteArrayInputStream(json));
            byte[] logoBytes = decodeLogoDataUri(logoDataUri);
            parameters.put(ISSUER_LOGO_STREAM_PARAMETER,
                    logoBytes == null ? null : new ByteArrayInputStream(logoBytes));
            parameters.put(ISSUER_LOGO_PRESENT_PARAMETER, logoBytes != null);
            var context = SafeJrxmlCompiler.secureContext();
            var print = JasperFillManager.getInstance(context).fill(
                    new ByteArrayInputStream(compiled), parameters);
            return Optional.of(JasperExportManager.getInstance(context).exportToPdf(print));
        } catch (IOException | JRException exception) {
            throw new IllegalStateException("invoice_jasper_render_failed", exception);
        }
    }

    private static byte[] decodeLogoDataUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int separator = value.indexOf(',');
        if (separator <= 0) {
            throw new IllegalArgumentException("invoice_print_logo_invalid");
        }
        String metadata = value.substring(0, separator).toLowerCase(java.util.Locale.ROOT);
        if (!("data:image/png;base64".equals(metadata)
                || "data:image/jpeg;base64".equals(metadata))) {
            throw new IllegalArgumentException("invoice_print_logo_invalid");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value.substring(separator + 1));
            if (decoded.length == 0) {
                throw new IllegalArgumentException("invoice_print_logo_invalid");
            }
            return decoded;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invoice_print_logo_invalid", error);
        }
    }

    private byte[] compiled(DocumentTemplate template, byte[] source) {
        var key = new CompiledCacheKey(template.getId(), template.getSha256());
        synchronized (compiledCache) {
            var cached = compiledCache.get(key);
            if (cached != null) {
                return cached;
            }
            var trusted = compiler.compile(source).compiled();
            compiledCache.put(key, trusted);
            return trusted;
        }
    }

    ObjectNode data(
            CommercialDocument document,
            Store store,
            Company company,
            Customer customer,
            InvoicePresentationSnapshot presentation,
            String qrUrl) {
        return data(document, store, company, customer, presentation, qrUrl, null);
    }

    ObjectNode data(
            CommercialDocument document,
            Store store,
            Company company,
            Customer customer,
            InvoicePresentationSnapshot presentation,
            String qrUrl,
            String logoDataUri) {
        var root = mapper.createObjectNode();
        var documentNode = root.putObject("document");
        documentNode.put("type", document.getTipo().name());
        documentNode.put("title", document.getTipo() == CommercialDocumentType.ALBARAN_VENTA
                ? "ALBARAN" : "FACTURA");
        documentNode.put("displayNumber", document.getNumero());
        documentNode.put("issueDate", document.getFecha().toString());
        documentNode.put("operationDate", document.getFecha().toString());

        var issuer = root.putObject("issuer");
        issuer.put("tradeName", store.getNombreEfectivo());
        issuer.put("legalName", company.getRazonSocial());
        issuer.put("taxId", company.getTaxId());
        address(issuer.putObject("address"), company.getDomicilioFiscal());
        putNullable(issuer, "phone", company.getDomicilioFiscal().get("telefono"));
        putNullable(issuer, "email", company.getDomicilioFiscal().get("email"));
        putNullable(issuer, "logoDataUri", logoDataUri);

        var customerNode = root.putObject("customer");
        if (customer != null) {
            customerNode.put("legalName", customer.getFiscalName());
            customerNode.put("taxId", customer.getDocumentNumber());
            var customerAddress = customer.getFiscalAddress();
            var address = customerNode.putObject("address");
            if (customerAddress != null) {
                putNullable(address, "line1", customerAddress.getAddress());
                putNullable(address, "postalCode", customerAddress.getPostalCode());
                putNullable(address, "city", customerAddress.getCity());
                putNullable(address, "province", customerAddress.getProvince());
                putNullable(address, "countryName", customerAddress.getCountry());
            }
            putNullable(customerNode, "phone", customer.getPhone());
            putNullable(customerNode, "customerCode", customer.getClientId());
        }

        boolean invoice = document.getTipo() == CommercialDocumentType.FACTURA_VENTA;
        String fiscalQrUrl = invoice ? qrUrl : null;
        var fiscal = root.putObject("fiscal");
        fiscal.put("qrRequired", fiscalQrUrl != null && !fiscalQrUrl.isBlank());
        fiscal.put("mode", !invoice ? "NOT_APPLICABLE"
                : fiscalQrUrl == null || fiscalQrUrl.isBlank()
                        ? "NO_VERIFACTU" : "VERIFACTU");
        putNullable(fiscal, "verificationUrl", fiscalQrUrl);
        putNullable(fiscal, "legend", invoice
                ? "Factura verificable en la sede electrónica de la AEAT" : null);
        fiscal.put("testData", false);

        var lines = root.putArray("lines");
        document.getLineas().stream()
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .forEach(line -> line(lines.addObject(), line));
        taxBreakdown(root.putArray("taxBreakdown"), document);

        var totals = root.putObject("totals");
        var gross = document.getLineas().stream()
                .map(line -> Money.euros(
                        netUnitPrice(line).multiply(line.getCantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        totals.put("grandTotal", document.getTotal());
        totals.put("grossAmount", Money.euros(gross));
        totals.put("discountTotal", Money.euros(
                gross.subtract(document.getBaseTotal())).max(BigDecimal.ZERO));
        totals.put("taxableBase", document.getBaseTotal());
        totals.put("taxTotal", document.getImpuestoTotal());
        totals.put("amountPaid", document.getPaidTotal());
        totals.put("amountDue", document.getPendingTotal());

        var payment = root.putObject("payment");
        var accounts = payment.putArray("bankAccounts");
        if (invoice) {
            var orderedPayments = document.getPagos().stream()
                    .sorted(Comparator.comparingInt(value -> value.getPosicion()))
                    .toList();
            putNullable(payment, "method", orderedPayments.stream()
                    .map(value -> value.getMetodoPago().getNombre())
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" / ")));
            putNullable(payment, "terms", null);
            putNullable(payment, "dueDate",
                    document.getDueDate() == null ? null : document.getDueDate().toString());
            putNullable(payment, "reference", orderedPayments.stream()
                    .map(value -> value.getReferencia())
                    .filter(value -> value != null && !value.isBlank())
                    .distinct().collect(Collectors.joining(" / ")));
            presentation.bankAccounts().forEach(account -> {
                var node = accounts.addObject();
                node.put("bankName", account.bankName());
                node.put("iban", account.iban());
            });
        }

        putNullable(root, "observations", presentation.observations());
        root.put("fiscalProfile", presentation.fiscalProfile().name());
        root.put("currency", store.getMoneda());
        return root;
    }

    private static void verifyReference(
            DocumentTemplate template,
            InvoicePresentationSnapshot.TemplateReference reference,
            DocumentTemplateType expectedType) {
        if (template.getType() != expectedType
                || (template.getStatus() != DocumentTemplateStatus.ACTIVE
                        && template.getStatus() != DocumentTemplateStatus.RETIRED)
                || !template.getCode().equals(reference.code())
                || template.getTemplateVersion() != reference.version()
                || !Objects.equals(template.getSchemaVersion(), reference.dataSchemaVersion())
                || !Objects.equals(template.getSha256(), reference.sha256())
                || template.getArtifactReference() == null) {
            throw new IllegalStateException("invoice_print_template_reference_mismatch");
        }
    }

    private static DocumentTemplateType templateType(CommercialDocumentType documentType) {
        return switch (documentType) {
            case FACTURA_VENTA -> DocumentTemplateType.FACTURA_VENTA;
            case ALBARAN_VENTA -> DocumentTemplateType.ALBARAN_VENTA;
            default -> null;
        };
    }

    private static void line(ObjectNode node, DocumentLine line) {
        node.put("position", line.getPosicion());
        node.put("code", line.getCodigo());
        putNullable(node, "barcode", line.getCodigoBarras());
        node.put("articleName", line.getNombre());
        node.put("quantity", line.getCantidad());
        putNullable(node, "unit", null);
        node.put("unitPriceNet", netUnitPrice(line));
        node.put("discountPercent", line.getDescuento());
        node.put("discountAmount", discountAmount(line));
        node.put("taxRegime", line.getRegimenImpuesto());
        node.put("taxRate", line.getPorcentajeImpuesto());
        node.put("taxableBase", line.getBase());
        node.put("taxAmount", line.getImpuesto());
        node.put("lineTotal", line.getTotal());
        node.put("priceIncludesTax", "");
    }

    private static BigDecimal netUnitPrice(DocumentLine line) {
        if (!line.isImpuestosIncluidos() || line.getPorcentajeImpuesto().signum() == 0) {
            return line.getPrecioUnitario();
        }
        var divisor = BigDecimal.ONE.add(line.getPorcentajeImpuesto().movePointLeft(2));
        return line.getPrecioUnitario().divide(divisor, 6, Money.ROUNDING);
    }

    private static BigDecimal discountAmount(DocumentLine line) {
        var gross = netUnitPrice(line).multiply(line.getCantidad());
        return Money.euros(gross.multiply(line.getDescuento().movePointLeft(2))).abs();
    }

    private static void taxBreakdown(ArrayNode target, CommercialDocument document) {
        record TaxKey(String regime, BigDecimal rate) {}
        record TaxTotal(BigDecimal base, BigDecimal tax) {
            TaxTotal add(DocumentLine line) {
                return new TaxTotal(base.add(line.getBase()), tax.add(line.getImpuesto()));
            }
        }
        var groups = new LinkedHashMap<TaxKey, TaxTotal>();
        document.getLineas().stream()
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .forEach(line -> groups.compute(
                        new TaxKey(line.getRegimenImpuesto(), line.getPorcentajeImpuesto()),
                        (ignored, current) -> (current == null
                                ? new TaxTotal(BigDecimal.ZERO, BigDecimal.ZERO)
                                : current).add(line)));
        groups.forEach((key, value) -> {
            var node = target.addObject();
            node.put("taxRegime", key.regime());
            node.put("taxRate", key.rate());
            node.put("taxableBase", Money.euros(value.base()));
            node.put("taxAmount", Money.euros(value.tax()));
        });
    }

    private static void address(ObjectNode target, Map<String, String> source) {
        putNullable(target, "line1", source.get("linea1"));
        putNullable(target, "postalCode", source.get("codigoPostal"));
        putNullable(target, "city", source.get("ciudad"));
        putNullable(target, "province", source.get("provincia"));
        putNullable(target, "countryName", source.get("pais"));
    }

    private static void putNullable(ObjectNode node, String name, String value) {
        if (value == null || value.isBlank()) {
            node.putNull(name);
        } else {
            node.put(name, value.trim());
        }
    }

    private record CompiledCacheKey(java.util.UUID templateId, String sha256) {}
}
