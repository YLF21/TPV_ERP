package com.tpverp.backend.document.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.document.DocumentLineTotals;
import com.tpverp.backend.document.FiscalPrintView;
import com.tpverp.backend.document.InvoicePresentationSnapshot;
import com.tpverp.backend.document.Money;
import com.tpverp.backend.document.SalesInvoiceRectificationRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.verifactu.FiscalEndpointEnvironment;
import com.tpverp.backend.verifactu.FiscalMode;
import com.tpverp.backend.verifactu.FiscalPrintSnapshotFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Collections;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperPrintManager;
import net.sf.jasperreports.json.query.JsonQueryExecuterFactory;
import org.springframework.stereotype.Service;

@Service
public class InvoiceJasperRenderer {

    static final String ISSUER_LOGO_STREAM_PARAMETER = "TPV_ISSUER_LOGO_STREAM";
    static final String ISSUER_LOGO_PRESENT_PARAMETER = "TPV_ISSUER_LOGO_PRESENT";
    private static final int TICKET_RASTER_WIDTH = 576;
    private static final int TICKET_RASTER_MAX_HEIGHT = 30_000;

    private final DocumentTemplateRepository templates;
    private final DocumentTemplateArtifactStorage storage;
    private final SafeJrxmlCompiler compiler;
    private final ObjectMapper mapper;
    private final BuiltInDocumentJrxmlCatalog builtInTemplates;
    private CommercialDocumentRepository documents;
    private SalesInvoiceRectificationRepository rectifications;
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
            ObjectMapper mapper,
            BuiltInDocumentJrxmlCatalog builtInTemplates) {
        this.templates = templates;
        this.storage = storage;
        this.compiler = compiler;
        this.mapper = mapper;
        this.builtInTemplates = builtInTemplates;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setRectificationContext(
            CommercialDocumentRepository documents,
            SalesInvoiceRectificationRepository rectifications) {
        this.documents = documents;
        this.rectifications = rectifications;
    }

    public Optional<byte[]> render(
            CommercialDocument document,
            Store store,
            Company company,
            Customer customer,
            InvoicePresentationSnapshot presentation,
            String qrUrl) {
        return render(document, store, company, customer, presentation, qrUrl,
                null, DocumentTemplateFormat.A4);
    }

    public Optional<byte[]> render(
            CommercialDocument document,
            Store store,
            Company company,
            Customer customer,
            InvoicePresentationSnapshot presentation,
            String qrUrl,
            String logoDataUri) {
        return render(document, store, company, customer, presentation, qrUrl,
                logoDataUri, DocumentTemplateFormat.A4);
    }

    public Optional<byte[]> render(
            CommercialDocument document,
            Store store,
            Company company,
            Customer customer,
            InvoicePresentationSnapshot presentation,
            String qrUrl,
            String logoDataUri,
            DocumentTemplateFormat format) {
        return renderDocument(document, store, company, customer, presentation,
                qrUrl, logoDataUri, format).map(RenderedDocument::pdf);
    }

    /** Renders exclusively from the persisted fiscal print snapshot metadata. */
    public Optional<byte[]> renderWithFiscalSnapshot(
            CommercialDocument document,
            Store store,
            Company company,
            Customer customer,
            InvoicePresentationSnapshot presentation,
            FiscalPrintView fiscal,
            String logoDataUri,
            DocumentTemplateFormat format) {
        requireFrozenIssuer(fiscal);
        return renderDocumentWithFiscalSnapshot(
                document, store, company, customer, presentation,
                fiscal, logoDataUri, format).map(RenderedDocument::pdf);
    }

    public Optional<RenderedDocument> renderDocument(
            CommercialDocument document,
            Store store,
            Company company,
            Customer customer,
            InvoicePresentationSnapshot presentation,
            String qrUrl,
            String logoDataUri,
            DocumentTemplateFormat format) {
        return renderDocumentInternal(document, store, company, customer, presentation,
                compatibilityFiscal(qrUrl), logoDataUri, format);
    }

    /** Reprints with mode, environment and wording frozen at fiscal issuance. */
    public Optional<RenderedDocument> renderDocumentWithFiscalSnapshot(
            CommercialDocument document,
            Store store,
            Company company,
            Customer customer,
            InvoicePresentationSnapshot presentation,
            FiscalPrintView fiscal,
            String logoDataUri,
            DocumentTemplateFormat format) {
        requireFrozenIssuer(fiscal);
        return renderDocumentInternal(document, store, company, customer, presentation,
                fiscal, logoDataUri, format);
    }

    private Optional<RenderedDocument> renderDocumentInternal(
            CommercialDocument document,
            Store store,
            Company company,
            Customer customer,
            InvoicePresentationSnapshot presentation,
            FiscalPrintView fiscal,
            String logoDataUri,
            DocumentTemplateFormat format) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(company, "company");
        Objects.requireNonNull(presentation, "presentation");
        Objects.requireNonNull(format, "format");
        var templateType = templateType(document.getTipo());
        if (templateType == null) {
            return Optional.empty();
        }
        var reference = format == DocumentTemplateFormat.TICKET_80
                ? presentation.ticketTemplate() : presentation.template();
        if (reference == null) {
            reference = builtInTemplates.reference(templateType, format);
        }
        if ((templateType == DocumentTemplateType.FACTURA_VENTA
                || templateType == DocumentTemplateType.RECTIFICATIVA_VENTA) && customer == null) {
            throw new IllegalArgumentException("invoice_print_customer_required");
        }
        return renderPayload(
                reference, templateType, format, store, company,
                dataWithFiscalSnapshot(
                        document, store, company, customer, presentation, fiscal, logoDataUri),
                logoDataUri);
    }

    public Optional<RenderedDocument> renderPayload(
            InvoicePresentationSnapshot.TemplateReference reference,
            DocumentTemplateType templateType,
            DocumentTemplateFormat format,
            Store store,
            Company company,
            ObjectNode data,
            String logoDataUri) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(templateType, "templateType");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(company, "company");
        Objects.requireNonNull(data, "data");
        try {
            byte[] compiled = reference.builtIn()
                    ? builtInTemplates.compiled(reference, templateType, format)
                    : compiledImported(reference, templateType, format, company, store);
            byte[] json = mapper.writeValueAsBytes(data);
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
            byte[] pdf = JasperExportManager.getInstance(context).exportToPdf(print);
            byte[] ticketRaster = format == DocumentTemplateFormat.TICKET_80
                    ? ticketRaster(print) : null;
            return Optional.of(new RenderedDocument(pdf, ticketRaster));
        } catch (IOException | JRException exception) {
            throw new IllegalStateException("invoice_jasper_render_failed", exception);
        }
    }

    private byte[] compiledImported(
            InvoicePresentationSnapshot.TemplateReference reference,
            DocumentTemplateType templateType,
            DocumentTemplateFormat format,
            Company company,
            Store store) throws IOException {
        var template = templates.findPrintableTemplate(
                        reference.id(), company.getId(), store.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "invoice_print_template_not_available"));
        verifyReference(template, reference, templateType, format);
        byte[] source = storage.readSource(template.getArtifactReference());
        if (!SafeJrxmlCompiler.sha256(source).equals(template.getSha256())) {
            throw new IllegalStateException(
                    "document_template_artifact_integrity_failed");
        }
        return compiled(template, source);
    }

    static byte[] ticketRaster(JasperPrint print) throws JRException, IOException {
        if (print.getPages().isEmpty() || print.getPageWidth() <= 0) {
            throw new IllegalStateException("invoice_ticket_raster_empty");
        }
        float zoom = (float) TICKET_RASTER_WIDTH / print.getPageWidth();
        var pages = new java.util.ArrayList<BufferedImage>(print.getPages().size());
        int totalHeight = 0;
        for (int pageIndex = 0; pageIndex < print.getPages().size(); pageIndex++) {
            Image rendered = JasperPrintManager.printPageToImage(print, pageIndex, zoom);
            var page = opaque(rendered);
            int usedHeight = usedHeight(page);
            var cropped = page.getSubimage(0, 0, page.getWidth(), usedHeight);
            pages.add(cropped);
            totalHeight = Math.addExact(totalHeight, usedHeight);
            if (totalHeight > TICKET_RASTER_MAX_HEIGHT) {
                throw new IllegalStateException("invoice_ticket_raster_too_long");
            }
        }
        var output = new BufferedImage(TICKET_RASTER_WIDTH, totalHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, output.getWidth(), output.getHeight());
            int y = 0;
            for (var page : pages) {
                graphics.drawImage(page, 0, y, null);
                y += page.getHeight();
            }
        } finally {
            graphics.dispose();
        }
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(output, "png", bytes)) {
            throw new IllegalStateException("invoice_ticket_raster_encoder_unavailable");
        }
        return bytes.toByteArray();
    }

    private static BufferedImage opaque(Image source) {
        var output = new BufferedImage(
                source.getWidth(null), source.getHeight(null), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, output.getWidth(), output.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static int usedHeight(BufferedImage image) {
        int lastInkRow = 0;
        for (int y = image.getHeight() - 1; y >= 0; y--) {
            boolean hasInk = false;
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xff;
                int green = (rgb >> 8) & 0xff;
                int blue = rgb & 0xff;
                if (red < 245 || green < 245 || blue < 245) {
                    hasInk = true;
                    break;
                }
            }
            if (hasInk) {
                lastInkRow = y;
                break;
            }
        }
        return Math.min(image.getHeight(), Math.max(1, lastInkRow + 17));
    }

    public record RenderedDocument(byte[] pdf, byte[] ticketRasterPng) {

        public RenderedDocument {
            Objects.requireNonNull(pdf, "pdf");
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
        return dataWithFiscalSnapshot(document, store, company, customer, presentation,
                compatibilityFiscal(qrUrl), logoDataUri);
    }

    ObjectNode dataWithFiscalSnapshot(
            CommercialDocument document,
            Store store,
            Company company,
            Customer customer,
            InvoicePresentationSnapshot presentation,
            FiscalPrintView fiscal,
            String logoDataUri) {
        var root = mapper.createObjectNode();
        var documentNode = root.putObject("document");
        documentNode.put("type", document.getTipo().name());
        documentNode.put("title", switch (document.getTipo()) {
            case ALBARAN_VENTA -> "ALBARAN";
            case RECTIFICATIVA_VENTA -> "FACTURA_RECTIFICATIVA";
            default -> "FACTURA";
        });
        documentNode.put("displayNumber", document.getNumero());
        documentNode.put("issueDate", document.getFecha().toString());
        documentNode.put("operationDate", document.getFecha().toString());

        var issuer = root.putObject("issuer");
        boolean invoice = document.getTipo() == CommercialDocumentType.FACTURA_VENTA
                || document.getTipo() == CommercialDocumentType.RECTIFICATIVA_VENTA;
        boolean compatibilityFiscal = fiscal != null
                && "LEGACY_COMPAT".equals(fiscal.formatVersion());
        if (invoice && fiscal != null && !compatibilityFiscal) {
            requireFrozenIssuer(fiscal);
        }
        boolean frozenIssuer = invoice && fiscal != null
                && fiscal.hasFrozenIssuerIdentity();
        String issuerName = frozenIssuer ? fiscal.issuerName() : company.getRazonSocial();
        String issuerTaxId = frozenIssuer ? fiscal.issuerTaxId() : company.getTaxId();
        Map<String, String> issuerAddress = frozenIssuer
                ? fiscal.issuerAddress() : company.getDomicilioFiscal();
        boolean showStoreName = presentation.shouldShowStoreName();
        root.put("showStoreName", showStoreName);
        issuer.put("tradeName", store.getNombreEfectivo());
        issuer.put("legalName", issuerName);
        issuer.put("headerPrimaryName", showStoreName
                ? store.getNombreEfectivo() : issuerName);
        putNullable(issuer, "headerSecondaryName",
                showStoreName ? issuerName : null);
        issuer.put("taxId", issuerTaxId);
        address(issuer.putObject("address"), issuerAddress);
        putNullable(issuer, "phone", issuerAddress.get("telefono"));
        putNullable(issuer, "email", issuerAddress.get("email"));
        putNullable(issuer, "logoDataUri", logoDataUri);
        putNullable(issuer, "details", issuerDetails(issuerTaxId, issuerAddress));

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
            putNullable(customerNode, "details", customerDetails(customer));
        }

        if (document.getTipo() == CommercialDocumentType.RECTIFICATIVA_VENTA) {
            var rectification = root.putObject("rectification");
            var metadata = rectifications == null ? null
                    : rectifications.findByDocumentId(document.getId()).orElse(null);
            var original = metadata == null || documents == null ? null
                    : documents.findByIdAndTiendaId(metadata.getOriginalDocumentId(), document.getTiendaId())
                            .orElse(null);
            putNullable(rectification, "originalInvoice", original == null ? null : original.getNumero());
            putNullable(rectification, "cause", metadata == null ? null : metadata.getReason().name());
            putNullable(rectification, "detail", metadata == null ? null : metadata.getDetail());
            putNullable(rectification, "fiscalType", metadata == null ? null : metadata.getFiscalType().name());
            putNullable(rectification, "method", metadata == null ? null : metadata.getMethod().name());
        }

        FiscalPrintView frozenFiscal = invoice ? fiscal : null;
        String fiscalQrUrl = frozenFiscal == null ? null : frozenFiscal.qrUrl();
        var fiscalNode = root.putObject("fiscal");
        fiscalNode.put("qrRequired", fiscalQrUrl != null && !fiscalQrUrl.isBlank());
        fiscalNode.put("mode", frozenFiscal == null
                ? "NOT_APPLICABLE" : frozenFiscal.mode().name());
        putNullable(fiscalNode, "environment", frozenFiscal == null
                ? null : frozenFiscal.environment().name());
        putNullable(fiscalNode, "formatVersion", frozenFiscal == null
                ? null : frozenFiscal.formatVersion());
        putNullable(fiscalNode, "generatorVersion", frozenFiscal == null
                ? null : frozenFiscal.generatorVersion());
        putNullable(fiscalNode, "verificationUrl", fiscalQrUrl);
        putNullable(fiscalNode, "payloadSha256", frozenFiscal == null
                ? null : frozenFiscal.qrPayloadSha256());
        putNullable(fiscalNode, "qrPrefix", frozenFiscal == null
                ? null : frozenFiscal.prefix());
        putNullable(fiscalNode, "legend", frozenFiscal == null ? null : frozenFiscal.legend());
        putNullable(fiscalNode, "testNotice", frozenFiscal == null
                ? null : frozenFiscal.testNotice());
        fiscalNode.put("testData", frozenFiscal != null
                && frozenFiscal.environment() == FiscalEndpointEnvironment.TEST);

        var lines = root.putArray("lines");
        document.getLineas().stream()
                .filter(line -> !DocumentLineTotals.isMemberBalance(line))
                .sorted(Comparator.comparingInt(DocumentLine::getPosicion))
                .forEach(line -> line(lines.addObject(), line));
        taxBreakdown(root.putArray("taxBreakdown"), document);

        var totals = root.putObject("totals");
        var gross = document.getLineas().stream()
                .filter(line -> !DocumentLineTotals.isMemberBalance(line))
                .map(line -> Money.euros(
                        netUnitPrice(line).multiply(line.getCantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var memberBalance = DocumentLineTotals.memberBalanceTotal(document.getLineas());
        var memberBalanceBase = DocumentLineTotals.memberBalanceBaseTotal(document.getLineas());
        totals.put("grandTotal", document.getTotal());
        totals.put("grossAmount", Money.euros(gross));
        totals.put("discountTotal", Money.euros(
                gross.subtract(document.getBaseTotal()).subtract(memberBalanceBase))
                .max(BigDecimal.ZERO));
        totals.put("memberBalanceTotal", memberBalance);
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

    /**
     * Compatibility for old internal callers. It deliberately does not inspect
     * the URL; all production print paths use {@link #renderWithFiscalSnapshot}.
     */
    private static FiscalPrintView compatibilityFiscal(String qrUrl) {
        if (qrUrl == null || qrUrl.isBlank()) {
            return null;
        }
        return new FiscalPrintView(
                "LEGACY_COMPAT",
                "LEGACY_COMPAT",
                FiscalMode.VERIFACTU,
                FiscalEndpointEnvironment.PRODUCTION,
                qrUrl,
                "LEGACY_UNVERIFIED",
                FiscalPrintSnapshotFactory.PREFIX,
                FiscalPrintSnapshotFactory.VERIFACTU_LEGEND,
                null);
    }

    private static void verifyReference(
            DocumentTemplate template,
            InvoicePresentationSnapshot.TemplateReference reference,
            DocumentTemplateType expectedType,
            DocumentTemplateFormat expectedFormat) {
        if (template.getType() != expectedType
                || template.getFormat() != expectedFormat
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
            case RECTIFICATIVA_VENTA -> DocumentTemplateType.RECTIFICATIVA_VENTA;
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

    private static String issuerDetails(
            String issuerTaxId, Map<String, String> source) {
        return compactLines(
                labeled("NIF: ", issuerTaxId),
                source.get("linea1"),
                locality(source.get("codigoPostal"), source.get("ciudad"),
                        source.get("provincia")),
                labeled("País: ", source.get("pais")),
                joinNonBlank(" · ", source.get("telefono"), source.get("email")));
    }

    private static void requireFrozenIssuer(FiscalPrintView fiscal) {
        if (fiscal == null || !fiscal.hasFrozenIssuerIdentity()) {
            throw new IllegalStateException("fiscal_print_issuer_identity_missing");
        }
    }

    private static String customerDetails(Customer customer) {
        var source = customer.getFiscalAddress();
        return compactLines(
                labeled("Cód. cliente: ", customer.getClientId()),
                labeled("NIF: ", customer.getDocumentNumber()),
                source == null ? null : source.getAddress(),
                source == null ? null : locality(
                        source.getPostalCode(), source.getCity(), source.getProvince()),
                source == null ? null : labeled("País: ", source.getCountry()),
                labeled("Tel.: ", customer.getPhone()));
    }

    private static String locality(String postalCode, String city, String province) {
        var locality = joinNonBlank(" ", postalCode, city);
        var normalizedProvince = normalized(province);
        if (normalizedProvince == null
                || normalizedProvince.equalsIgnoreCase(normalized(city))) {
            return locality;
        }
        return joinNonBlank(" · ", locality, normalizedProvince);
    }

    private static String labeled(String label, String value) {
        var normalizedValue = normalized(value);
        return normalizedValue == null ? null : label + normalizedValue;
    }

    private static String joinNonBlank(String separator, String... values) {
        return Stream.of(values)
                .map(InvoiceJasperRenderer::normalized)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(separator));
    }

    private static String compactLines(String... values) {
        return joinNonBlank("\n", values);
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
