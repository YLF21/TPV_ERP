package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import com.tpverp.backend.shared.crypto.SecretProtector;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WarehouseExcelImportProvenanceServiceTest {

    @TempDir
    Path directory;

    @Test
    void signsAndVerifiesDetachedApplyAndDocumentTokens() {
        var service = service();
        var company = UUID.randomUUID();
        var store = UUID.randomUUID();
        var warehouse = UUID.randomUUID();
        var document = UUID.randomUUID();
        var supplier = UUID.randomUUID();
        var metadata = metadata(supplier);
        var documentLines = documentLines(metadata);

        var date = LocalDate.of(2026, 9, 5);
        var apply = service.signApply(company, store, warehouse, date, supplier, metadata);
        var snapshot = service.signDocument(company, store, warehouse, date, document, 4L, supplier, metadata,
                documentLines);

        assertThat(apply).matches("WXP1\\.A\\.[A-Za-z0-9_-]{512}");
        assertThat(snapshot).matches("WXP1\\.D\\.[A-Za-z0-9_-]{512}");
        assertThat(service.verifyApply(apply, company, store, warehouse, date, supplier, metadata)).isTrue();
        assertThat(service.verifyDocument(snapshot, company, store, warehouse, date, document, 4L, supplier, metadata,
                documentLines)).isTrue();
        var changedLine = new WarehouseInputLine(UUID.randomUUID(), documentLines.get(0).getProductId(),
                new BigDecimal("3.000"), new BigDecimal("12.34"), new BigDecimal("2.50"), true);
        changedLine.assignPosition(1);
        assertThat(service.verifyDocument(snapshot, company, store, warehouse, date, document, 4L, supplier, metadata,
                List.of(changedLine))).isFalse();
        assertThat(service.verifyDocument(snapshot, company, store, warehouse, date, document, 5L, supplier, metadata,
                documentLines)).isFalse();
        assertThat(service.verifyDocument(apply, company, store, warehouse, date, document, 4L, supplier, metadata,
                documentLines)).isFalse();
    }

    @Test
    void rejectsTamperedMetadataAndScope() {
        var service = service();
        var company = UUID.randomUUID();
        var store = UUID.randomUUID();
        var warehouse = UUID.randomUUID();
        var supplier = UUID.randomUUID();
        var metadata = metadata(supplier);
        var date = LocalDate.of(2026, 9, 5);
        var token = service.signApply(company, store, warehouse, date, supplier, metadata);

        var changed = new WarehouseExcelImportMetadata(metadata.fileName(), metadata.formulas(), metadata.sha256(),
                metadata.sheetName(), true, metadata.skipZeroPriceUpdate(),
                List.of(new WarehouseExcelImportMetadata.Line(metadata.lines().get(0).productId(), List.of(2),
                        "changed", new BigDecimal("12.35"), new BigDecimal("1.25"))));
        assertThat(service.verifyApply(token, company, store, warehouse, date, supplier, changed)).isFalse();
        assertThat(service.verifyApply(token, UUID.randomUUID(), store, warehouse, date, supplier, metadata)).isFalse();
        assertThat(service.verifyApply(token, company, store, warehouse, date.plusDays(1), supplier, metadata)).isFalse();
        assertThat(service.verifyApply(token, company, store, warehouse, date, UUID.randomUUID(), metadata)).isFalse();
    }

    @Test
    void detachedTokenDoesNotEmbedMetadataAndStaysCompactForLargeMetadata() {
        var service = service();
        var company = UUID.randomUUID();
        var store = UUID.randomUUID();
        var warehouse = UUID.randomUUID();
        var supplier = UUID.randomUUID();
        var lines = java.util.stream.IntStream.rangeClosed(1, 5_000)
                .mapToObj(row -> new WarehouseExcelImportMetadata.Line(UUID.randomUUID(), List.of(row),
                        "REF-" + row, new BigDecimal("123.45"), new BigDecimal("12.34")))
                .toList();
        var metadata = new WarehouseExcelImportMetadata("catalogo.xlsx",
                List.of(new WarehouseExcelImportMetadata.Formula("A1", "=" + "x".repeat(32_766), "0")),
                "a".repeat(64), "Hoja larga", true, true, lines);

        var token = service.signApply(company, store, warehouse, LocalDate.of(2026, 9, 5), supplier, metadata);

        assertThat(token).hasSize(519);
        assertThat(token).doesNotContain("catalogo.xlsx", "REF-", "123.45", "32_766");
        assertThat(service.verifyApply(token, company, store, warehouse, LocalDate.of(2026, 9, 5), supplier, metadata)).isTrue();
    }

    @Test
    void commandDoesNotAllowAmbiguousExcelProofControls() {
        var warehouse = UUID.randomUUID();
        var date = LocalDate.of(2026, 9, 5);
        var line = new WarehouseInputLineCommand(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, false);
        var metadata = new WarehouseExcelImportMetadata(
                "catalogo.xlsx", List.of(), "a".repeat(64), "Hoja1", false, false, List.of());
        var applyToken = "WXP1.A." + "A".repeat(512);
        var snapshotToken = "WXP1.D." + "D".repeat(512);

        assertThatThrownBy(() -> new WarehouseInputCommand(
                warehouse, date, null, "origen", null, "concepto",
                WarehouseInputDocumentType.ENTRADA_ALMACEN, WarehouseInputPriceSource.PURCHASE,
                BigDecimal.ZERO, List.of(), List.of(line), null, applyToken, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WarehouseInputCommand(
                warehouse, date, null, "origen", null, "concepto",
                WarehouseInputDocumentType.ENTRADA_ALMACEN, WarehouseInputPriceSource.PURCHASE,
                BigDecimal.ZERO, List.of(), List.of(line), metadata, null, false, snapshotToken))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WarehouseInputCommand(
                warehouse, date, null, "origen", null, "concepto",
                WarehouseInputDocumentType.ENTRADA_ALMACEN, WarehouseInputPriceSource.PURCHASE,
                BigDecimal.ZERO, List.of(), List.of(line), metadata, null, true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private WarehouseExcelImportProvenanceService service() {
        return new WarehouseExcelImportProvenanceService(
                new InstallationIdentityStore(directory, new ReversibleProtector()));
    }

    private static WarehouseExcelImportMetadata metadata(UUID supplier) {
        return new WarehouseExcelImportMetadata("catalogo.xlsx", List.of(), "a".repeat(64), "Hoja 1", true, false,
                List.of(new WarehouseExcelImportMetadata.Line(UUID.randomUUID(), List.of(2, 5),
                        "SUP-001", new BigDecimal("12.34"), new BigDecimal("2.50"))));
    }

    private static List<WarehouseInputLine> documentLines(WarehouseExcelImportMetadata metadata) {
        var productId = metadata.lines().get(0).productId();
        var line = new WarehouseInputLine(UUID.randomUUID(), productId, new BigDecimal("2.000"),
                new BigDecimal("12.34"), new BigDecimal("2.50"), true);
        line.assignPosition(1);
        return List.of(line);
    }

    private static final class ReversibleProtector implements SecretProtector {
        @Override public byte[] protect(byte[] value) { return xor(value); }
        @Override public byte[] unprotect(byte[] value) { return xor(value); }
        private byte[] xor(byte[] value) {
            var result = value.clone();
            for (int index = 0; index < result.length; index++) result[index] ^= 0x5a;
            return result;
        }
    }
}
