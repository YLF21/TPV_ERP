package com.tpverp.backend.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.DocumentTemplateType;
import com.tpverp.backend.document.template.InvoiceJasperRenderer;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoucherPrintService {

    private static final int TRACEABILITY_DOCUMENTS_PER_LINE = 3;

    private final CurrentOrganization organization;
    private final VoucherPresentationSnapshotFactory snapshots;
    private final InvoiceJasperRenderer jasper;
    private final ObjectMapper mapper;

    public VoucherPrintService(
            CurrentOrganization organization,
            VoucherPresentationSnapshotFactory snapshots,
            InvoiceJasperRenderer jasper,
            ObjectMapper mapper) {
        this.organization = organization;
        this.snapshots = snapshots;
        this.jasper = jasper;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PrintedVoucher render(Voucher voucher) {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        if (!store.getId().equals(voucher.storeId())) {
            throw new IllegalArgumentException("vale no encontrado");
        }
        var snapshot = snapshots.read(voucher.printSnapshot());
        String logoDataUri = snapshots.logoDataUri(snapshot, store.getId());
        var rendered = jasper.renderPayload(
                        snapshot.template(), DocumentTemplateType.VALE,
                        DocumentTemplateFormat.TICKET_80, store, company,
                        data(voucher, snapshot, logoDataUri), logoDataUri)
                .orElseThrow(() -> new IllegalStateException(
                        "voucher_jasper_template_required"));
        var traceability = snapshot.traceability().stream()
                .map(TraceView::from)
                .toList();
        return new PrintedVoucher(
                voucher.code(), voucher.initialAmount(), voucher.createdAt(),
                traceability.get(traceability.size() - 1).documentNumber(),
                traceability,
                snapshot.observations(),
                new RenderedContent("application/pdf",
                        Base64.getEncoder().encodeToString(rendered.pdf())),
                new RenderedContent("image/png",
                        Base64.getEncoder().encodeToString(rendered.ticketRasterPng())));
    }

    ObjectNode data(
            Voucher voucher,
            VoucherPresentationSnapshot snapshot,
            String logoDataUri) {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var root = mapper.createObjectNode();
        var voucherNode = root.putObject("voucher");
        voucherNode.put("code", voucher.code());
        voucherNode.put("barcode", voucher.code());
        voucherNode.put("amount", voucher.initialAmount());
        voucherNode.put("amountFormatted", formatAmount(voucher.initialAmount()));
        voucherNode.put("issuedAt", DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.of(store.getTimezone())).format(voucher.createdAt()));
        voucherNode.put("status", voucher.status().name());
        root.put("currency", store.getMoneda());
        root.put("storeName", store.getNombreEfectivo());
        boolean showStoreName = snapshot.shouldShowStoreName();
        root.put("showStoreName", showStoreName);
        putNullable(root, "terminalName", snapshot.terminalName());
        putNullable(root, "observations", snapshot.observations());

        var issuer = root.putObject("issuer");
        issuer.put("tradeName", store.getNombreEfectivo());
        issuer.put("legalName", company.getRazonSocial());
        issuer.put("headerPrimaryName", showStoreName
                ? store.getNombreEfectivo() : company.getRazonSocial());
        putNullable(issuer, "headerSecondaryName",
                showStoreName ? company.getRazonSocial() : null);
        issuer.put("taxId", company.getTaxId());
        var fiscalAddress = company.getDomicilioFiscal();
        var address = issuer.putObject("address");
        putNullable(address, "line1", fiscalAddress.get("linea1"));
        putNullable(address, "postalCode", fiscalAddress.get("codigoPostal"));
        putNullable(address, "city", fiscalAddress.get("ciudad"));
        putNullable(address, "province", fiscalAddress.get("provincia"));
        putNullable(address, "countryName", fiscalAddress.get("pais"));
        putNullable(issuer, "phone", fiscalAddress.get("telefono"));
        putNullable(issuer, "email", fiscalAddress.get("email"));
        putNullable(issuer, "logoDataUri", logoDataUri);

        List<String> traceabilityDocumentNumbers = snapshot.traceability().stream()
                .sorted(Comparator.comparing(
                        VoucherPresentationSnapshot.TraceEntry::documentDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(VoucherPresentationSnapshot.TraceEntry::documentNumber)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        root.put("traceabilityDocumentNumbers",
                formatTraceabilityDocumentNumbers(traceabilityDocumentNumbers));

        var trace = root.putArray("traceability");
        snapshot.traceability().forEach(entry -> {
            var node = trace.addObject();
            node.put("documentNumber", entry.documentNumber());
            putNullable(node, "documentType",
                    documentTypeLabel(entry.documentType()));
            putNullable(node, "documentDate",
                    entry.documentDate() == null ? null
                            : DateTimeFormatter.ofPattern("dd/MM/yyyy")
                                    .format(entry.documentDate()));
            node.put("operation", operationLabel(entry.operation()));
        });
        return root;
    }

    private static String formatTraceabilityDocumentNumbers(List<String> documentNumbers) {
        var formatted = new StringBuilder();
        for (int index = 0; index < documentNumbers.size(); index++) {
            if (index > 0) {
                formatted.append(index % TRACEABILITY_DOCUMENTS_PER_LINE == 0
                        ? '\n'
                        : " · ");
            }
            formatted.append(documentNumbers.get(index));
        }
        return formatted.toString();
    }

    private static String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP)
                .toPlainString()
                .replace('.', ',');
    }

    private static void putNullable(ObjectNode node, String name, String value) {
        if (value == null || value.isBlank()) node.putNull(name);
        else node.put(name, value.trim());
    }

    private static String operationLabel(String value) {
        return switch (value) {
            case "EMISION" -> "Emisi\u00f3n";
            case "CONSUMO_PARCIAL" -> "Consumo parcial y renovaci\u00f3n";
            default -> value;
        };
    }

    private static String documentTypeLabel(CommercialDocumentType value) {
        if (value == null) return null;
        return switch (value) {
            case TICKET -> "Ticket";
            case FACTURA_VENTA -> "Factura de venta";
            case RECTIFICATIVA_VENTA -> "Factura rectificativa de venta";
            case ALBARAN_VENTA -> "Albar\u00e1n de venta";
            case FACTURA_COMPRA -> "Factura de compra";
            case RECTIFICATIVA_COMPRA -> "Factura rectificativa de compra";
            case ALBARAN_COMPRA -> "Albar\u00e1n de compra";
        };
    }

    public record PrintedVoucher(
            String code,
            BigDecimal amount,
            Instant issuedAt,
            String originTicketNumber,
            List<TraceView> traceability,
            String observations,
            RenderedContent renderedPdf,
            RenderedContent ticketRenderedImage) {
    }

    public record TraceView(
            String documentNumber,
            String documentType,
            String documentDate,
            String operation) {

        static TraceView from(VoucherPresentationSnapshot.TraceEntry value) {
            return new TraceView(
                    value.documentNumber(),
                    value.documentType() == null ? null : value.documentType().name(),
                    value.documentDate() == null ? null : value.documentDate().toString(),
                    value.operation());
        }
    }

    public record RenderedContent(String contentType, String base64) {
    }
}
