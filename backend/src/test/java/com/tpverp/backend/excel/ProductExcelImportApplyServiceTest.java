package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.reset;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.CatalogService;
import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.organization.CurrentOrganization;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.mockito.ArgumentCaptor;

class ProductExcelImportApplyServiceTest {
    private final ProductExcelImportPreviewService preview = mock(ProductExcelImportPreviewService.class);
    private final ProductExcelImportApplyWriter writer = mock(ProductExcelImportApplyWriter.class);
    private final AuditService audit = mock(AuditService.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private ProductExcelImportApplyService service;
    private final UUID storeId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final String hash = "a".repeat(64);

    @BeforeEach
    void setup() {
        service = new ProductExcelImportApplyService(preview, writer, audit);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "catalog", "n/a", List.of(() -> "GESTION_PRODUCTO", () -> "GESTION_ALMACEN")));
    }

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void requiresHashAndDoesNotReadOrWriteWithoutIt() {
        var result = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                request(null), Map.of(), true, true));
        assertThat(result.errors()).extracting(ProductExcelImportApplyService.ApplyError::code).contains("HASH_REQUIRED");
        verify(preview, never()).preview(any(), any());
        verify(writer, never()).write(any());
    }

    @Test
    void earlyAuditUsesOnlySaneHashAndCanonicalContextWithoutClientTenantValues() {
        var unsafe = new ProductExcelImportPreviewService.PreviewRequest(Map.of(), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false,
                        "not-a-context-" + "x".repeat(1000), storeId, companyId, false, false),
                storeId, companyId, hash, 2, null, Map.of());
        service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(unsafe, Map.of(2, "not-a-token"), false, false));

        verify(audit).record(org.mockito.ArgumentMatchers.eq("PRODUCT_EXCEL_IMPORT_APPLY"),
                org.mockito.ArgumentMatchers.eq(com.tpverp.backend.audit.AuditResult.FALLO),
                org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(details ->
                        "".equals(details.get("context"))
                                && String.valueOf(details.get("sha256")).matches("[0-9a-fA-F]{64}")
                                && !String.valueOf(details).contains("not-a-context-")));
    }

    @Test
    void previewIntegrityErrorBlocksAllWrites() {
        var config = request(hash);
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "ERROR", Map.of(), null, null,
                Map.of(), List.of(new ProductExcelImportPreviewService.ImportError("DATE_INVALID", 2, 2,
                        "offerFrom", "bad", "fecha invalida", "DD-MM-AA", "corrige")));
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(config, Map.of(), true, true));
        assertThat(applied.errors()).extracting(ProductExcelImportApplyService.ApplyError::code).contains("DATE_INVALID");
        verify(writer, never()).write(any());
    }

    @Test
    void cappedPreviewErrorsBlockApplyWithoutDuplicatingTheRowBudget() {
        var rows = new java.util.ArrayList<ProductExcelImportPreviewService.PreviewRow>();
        for (int rowNumber = 2; rowNumber < 5_002; rowNumber++) {
            rows.add(new ProductExcelImportPreviewService.PreviewRow(rowNumber, List.of(rowNumber), "ERROR",
                    Map.of(), null, null, Map.of(), List.of(new ProductExcelImportPreviewService.ImportError(
                            "FIELD_INVALID", rowNumber, 1, "code", "bad", "invalid", "valid", "fix"))));
        }
        var limit = new ProductExcelImportPreviewService.ImportError("ERROR_LIMIT", null, null, "errors", "15000",
                "detalles omitidos", "hasta 5.000", "corrige y vuelve a previsualizar");
        when(preview.preview(any(), any())).thenReturn(result(rows, List.of(limit)));

        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                request(hash), Map.of(), true, true));

        assertThat(applied.errors()).hasSize(5_001);
        assertThat(applied.errors()).extracting(ProductExcelImportApplyService.ApplyError::code)
                .containsOnlyOnce("ERROR_LIMIT");
        verify(writer, never()).write(any());
    }

    @Test
    void missingProductRequiresExplicitAutoAdd() {
        var row = row("MISSING", Map.of("code", "NEW", "name", "Nuevo", "familyId", UUID.randomUUID().toString(),
                "taxId", UUID.randomUUID().toString(), "purchasePrice", "0", "salePrice", "0", "productType", "UNIT"), null, null);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(request(hash), Map.of(2, hash), false, true));
        assertThat(applied.errors()).extracting(ProductExcelImportApplyService.ApplyError::code).contains("MISSING_REVIEW_REQUIRED");
        verify(writer, never()).write(any());
    }

    @Test
    void appliesExistingWithExpectedVersionAndReturnsProductId() {
        var row = row("EXISTING", Map.of("id", productId.toString(), "version", 3L, "code", "A", "name", "Producto",
                "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(), "productType", "UNIT",
                "purchasePrice", "1", "salePrice", "2", "discountType", "NORMAL"), productId, 3L);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        when(writer.write(any())).thenReturn(List.of(new ProductExcelImportApplyWriter.AppliedProduct(2, List.of(2), productId, productId, true)));
        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(request(hash), Map.of(2, hash), true, true));
        assertThat(applied.appliedCount()).isEqualTo(1);
        assertThat(applied.rows()).singleElement().extracting(ProductExcelImportApplyService.AppliedRow::productId).isEqualTo(productId);
    }

    @Test
    void auditFailureAfterWriterDoesNotTurnCommittedApplyIntoRollback() {
        var row = row("EXISTING", Map.of("id", productId.toString(), "version", 3L, "code", "A", "name", "Producto",
                "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(), "productType", "UNIT",
                "purchasePrice", "1", "salePrice", "2", "discountType", "NORMAL"), productId, 3L);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        when(writer.write(any())).thenReturn(List.of(new ProductExcelImportApplyWriter.AppliedProduct(
                2, List.of(2), productId, productId, true)));
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(), any(), any());

        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                request(hash), Map.of(2, hash), true, true));

        assertThat(applied.errors()).isEmpty();
        assertThat(applied.appliedCount()).isEqualTo(1);
        assertThat(applied.rows()).singleElement().extracting(ProductExcelImportApplyService.AppliedRow::productId)
                .isEqualTo(productId);
        verify(writer).write(any());
    }

    @Test
    void existingWithoutExpectedVersionIsRejectedBeforeWriter() {
        var row = completeRow("EXISTING", Map.of("id", productId.toString(), "version", 3L, "code", "A", "name", "Producto",
                "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(), "productType", "UNIT",
                "purchasePrice", "1", "salePrice", "2", "discountType", "NORMAL"), Map.of("name", Map.of("before", "A", "after", "B")));
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(request(hash), Map.of(), true, true));
        assertThat(applied.errors()).extracting(ProductExcelImportApplyService.ApplyError::code).contains("CONCURRENCY_TOKEN_REQUIRED");
        verify(writer, never()).write(any());
    }

    @Test
    void newProductUsesCurrentZeroPriceAndTaxesIncludedDefaults() {
        UUID familyId = UUID.randomUUID(); UUID taxId = UUID.randomUUID();
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "MISSING",
                Map.of("code", "NEW", "name", "Nuevo", "familyId", familyId.toString(), "taxId", taxId.toString(), "productType", "UNIT"),
                null, null, Map.of(), List.of(), false, hash);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        when(writer.write(any())).thenReturn(List.of(new ProductExcelImportApplyWriter.AppliedProduct(2, List.of(2), null, UUID.randomUUID(), true)));
        service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(request(hash), Map.of(2, hash), true, true));
        ArgumentCaptor<List<ProductExcelImportApplyService.WriteItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(item -> {
            assertThat(item.request().purchasePrice()).isZero();
            assertThat(item.request().salePrice()).isZero();
            assertThat(item.request().taxesIncluded()).isTrue();
        });
    }

    @Test
    void writeBoundaryRejectsSpecialPricesOnDiscountProhibitedProducts() {
        UUID familyId = UUID.randomUUID();
        UUID taxId = UUID.randomUUID();
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "MISSING",
                Map.ofEntries(Map.entry("code", "NEW"), Map.entry("name", "Nuevo"),
                        Map.entry("familyId", familyId.toString()), Map.entry("taxId", taxId.toString()),
                        Map.entry("productType", "UNIT"), Map.entry("purchasePrice", "1"),
                        Map.entry("salePrice", "2"), Map.entry("priceUseMode", "NORMAL"),
                        Map.entry("discountType", "1"), Map.entry("memberPrice", "1.50")),
                null, null, Map.of(), List.of(), false, hash);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));

        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                request(hash), Map.of(2, hash), true, true));

        assertThat(applied.errors()).extracting(ProductExcelImportApplyService.ApplyError::code)
                .containsExactly("DISCOUNT_PROHIBITED_PRICE_MODE");
        verify(writer, never()).write(any());
    }

    @Test
    void stockAndOutputAreRejectedWithoutWritingProducts() {
        var stock = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "STOCK", storeId, companyId, false, false),
                storeId, companyId, hash, 2, null, Map.of());
        var stockResult = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(stock, Map.of(), false, false));
        assertThat(stockResult.errors()).extracting(ProductExcelImportApplyService.ApplyError::code).contains("APPLY_CONTEXT_UNSUPPORTED");
        var output = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "WAREHOUSE_OUTPUT", storeId, companyId, false, false),
                storeId, companyId, hash, 2, null, Map.of());
        var outputResult = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(output, Map.of(), false, false));
        assertThat(outputResult.errors()).extracting(ProductExcelImportApplyService.ApplyError::code).contains("APPLY_CONTEXT_UNSUPPORTED");
        verify(writer, never()).write(any());
    }

    @Test
    void canonicalizesContextBeforeUnsupportedDecisionAndAuthorizesWarehouseInput() {
        var lowerStock = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "stock", storeId, companyId, false, false),
                storeId, companyId, hash, 2, null, Map.of());
        var rejected = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(lowerStock, Map.of(), false, false));
        assertThat(rejected.errors()).extracting(ProductExcelImportApplyService.ApplyError::code)
                .containsExactly("APPLY_CONTEXT_UNSUPPORTED");
        verify(preview, never()).preview(any(), any());

        reset(preview, writer);
        var lowerInput = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "warehouse_input", storeId, companyId, false, false),
                storeId, companyId, hash, 2, null, Map.of());
        when(preview.preview(any(), any())).thenReturn(result(List.of(), List.of()));
        when(writer.write(any())).thenReturn(List.of());
        service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(lowerInput, Map.of(), false, false));
        verify(preview).preview(any(), argThat(value -> "WAREHOUSE_INPUT".equals(value.options().context())));
    }

    @Test
    void staleVersionIsStructuredAndTransactionFailureIsReported() {
        var row = row("EXISTING", Map.of("id", productId.toString(), "version", 3L, "code", "A", "name", "Producto",
                "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(), "productType", "UNIT",
                "purchasePrice", "1", "salePrice", "2"), productId, 3L);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        doThrow(new ProductExcelImportApplyWriter.StaleVersionException(productId, 3, 4)).when(writer).write(any());
        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(request(hash), Map.of(2, hash), true, true));
        assertThat(applied.errors()).extracting(ProductExcelImportApplyService.ApplyError::code).containsExactly("VERSION_STALE");
    }

    @Test
    void unexpectedWriterValidationFailureIsNotMisreportedAsStaleVersion() {
        var row = completeRow("EXISTING", Map.of("id", productId.toString(), "version", 3L, "code", "A",
                "name", "Nuevo", "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(),
                "productType", "UNIT", "purchasePrice", "1", "salePrice", "2"),
                Map.of("name", Map.of("before", "Producto", "after", "Nuevo")));
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        doThrow(new IllegalArgumentException("internal validation")).when(writer).write(any());

        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                request(hash), Map.of(2, hash), true, true));

        assertThat(applied.errors()).extracting(ProductExcelImportApplyService.ApplyError::code)
                .containsExactly("APPLY_TRANSACTION_FAILED");
    }

    @Test
    void explicitlyTypedConcurrentConflictRemainsRepreviewable() {
        var row = completeRow("EXISTING", Map.of("id", productId.toString(), "version", 3L, "code", "A",
                "name", "Nuevo", "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(),
                "productType", "UNIT", "purchasePrice", "1", "salePrice", "2"),
                Map.of("name", Map.of("before", "Producto", "after", "Nuevo")));
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        doThrow(new ProductExcelImportApplyWriter.ConcurrentImportConflictException("expected conflict"))
                .when(writer).write(any());

        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                request(hash), Map.of(2, hash), true, true));

        assertThat(applied.errors()).extracting(ProductExcelImportApplyService.ApplyError::code)
                .containsExactly("VERSION_STALE");
    }

    @Test
    void auditFailureAfterWriterFailurePreservesStructuredWriterError() {
        var row = row("EXISTING", Map.of("id", productId.toString(), "version", 3L, "code", "A", "name", "Producto",
                "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(), "productType", "UNIT",
                "purchasePrice", "1", "salePrice", "2"), productId, 3L);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        doThrow(new ProductExcelImportApplyWriter.StaleVersionException(productId, 3, 4)).when(writer).write(any());
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(), any(), any());

        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                request(hash), Map.of(2, hash), true, true));

        assertThat(applied.errors()).extracting(ProductExcelImportApplyService.ApplyError::code)
                .containsExactly("VERSION_STALE");
        assertThat(applied.appliedCount()).isZero();
    }

    @Test
    void inventedConcurrencyTokenIsRejectedAsStaleBeforeWriter() {
        var row = row("EXISTING", Map.of("id", productId.toString(), "version", 3L, "code", "A", "name", "Producto",
                "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(), "productType", "UNIT",
                "purchasePrice", "1", "salePrice", "2"), productId, 3L);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));

        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                request(hash), Map.of(2, "b".repeat(64)), true, true));

        assertThat(applied.errors()).extracting(ProductExcelImportApplyService.ApplyError::code)
                .containsExactly("VERSION_STALE");
        verify(writer, never()).write(any());
    }

    @Test
    void missingToExistingClassificationChangeIsRejectedAsStale() {
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING",
                Map.of("id", productId.toString(), "version", 4L, "code", "NEW", "name", "Nuevo",
                        "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(),
                        "productType", "UNIT", "purchasePrice", "1", "salePrice", "2"),
                Map.of("id", productId.toString()), 4L, Map.of(), List.of(), false, hash);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));

        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                request(hash), Map.of(2, "b".repeat(64)), true, true));

        assertThat(applied.errors()).extracting(ProductExcelImportApplyService.ApplyError::code)
                .containsExactly("VERSION_STALE");
        verify(writer, never()).write(any());
    }

    @Test
    void showOnlyImportedTokenCanApplyAfterAuthoritativeRepreview() {
        var row = row("EXISTING", Map.of("id", productId.toString(), "version", 3L, "code", "A", "name", "Producto",
                "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(), "productType", "UNIT",
                "purchasePrice", "1", "salePrice", "2"), productId, 3L);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        when(writer.write(any())).thenReturn(List.of(new ProductExcelImportApplyWriter.AppliedProduct(
                2, List.of(2), productId, productId, false)));
        var showOnlyRequest = new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), true,
                        "WAREHOUSE_INPUT", storeId, companyId, false, false),
                storeId, companyId, hash, 2, null, Map.of());

        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                showOnlyRequest, Map.of(2, hash), false, true));

        assertThat(applied.errors()).isEmpty();
        assertThat(applied.rows()).singleElement().extracting(ProductExcelImportApplyService.AppliedRow::productId)
                .isEqualTo(productId);
        verify(writer).write(any());
    }

    @Test
    void warehouseNoOpNeedsOnlyWarehouseAndReportsZeroMutations() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "warehouse", "n/a", List.of(() -> "GESTION_ALMACEN")));
        var row = completeRow("EXISTING", Map.of("id", productId.toString(), "version", 3L, "code", "A",
                "name", "Producto", "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(),
                "productType", "UNIT", "purchasePrice", "1", "salePrice", "2", "discountType", "NORMAL"), Map.of());
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        when(writer.write(any())).thenReturn(List.of(new ProductExcelImportApplyWriter.AppliedProduct(2, List.of(2), productId, productId, false)));
        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                warehouseRequest(hash), Map.of(2, hash), true, true));
        assertThat(applied.appliedCount()).isZero();
        assertThat(applied.rows()).singleElement().extracting(ProductExcelImportApplyService.AppliedRow::productId).isEqualTo(productId);
    }

    @Test
    void warehouseMutationRequiresProductPermissionAndDoesNotWrite() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "warehouse", "n/a", List.of(() -> "GESTION_ALMACEN")));
        var row = completeRow("MISSING", Map.of("code", "NEW", "name", "Nuevo", "familyId", UUID.randomUUID().toString(),
                "taxId", UUID.randomUUID().toString(), "productType", "UNIT", "purchasePrice", "0", "salePrice", "0"), Map.of());
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                warehouseRequest(hash), Map.of(2, hash), true, true))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(writer, never()).write(any());
    }

    @Test
    void warehouseMutationRequiresExplicitConfirmation() {
        var row = completeRow("EXISTING", Map.of("id", productId.toString(), "version", 3L, "code", "A",
                "name", "Nuevo", "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(),
                "productType", "UNIT", "purchasePrice", "1", "salePrice", "2"),
                Map.of("name", Map.of("before", "Producto", "after", "Nuevo")));
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        for (Boolean confirmation : java.util.Arrays.asList(Boolean.FALSE, null)) {
            var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                warehouseRequest(hash), Map.of(2, hash), true, confirmation));
            assertThat(applied.errors()).extracting(ProductExcelImportApplyService.ApplyError::code)
                    .containsExactly("CONFIRMATION_REQUIRED");
        }
        verify(writer, never()).write(any());
    }

    @Test
    void nullUpdateFieldsMeansAllAndPreviewErrorsKeepReceivedValue() {
        var row = completeRow("EXISTING", Map.of("id", productId.toString(), "version", 3L, "code", "A",
                "name", "Nuevo", "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(),
                "productType", "UNIT", "purchasePrice", "1", "salePrice", "2"),
                Map.of("name", Map.of("before", "Producto", "after", "Nuevo")));
        var config = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "WAREHOUSE_INPUT",
                        storeId, companyId, false, false), storeId, companyId, hash, 2, null, null);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        when(writer.write(any())).thenReturn(List.of(new ProductExcelImportApplyWriter.AppliedProduct(2, List.of(2), productId, productId, true)));
        service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(config, Map.of(2, hash), true, true));
        ArgumentCaptor<List<ProductExcelImportApplyService.WriteItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(item -> assertThat(item.request().name()).isEqualTo("Nuevo"));

        var invalid = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "ERROR", Map.of(), null, null,
                Map.of(), List.of(new ProductExcelImportPreviewService.ImportError("DATE_INVALID", 2, 2,
                        "offerFrom", "31-02-2026", "fecha invalida", "DD-MM-AAAA", "corrige")));
        when(preview.preview(any(), any())).thenReturn(result(List.of(invalid), List.of()));
        var failed = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(config, Map.of(), false, true));
        assertThat(failed.errors()).singleElement().satisfies(error -> assertThat(error.receivedValue()).isEqualTo("31-02-2026"));
    }

    @Test
    void blankDiscountImportPreservesNoneFromExistingProduct() {
        UUID familyId = UUID.randomUUID();
        UUID taxId = UUID.randomUUID();
        Map<String, Object> database = new java.util.LinkedHashMap<>(Map.ofEntries(
                Map.entry("id", productId.toString()), Map.entry("version", 3L), Map.entry("code", "A"),
                Map.entry("barcode", ""), Map.entry("name", "Producto"), Map.entry("familyId", familyId.toString()),
                Map.entry("taxId", taxId.toString()), Map.entry("productType", "UNIT"),
                Map.entry("purchasePrice", "1"), Map.entry("salePrice", "2"), Map.entry("discountType", "1"),
                Map.entry("priceUseMode", "NORMAL"), Map.entry("taxesIncluded", "1")));
        Map<String, Object> excel = new java.util.LinkedHashMap<>(database);
        excel.put("name", "Nuevo");
        excel.put("discountType", "");
        excel.put("prohibitedDiscount", "");
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING", excel,
                database, 3L, Map.of("name", Map.of("before", "Producto", "after", "Nuevo")), List.of(), false, hash);
        var config = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "WAREHOUSE_INPUT",
                        storeId, companyId, false, false), storeId, companyId, hash, 2, null,
                Map.of("name", true, "discountType", true));
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        when(writer.write(any())).thenReturn(List.of(new ProductExcelImportApplyWriter.AppliedProduct(
                2, List.of(2), productId, productId, true)));

        service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(config, Map.of(2, hash), true, true));

        ArgumentCaptor<List<ProductExcelImportApplyService.WriteItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(item ->
                assertThat(item.request().discountType()).isEqualTo(DiscountType.NONE));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"001,true", "001002,true", "001,false", "001002,false"})
    void unifiedFamilyWritesBothIdentifiersOnlyWhenChecked(String code, boolean selected) throws Exception {
        UUID familyId = UUID.randomUUID();
        UUID oldSubfamilyId = UUID.randomUUID();
        UUID newSubfamilyId = UUID.randomUUID();
        Map<String, Object> database = new java.util.LinkedHashMap<>(Map.ofEntries(
                Map.entry("id", productId.toString()), Map.entry("version", 3L), Map.entry("code", "A"),
                Map.entry("name", "Producto"), Map.entry("familyId", familyId.toString()),
                Map.entry("subfamilyId", oldSubfamilyId.toString()), Map.entry("taxId", UUID.randomUUID().toString()),
                Map.entry("productType", "UNIT"), Map.entry("purchasePrice", "1"), Map.entry("salePrice", "2"),
                Map.entry("discountType", "NORMAL"), Map.entry("priceUseMode", "NORMAL"), Map.entry("taxesIncluded", "1")));
        Map<String, Object> excel = new java.util.LinkedHashMap<>();
        excel.put("familyId", familyId.toString());
        excel.put("familyBusinessCode", code);
        excel.put("subfamilyId", code.length() == 3 ? "" : newSubfamilyId.toString());
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING", excel,
                database, 3L, Map.of(), List.of());
        var config = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A", "familyId", "B"),
                List.of(), new ProductExcelImportPreviewService.PreviewOptions(), storeId, companyId, hash, 2, null,
                Map.of("familyId", selected));
        var method = ProductExcelImportApplyService.class.getDeclaredMethod("productRequest",
                ProductExcelImportPreviewService.PreviewRow.class, Map.class, ProductExcelImportPreviewService.PreviewRequest.class);
        method.setAccessible(true);
        Object result = method.invoke(service, row, database, config);
        var accessor = result.getClass().getDeclaredMethod("request");
        accessor.setAccessible(true);
        var request = (com.tpverp.backend.catalog.CatalogService.ProductRequest) accessor.invoke(result);
        assertThat(request).isNotNull();
        assertThat(request.familyId()).isEqualTo(familyId);
        assertThat(request.subfamilyId()).isEqualTo(!selected ? oldSubfamilyId : code.length() == 3 ? null : newSubfamilyId);
    }

    @Test
    void canonicalDiscountValueWinsOverTheLegacyAliasAtTheWriteBoundary() {
        UUID familyId = UUID.randomUUID();
        UUID taxId = UUID.randomUUID();
        Map<String, Object> database = new java.util.LinkedHashMap<>(Map.ofEntries(
                Map.entry("id", productId.toString()), Map.entry("version", 3L), Map.entry("code", "A"),
                Map.entry("barcode", ""), Map.entry("name", "Producto"), Map.entry("familyId", familyId.toString()),
                Map.entry("taxId", taxId.toString()), Map.entry("productType", "UNIT"),
                Map.entry("purchasePrice", "1"), Map.entry("salePrice", "2"), Map.entry("discountType", "0"),
                Map.entry("prohibitedDiscount", "0"), Map.entry("priceUseMode", "NORMAL"),
                Map.entry("taxesIncluded", "1")));
        Map<String, Object> excel = new java.util.LinkedHashMap<>(database);
        excel.put("discountType", "1");
        excel.put("prohibitedDiscount", "0");
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING", excel,
                database, 3L, Map.of("discountType", Map.of("before", "0", "after", "1")),
                List.of(), false, hash);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        when(writer.write(any())).thenReturn(List.of(new ProductExcelImportApplyWriter.AppliedProduct(
                2, List.of(2), productId, productId, true)));

        var config = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false,
                        "WAREHOUSE_INPUT", storeId, companyId, false, false),
                storeId, companyId, hash, 2, null, Map.of("discountType", true));
        service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                config, Map.of(2, hash), true, true));

        ArgumentCaptor<List<ProductExcelImportApplyService.WriteItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(item ->
                assertThat(item.request().discountType()).isEqualTo(DiscountType.NONE));
    }

    @Test
    void skipZeroKeepsStoredPriceWhileApplyingAnotherFieldAndForcesFullSnapshot() {
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING", Map.of("id", productId.toString(), "version", 3L, "code", "A", "name", "Nuevo",
                "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(), "productType", "UNIT",
                "purchasePrice", "0", "salePrice", "2", "discountType", "NORMAL"),
                Map.of("id", productId.toString(), "version", 3L, "code", "A", "name", "Producto",
                        "familyId", UUID.randomUUID().toString(), "taxId", UUID.randomUUID().toString(), "productType", "UNIT",
                        "purchasePrice", "1", "salePrice", "2", "discountType", "NORMAL"), 3L,
                Map.of("name", Map.of("before", "Producto", "after", "Nuevo")), List.of(), true, hash);
        var config = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), true, "WAREHOUSE_INPUT", storeId, companyId, true, false),
                storeId, companyId, hash, 2, null, Map.of("name", true, "purchasePrice", true));
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        when(writer.write(any())).thenReturn(List.of(new ProductExcelImportApplyWriter.AppliedProduct(2, List.of(2), productId, productId, true)));
        service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(config, Map.of(2, hash), true, true));
        ArgumentCaptor<List<ProductExcelImportApplyService.WriteItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(item ->
                assertThat(item.request().purchasePrice()).isEqualByComparingTo("1"));
        ArgumentCaptor<ProductExcelImportPreviewService.PreviewRequest> previewCaptor = ArgumentCaptor.forClass(ProductExcelImportPreviewService.PreviewRequest.class);
        verify(preview).preview(any(), previewCaptor.capture());
        assertThat(previewCaptor.getValue().options().showOnlyImported()).isFalse();
    }

    @Test
    void newProductSkipZeroKeepsExcelZeroButSendsNullOptionalPrices() {
        UUID familyId = UUID.randomUUID();
        UUID taxId = UUID.randomUUID();
        var config = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "WAREHOUSE_INPUT",
                        storeId, companyId, true, false), storeId, companyId, hash, 2, null, Map.of());
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "MISSING",
                Map.of("code", "NEW", "name", "Nuevo", "familyId", familyId.toString(), "taxId", taxId.toString(),
                        "productType", "UNIT", "purchasePrice", "0", "salePrice", "0", "memberPrice", "0",
                        "wholesalePrice", "0", "offerPrice", "0"), null, null, Map.of(), List.of(), false, hash);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        when(writer.write(any())).thenReturn(List.of(new ProductExcelImportApplyWriter.AppliedProduct(
                2, List.of(2), null, UUID.randomUUID(), true)));
        service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(config, Map.of(2, hash), true, true));
        ArgumentCaptor<List<ProductExcelImportApplyService.WriteItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).write(captor.capture());
        assertThat(row.excelData()).containsEntry("memberPrice", "0").containsEntry("offerPrice", "0");
        assertThat(captor.getValue()).singleElement().satisfies(item -> {
            assertThat(item.request().memberPrice()).isNull();
            assertThat(item.request().wholesalePrice()).isNull();
            assertThat(item.request().offerPrice()).isNull();
        });
    }

    @Test
    void optionalZeroPriceIsRejectedBeforeWriterWhenSkipZeroIsDisabled() {
        UUID familyId = UUID.randomUUID();
        UUID taxId = UUID.randomUUID();
        var config = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "WAREHOUSE_INPUT",
                        storeId, companyId, false, false), storeId, companyId, hash, 2, null, Map.of());
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "MISSING",
                Map.of("code", "NEW", "name", "Nuevo", "familyId", familyId.toString(), "taxId", taxId.toString(),
                        "productType", "UNIT", "purchasePrice", "0", "salePrice", "0", "memberPrice", "0"),
                null, null, Map.of(), List.of(), false, hash);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(config,
                Map.of(2, hash), true, true));
        assertThat(applied.errors()).extracting(ProductExcelImportApplyService.ApplyError::code)
                .containsExactly("ZERO_PRICE_INVALID");
        verify(writer, never()).write(any());
    }

    @Test
    void rejectsOversizedOrUnexpectedConcurrencyTokenMapsBeforeWriter() {
        var row = row("EXISTING", Map.of("id", productId.toString(), "version", 3L, "code", "A",
                "name", "Producto"), productId, 3L);
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of()));
        Map<Integer, String> oversized = new java.util.LinkedHashMap<>();
        for (int index = 1; index <= 5_001; index++) oversized.put(index, hash);
        var oversizedResult = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                request(hash), oversized, true, true));
        assertThat(oversizedResult.errors()).extracting(ProductExcelImportApplyService.ApplyError::code)
                .containsExactly("TOKEN_LIMIT");

        var unexpected = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                request(hash), Map.of(999, hash), true, true));
        assertThat(unexpected.errors()).extracting(ProductExcelImportApplyService.ApplyError::code)
                .contains("TOKEN_UNEXPECTED", "CONCURRENCY_TOKEN_REQUIRED");
        verify(writer, never()).write(any());
    }

    @Test
    void rejectsBarcode2PrimaryCollisionBeforeWriter() {
        var collision = new ProductExcelImportPreviewService.ImportError(
                "IDENTIFIER_DUPLICATE", 2, 2, "barcode2", "CODE-X",
                "El codigo de barras secundario no puede coincidir con un identificador principal",
                "Un identificador secundario distinto", "Corrige el valor");
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "ERROR",
                Map.of("code", "CODE-X", "barcode2", "CODE-X"), null, null, Map.of(), List.of(collision));
        when(preview.preview(any(), any())).thenReturn(result(List.of(row), List.of(collision)));

        var applied = service.apply(file(), new ProductExcelImportApplyService.ApplyRequest(
                request(hash), Map.of(2, hash), false, true));

        assertThat(applied.errors()).extracting(ProductExcelImportApplyService.ApplyError::code)
                .contains("IDENTIFIER_DUPLICATE");
        verify(writer, never()).write(any());
    }

    private MockMultipartFile file() { return new MockMultipartFile("file", "catalogo.xlsx", "application/octet-stream", new byte[] {1}); }
    private ProductExcelImportPreviewService.PreviewRequest request(String expectedHash) {
        return new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "WAREHOUSE_INPUT", storeId, companyId, false, false),
                storeId, companyId, expectedHash, 2, null, Map.of());
    }
    private ProductExcelImportPreviewService.PreviewResult result(List<ProductExcelImportPreviewService.PreviewRow> rows,
            List<ProductExcelImportPreviewService.ImportError> errors) {
        return new ProductExcelImportPreviewService.PreviewResult("catalogo.xlsx", hash, "Hoja", rows, rows.size(), 0, 0, errors);
    }
    private ProductExcelImportPreviewService.PreviewRow row(String classification, Map<String, Object> data, UUID id, Long version) {
        return new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), classification, data, id == null ? null : Map.of("id", id.toString()), version,
                "EXISTING".equals(classification) ? Map.of("name", Map.of("before", "old", "after", "Producto")) : Map.of(), List.of(),
                "EXISTING".equals(classification), classification.equals("ERROR") ? null : hash);
    }

    private ProductExcelImportPreviewService.PreviewRow completeRow(String classification, Map<String, Object> data,
            Map<String, Object> changes) {
        UUID id = data.get("id") == null ? null : UUID.fromString(String.valueOf(data.get("id")));
        return new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), classification, data,
                id == null ? null : data, id == null ? null : 3L, changes, List.of(), changes.containsKey("purchasePrice"), classification.equals("ERROR") ? null : hash);
    }

    private ProductExcelImportPreviewService.PreviewRequest warehouseRequest(String expectedHash) {
        return new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "WAREHOUSE_INPUT", storeId, companyId, false, false),
                storeId, companyId, expectedHash, 2, null, Map.of());
    }
}
