package com.tpverp.backend.inventory;

import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Signs the small, authoritative Warehouse Excel provenance contract.  The
 * payload is deliberately binary length-prefixed rather than delimiter based:
 * null, empty strings, decimal scale and list boundaries remain unambiguous.
 */
@Service
public class WarehouseExcelImportProvenanceService {

    private static final String VERSION = "warehouse-excel-provenance-v1";
    private static final String APPLY = "APPLY_PROVENANCE";
    private static final String DOCUMENT = "DOCUMENT_SNAPSHOT";
    private final InstallationIdentityStore identity;

    public WarehouseExcelImportProvenanceService(InstallationIdentityStore identity) {
        this.identity = identity;
    }

    public String signApply(UUID companyId, UUID storeId, UUID warehouseId, LocalDate documentDate,
            UUID supplierId, WarehouseExcelImportMetadata metadata) {
        return sign(APPLY, companyId, storeId, warehouseId, documentDate, null, 0L, supplierId, metadata,
                List.of());
    }

    public String signDocument(UUID companyId, UUID storeId, UUID warehouseId, LocalDate documentDate,
            UUID documentId, long version, UUID supplierId, WarehouseExcelImportMetadata metadata,
            List<WarehouseInputLine> lines) {
        return sign(DOCUMENT, companyId, storeId, warehouseId, documentDate, documentId, version, supplierId,
                metadata, lines);
    }

    public boolean verifyApply(String token, UUID companyId, UUID storeId, UUID warehouseId,
            LocalDate documentDate, UUID supplierId, WarehouseExcelImportMetadata metadata) {
        return verify(token, APPLY, companyId, storeId, warehouseId, documentDate, null, 0L, supplierId, metadata,
                List.of());
    }

    public boolean verifyDocument(String token, UUID companyId, UUID storeId, UUID warehouseId,
            LocalDate documentDate, UUID documentId, long version, UUID supplierId,
            WarehouseExcelImportMetadata metadata, List<WarehouseInputLine> lines) {
        return verify(token, DOCUMENT, companyId, storeId, warehouseId, documentDate, documentId, version, supplierId,
                metadata, lines);
    }

    private String sign(String kind, UUID companyId, UUID storeId, UUID warehouseId, LocalDate documentDate,
            UUID documentId, long version,
            UUID supplierId, WarehouseExcelImportMetadata metadata, List<WarehouseInputLine> lines) {
        byte[] payload = canonical(kind, companyId, storeId, warehouseId, documentDate, documentId,
                version, supplierId, metadata, lines);
        String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(identity.sign(payload));
        return "WXP1." + tokenKind(kind) + "." + encodedSignature;
    }

    private boolean verify(String token, String kind, UUID companyId, UUID storeId, UUID warehouseId,
            LocalDate documentDate, UUID documentId,
            long version, UUID supplierId, WarehouseExcelImportMetadata metadata, List<WarehouseInputLine> documentLines) {
        if (token == null || !token.startsWith("WXP1.")) return false;
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !tokenKind(kind).equals(parts[1]) || parts[2].isBlank()) return false;
        try {
            byte[] payload = canonical(kind, companyId, storeId, warehouseId, documentDate, documentId,
                version, supplierId, metadata, documentLines);
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            return identity.verify(payload, signature);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] canonical(String kind, UUID companyId, UUID storeId, UUID warehouseId,
            LocalDate documentDate, UUID documentId,
            long version, UUID supplierId, WarehouseExcelImportMetadata metadata,
            List<WarehouseInputLine> documentLines) {
        if (companyId == null || storeId == null || metadata == null) {
            throw new IllegalArgumentException("provenance scope and metadata are required");
        }
        try {
            var bytes = new ByteArrayOutputStream(4096);
            var output = new DataOutputStream(bytes);
            text(output, VERSION);
            text(output, kind);
            uuid(output, companyId);
            uuid(output, storeId);
            uuid(output, warehouseId);
            text(output, documentDate == null ? null : documentDate.toString());
            uuid(output, documentId);
            output.writeLong(version);
            uuid(output, supplierId);
            text(output, metadata.fileName());
            text(output, metadata.sha256() == null ? null : metadata.sha256().toLowerCase(java.util.Locale.ROOT));
            text(output, metadata.sheetName());
            output.writeBoolean(metadata.updateSupplier());
            output.writeBoolean(metadata.skipZeroPriceUpdate());
            var formulas = new ArrayList<>(metadata.formulas());
            formulas.sort(Comparator.comparing(WarehouseExcelImportMetadata.Formula::cell));
            output.writeInt(formulas.size());
            for (var formula : formulas) {
                text(output, formula.cell());
                text(output, formula.formula());
                text(output, formula.calculatedValue());
            }
            var lines = new ArrayList<>(metadata.lines());
            lines.sort(Comparator.comparing(WarehouseExcelImportMetadata.Line::productId));
            output.writeInt(lines.size());
            for (var line : lines) {
                uuid(output, line.productId());
                var rows = line.rowNumbers().stream().sorted().toList();
                output.writeInt(rows.size());
                rows.forEach(row -> writeInt(output, row));
                text(output, line.supplierReference());
                text(output, line.grossPurchasePrice() == null ? null : line.grossPurchasePrice().stripTrailingZeros().toPlainString());
                text(output, line.purchaseDiscountPercent() == null ? null : line.purchaseDiscountPercent().stripTrailingZeros().toPlainString());
            }
            if (DOCUMENT.equals(kind)) {
                var snapshotLines = documentLines == null ? List.<WarehouseInputLine>of() : new ArrayList<>(documentLines);
                snapshotLines.sort(Comparator.comparingInt(WarehouseInputLine::getPosition)
                        .thenComparing(line -> line.getProductId().toString()));
                output.writeInt(snapshotLines.size());
                for (var line : snapshotLines) {
                    uuid(output, line.getProductId());
                    text(output, line.getQuantity().stripTrailingZeros().toPlainString());
                    text(output, line.getPurchaseUnitPrice().stripTrailingZeros().toPlainString());
                    text(output, line.getDiscount().stripTrailingZeros().toPlainString());
                    output.writeBoolean(line.isPriceOverridden());
                }
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot canonicalize provenance", exception);
        }
    }

    private static String tokenKind(String kind) {
        return APPLY.equals(kind) ? "A" : DOCUMENT.equals(kind) ? "D" : "?";
    }

    private static void uuid(DataOutputStream output, UUID value) throws IOException {
        if (value == null) {
            output.writeBoolean(false);
            return;
        }
        output.writeBoolean(true);
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void writeInt(DataOutputStream output, int value) {
        try {
            output.writeInt(value);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
