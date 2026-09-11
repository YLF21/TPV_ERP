package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.tpverp.backend.audit.AuditService;

class ProductExcelImportOperationsTest {
    final ProductExcelImportPreviewService preview = mock(ProductExcelImportPreviewService.class);
    final ProductExcelImportApplyWriter writer = mock(ProductExcelImportApplyWriter.class);
    final AuditService audit = mock(AuditService.class);
    final ProductExcelImportApplyService service = new ProductExcelImportApplyService(preview, writer, audit);
    final String hash = "a".repeat(64);
    final UUID id = UUID.randomUUID();
    final UUID family = UUID.randomUUID();
    final UUID tax = UUID.randomUUID();
    final List<ProductExcelImportApplyService.WriteItem> captured = new ArrayList<>();

    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("user", "",
                List.of(() -> "GESTION_PRODUCTO", () -> "GESTION_ALMACEN")));
        when(preview.preview(any(), any())).thenAnswer(call -> snapshot(call.getArgument(1)));
        when(writer.write(any())).thenAnswer(call -> {
            List<ProductExcelImportApplyService.WriteItem> items = call.getArgument(0);
            captured.addAll(items);
            return items.stream().map(item -> new ProductExcelImportApplyWriter.AppliedProduct(item.row().rowNumber(),
                    item.row().rowNumbers(), item.existing() == null ? null : item.existing().productId(),
                    item.existing() == null ? UUID.randomUUID() : item.existing().productId(), item.request() != null)).toList();
        });
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    ProductExcelImportPreviewService.PreviewRequest config() {
        return new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A", "name", "B", "purchasePrice", "C"),
                List.of(), new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "WAREHOUSE_INPUT",
                null, null, true, false, "purchasePrice"), null, null, hash, 2, null, Map.of("name", true, "purchasePrice", true));
    }
    ProductExcelImportApplyService.ApplyRequest command(ProductExcelImportApplyService.Operation operation, String fingerprint) {
        return new ProductExcelImportApplyService.ApplyRequest(config(), Map.of(), operation == ProductExcelImportApplyService.Operation.CREATE_MISSING,
                true, null, null, null, false, operation, fingerprint);
    }
    Map<String, Object> values(String code, String name, String price) {
        return new LinkedHashMap<>(Map.of("code", code, "name", name, "purchasePrice", price, "salePrice", "20",
                "familyId", family.toString(), "taxId", tax.toString(), "productType", "UNIT", "priceUseMode", "NORMAL",
                "discountType", "NORMAL", "taxesIncluded", "1"));
    }
    ProductExcelImportPreviewService.PreviewResult snapshot(ProductExcelImportPreviewService.PreviewRequest request) {
        Map<String, Object> db = values("A1", "Current", "10");
        db.put("id", id.toString()); db.put("version", 1L);
        Map<String, Object> changes = new LinkedHashMap<>();
        request.updateFields().forEach((field, enabled) -> {
            if (enabled && Set.of("name", "purchasePrice").contains(field)) changes.put(field, Map.of("before", db.get(field), "after", field.equals("name") ? "New" : "12"));
        });
        var existing = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING",
                values("A1", "New", "12"), db, 1L, changes, List.of(), true);
        var missing = new ProductExcelImportPreviewService.PreviewRow(3, List.of(3), "MISSING",
                values("A2", "Missing", "5"), null, null, Map.of(), List.of(), false);
        return new ProductExcelImportPreviewService.PreviewResult("test.xlsx", hash, "Sheet", List.of(existing, missing),
                2, 1, 1, List.of(), List.of(), hash);
    }

    @ParameterizedTest @EnumSource(ProductExcelImportApplyService.Operation.class)
    void scopesEachButtonAndNeverCreatesMissingDuringDestinationPreparation(ProductExcelImportApplyService.Operation operation) {
        var result = service.apply(new MockMultipartFile("file", "test.xlsx", null, new byte[]{1}), command(operation, hash));
        assertThat(result.errors()).isEmpty();
        var requests = org.mockito.ArgumentCaptor.forClass(ProductExcelImportPreviewService.PreviewRequest.class);
        verify(preview, times(2)).preview(any(), requests.capture());
        var scopedOptions = requests.getAllValues().get(1).options();
        assertThat(scopedOptions.context()).isEqualTo(operation == ProductExcelImportApplyService.Operation.PREPARE_DESTINATION
                ? "WAREHOUSE_INPUT" : "STOCK");
        assertThat(scopedOptions.documentPriceSource()).isEqualTo(operation == ProductExcelImportApplyService.Operation.PREPARE_DESTINATION
                ? "purchasePrice" : null);
        assertThat(captured).hasSize(1);
        var item = captured.get(0);
        if (operation == ProductExcelImportApplyService.Operation.PREPARE_DESTINATION) {
            assertThat(item.request()).isNull();
            assertThat(item.existing().productId()).isEqualTo(id);
            assertThat(result.appliedCount()).isZero();
            assertThat(result.rows()).hasSize(1);
        } else if (operation == ProductExcelImportApplyService.Operation.CREATE_MISSING) {
            assertThat(item.existing()).isNull();
            assertThat(item.request().code()).isEqualTo("A2");
        } else if (operation == ProductExcelImportApplyService.Operation.UPDATE_PURCHASE_PRICE) {
            assertThat(item.request().name()).isEqualTo("Current");
            assertThat(item.request().purchasePrice()).isEqualByComparingTo("12");
        } else {
            assertThat(item.request().name()).isEqualTo("New");
            assertThat(item.request().purchasePrice()).isEqualByComparingTo("12");
        }
        verify(audit).record(eq("PRODUCT_EXCEL_IMPORT_APPLY"), any(), argThat(details ->
                operation.name().equals(details.get("operation"))
                && Objects.equals(details.get("createdCount"), operation == ProductExcelImportApplyService.Operation.CREATE_MISSING ? 1L : 0L)
                && Objects.equals(details.get("updatedCount"), operation == ProductExcelImportApplyService.Operation.UPDATE_PURCHASE_PRICE
                    || operation == ProductExcelImportApplyService.Operation.UPDATE_SELECTED_FIELDS ? 1L : 0L)
                && (operation != ProductExcelImportApplyService.Operation.PREPARE_DESTINATION
                    || Map.of().equals(details.get("changedFields")))));
    }

    @Test void staleReviewPreventsAnyWrite() {
        var result = service.apply(new MockMultipartFile("file", "test.xlsx", null, new byte[]{1}),
                command(ProductExcelImportApplyService.Operation.UPDATE_SELECTED_FIELDS, "b".repeat(64)));
        assertThat(result.errors()).extracting(ProductExcelImportApplyService.ApplyError::code).contains("VERSION_STALE");
        verifyNoInteractions(writer);
        assertAuditHasNoMutations();
    }

    @Test void transactionFailureDoesNotAuditPlannedChangesAsSaved() {
        doThrow(new IllegalStateException("database failure")).when(writer).write(any());
        var result = service.apply(new MockMultipartFile("file", "test.xlsx", null, new byte[]{1}),
                command(ProductExcelImportApplyService.Operation.UPDATE_SELECTED_FIELDS, hash));
        assertThat(result.errors()).extracting(ProductExcelImportApplyService.ApplyError::code).contains("APPLY_TRANSACTION_FAILED");
        assertThat(result.appliedCount()).isZero();
        assertAuditHasNoMutations();
    }

    @Test void preparingStockWithSelectedDraftChangesAuditsNoMasterWrites() {
        var base = config();
        var stock = new ProductExcelImportPreviewService.PreviewRequest(base.mapping(), base.edits(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "STOCK", null, null, true, false),
                null, null, hash, 2, null, base.updateFields());
        var request = new ProductExcelImportApplyService.ApplyRequest(stock, Map.of(), false, false,
                null, null, null, false, ProductExcelImportApplyService.Operation.PREPARE_DESTINATION, hash);
        var result = service.apply(new MockMultipartFile("file", "test.xlsx", null, new byte[]{1}), request);
        assertThat(result.errors()).isEmpty();
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).request()).isNull();
        assertThat(captured.get(0).row().changes()).containsKeys("name", "purchasePrice");
        assertAuditHasNoMutations();
    }

    void assertAuditHasNoMutations() {
        verify(audit).record(eq("PRODUCT_EXCEL_IMPORT_APPLY"), any(), argThat(details ->
                Long.valueOf(0).equals(details.get("createdCount"))
                && Long.valueOf(0).equals(details.get("updatedCount"))
                && Map.of().equals(details.get("changedFields"))));
    }

    @Test void purchaseUpdateRequiresItsCheckbox() {
        var base = config();
        var unchecked = new ProductExcelImportPreviewService.PreviewRequest(base.mapping(), base.edits(), base.options(),
                null, null, hash, 2, null, Map.of("name", true));
        var request = new ProductExcelImportApplyService.ApplyRequest(unchecked, Map.of(), false, true,
                null, null, null, false, ProductExcelImportApplyService.Operation.UPDATE_PURCHASE_PRICE, hash);
        var result = service.apply(new MockMultipartFile("file", "test.xlsx", null, new byte[]{1}), request);
        assertThat(result.errors()).extracting(ProductExcelImportApplyService.ApplyError::code).contains("CONFIRMATION_REQUIRED");
        verifyNoInteractions(writer);
    }
}
