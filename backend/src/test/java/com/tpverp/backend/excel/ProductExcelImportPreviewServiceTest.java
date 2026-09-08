package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductIdentifier;
import com.tpverp.backend.catalog.ProductIdentifierRepository;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.catalog.Family;
import com.tpverp.backend.catalog.FamilyRepository;
import com.tpverp.backend.catalog.SubfamilyRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductExcelImportPreviewServiceTest {

    private final ProductExcelImportReadService reader = mock(ProductExcelImportReadService.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final ProductIdentifierRepository identifiers = mock(ProductIdentifierRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final StoreTaxRepository taxes = mock(StoreTaxRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final ProductExcelImportPreviewService service = new ProductExcelImportPreviewService(
            reader, organization, identifiers, products, taxes, audit, null, null);
    private final UUID storeId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        var store = mock(Store.class);
        var company = mock(Company.class);
        when(store.getId()).thenReturn(storeId);
        when(store.getEmpresa()).thenReturn(company);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentStore()).thenReturn(store);
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of());
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(List.of());
    }

    @Test
    void auditFailureDoesNotChangeStructuredPreviewResult() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A1"))));
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(), any(), any());

        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of(), List.of(), new ProductExcelImportPreviewService.PreviewOptions(), null, null,
                null, 2, null, Map.of()));

        assertThat(result.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("CONTEXT_REQUIRED");
    }

    @Test
    void respectsStartRowAndIgnoresPriceOnlyRows() {
        when(reader.read(any())).thenReturn(read(
                List.of(c("Codigo"), c("Nombre"), c("Precio")),
                List.of(c(""), c(""), c("9")),
                List.of(c(""), c("Solo nombre"), c("9"))));
        var result = service.preview(null, request(Map.of("code", "A", "name", "B", "purchasePrice", "C"), 2, false));

        assertThat(result.detectedRows()).isEqualTo(1);
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.rowNumber()).isEqualTo(3);
            assertThat(row.classification()).isEqualTo("ERROR");
            assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                    .contains("IDENTIFIER_REQUIRED");
        });
    }

    @Test
    void mergesExistingRowsResolvedByCodeAndBarcodeButRejectsPriceDivergence() throws Exception {
        var merge = ProductExcelImportPreviewService.class.getDeclaredMethod("mergeDuplicates", List.class, Map.class);
        merge.setAccessible(true);
        UUID productId = UUID.randomUUID();
        Map<String, Object> database = Map.of("id", productId.toString());
        var codeRow = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING",
                Map.of("code", "CODE", "name", "Producto", "purchasePrice", "10", "quantity", "1"),
                database, 1L, Map.of(), List.of());
        var barcodeRow = new ProductExcelImportPreviewService.PreviewRow(3, List.of(3), "EXISTING",
                Map.of("barcode", "BAR", "name", "Producto", "purchasePrice", "10", "quantity", "2"),
                database, 1L, Map.of(), List.of());

        @SuppressWarnings("unchecked")
        List<ProductExcelImportPreviewService.PreviewRow> merged =
                (List<ProductExcelImportPreviewService.PreviewRow>) merge.invoke(service, List.of(codeRow, barcodeRow),
                        Map.of("quantity", "D"));
        assertThat(merged).singleElement().satisfies(row -> {
            assertThat(row.rowNumbers()).containsExactly(2, 3);
            assertThat(row.excelData()).containsEntry("quantity", "3");
        });

        var divergent = new ProductExcelImportPreviewService.PreviewRow(3, List.of(3), "EXISTING",
                Map.of("barcode", "BAR", "name", "Producto", "purchasePrice", "11", "quantity", "2"),
                database, 1L, Map.of(), List.of());
        @SuppressWarnings("unchecked")
        List<ProductExcelImportPreviewService.PreviewRow> conflicts =
                (List<ProductExcelImportPreviewService.PreviewRow>) merge.invoke(service, List.of(codeRow, divergent),
                        Map.of("quantity", "D"));
        assertThat(conflicts).allSatisfy(row -> assertThat(row.classification()).isEqualTo("ERROR"));
    }

    @Test
    void canonicalizesDateTaxAndHidesDatabaseWhenRequested() {
        UUID productId = UUID.randomUUID();
        UUID taxId = UUID.randomUUID();
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(4L);
        when(product.getCode()).thenReturn("A1");
        when(product.getBarcode()).thenReturn(null);
        when(product.getName()).thenReturn(null);
        when(product.getPurchasePrice()).thenReturn(new BigDecimal("1.00"));
        when(product.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(product.getTaxId()).thenReturn(taxId);
        when(product.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(product.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(
                List.of(new ProductIdentifier(storeId, productId, com.tpverp.backend.catalog.IdentifierType.CODIGO, "A1")));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Oferta hasta"), c("IVA")),
                List.of(c("A1"), c("31-12-26"), c("21"))));
        var tax = mock(com.tpverp.backend.catalog.StoreTax.class);
        when(tax.getId()).thenReturn(taxId);
        when(tax.getStoreId()).thenReturn(storeId);
        when(tax.isActive()).thenReturn(true);
        when(tax.getPercentage()).thenReturn(new BigDecimal("21.00"));
        when(taxes.findByStoreIdOrderByPorcentaje(storeId)).thenReturn(List.of(tax));

        var result = service.preview(null, request(Map.of("code", "A", "offerUntil", "B", "taxId", "C"), 2, true));
        verify(taxes).findByStoreIdOrderByPorcentaje(storeId);
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.classification()).isEqualTo("EXISTING");
            assertThat(row.excelData()).containsEntry("offerUntil", "2026-12-31").containsEntry("taxId", taxId.toString());
            assertThat(row.databaseData()).isNull();
            assertThat(row.version()).isNull();
            assertThat(row.changes()).isEmpty();
            assertThat(row.concurrencyToken()).isNotBlank();
        });
    }

    @Test
    void concurrencyTokenIsStableForSameSnapshotAndChangesWithProductVersion() {
        UUID productId = UUID.randomUUID();
        Product product = mock(Product.class);
        AtomicLong version = new AtomicLong(4L);
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenAnswer(ignored -> version.get());
        when(product.getCode()).thenReturn("A1");
        when(product.getBarcode()).thenReturn(null);
        when(product.getName()).thenReturn("Producto");
        when(product.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(product.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(product.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(
                List.of(new ProductIdentifier(storeId, productId, com.tpverp.backend.catalog.IdentifierType.CODIGO, "A1")));
        when(taxes.findByStoreIdOrderByPorcentaje(storeId)).thenReturn(List.of());
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A1"))));
        var normalRequest = request(Map.of("code", "A"), 2, false);
        var hiddenRequest = request(Map.of("code", "A"), 2, true);

        var normal = service.preview(null, normalRequest);
        var hidden = service.preview(null, hiddenRequest);

        String stable = normal.rows().get(0).concurrencyToken();
        assertThat(stable).isNotBlank();
        assertThat(hidden.rows()).singleElement().satisfies(row -> {
            assertThat(row.concurrencyToken()).isEqualTo(stable);
            assertThat(row.databaseData()).isNull();
            assertThat(row.version()).isNull();
            assertThat(row.changes()).isEmpty();
        });

        version.set(5L);
        var changed = service.preview(null, normalRequest);
        assertThat(changed.rows()).singleElement().extracting(
                ProductExcelImportPreviewService.PreviewRow::concurrencyToken)
                .isNotEqualTo(stable);
    }

    @Test
    void exposesDatabaseDecimalsAsExactStringsForTheBrowser() {
        UUID productId = UUID.randomUUID();
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(4L);
        when(product.getCode()).thenReturn("A1");
        when(product.getName()).thenReturn("Producto");
        when(product.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(product.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(product.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        when(product.getPurchasePrice()).thenReturn(new BigDecimal("9007199254740993.01"));
        when(product.getPackageQuantity()).thenReturn(new BigDecimal("1234567890123456.001"));
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(
                List.of(new ProductIdentifier(storeId, productId,
                        com.tpverp.backend.catalog.IdentifierType.CODIGO, "A1")));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A1"))));

        var result = service.preview(null, request(Map.of("code", "A"), 2, false));

        assertThat(result.rows()).singleElement().satisfies(row -> assertThat(row.databaseData())
                .containsEntry("purchasePrice", "9007199254740993.01")
                .containsEntry("packageQuantity", "1234567890123456.001"));
    }

    @Test
    void appliesGlobalOrExcelValueSourcesAndRejectsHashMismatch() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Usar precio")), List.of(c("NUEVO"), c("2"))));
        var excel = new ProductExcelImportPreviewService.PreviewOptions(
                Map.of("priceUseMode", "4"),
                Map.of("priceUseMode", new ProductExcelImportPreviewService.ValueSource("excel", null)),
                false, "WAREHOUSE_INPUT", storeId, companyId, false, false);
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "priceUseMode", "B"), List.of(), excel, storeId, companyId, null, 2, null, Map.of()));
        assertThat(result.rows()).singleElement().satisfies(row -> assertThat(row.excelData()).containsEntry("priceUseMode", "MEMBER_PRICE"));

        var changed = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A"), List.of(), excel, storeId, companyId, "b".repeat(64), 2, null, Map.of()));
        assertThat(changed.errors()).extracting(ProductExcelImportPreviewService.ImportError::code).containsExactly("FILE_CHANGED");
    }

    @Test
    void canonicalizesTheLegacyProhibitedDiscountAliasBeforeApply() {
        UUID productId = UUID.randomUUID();
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(1L);
        when(product.getCode()).thenReturn("A1");
        when(product.getName()).thenReturn("Existente");
        when(product.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(product.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(product.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(List.of(
                new ProductIdentifier(storeId, productId,
                        com.tpverp.backend.catalog.IdentifierType.CODIGO, "A1")));
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(reader.read(any())).thenReturn(read(
                List.of(c("Codigo"), c("Prohibido legado")), List.of(c("A1"), c("0"))));
        var configured = new ProductExcelImportPreviewService.PreviewOptions(
                Map.of(), Map.of("discountType",
                        new ProductExcelImportPreviewService.ValueSource("global", "1")),
                false, "WAREHOUSE_INPUT", storeId, companyId, false, false);

        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "prohibitedDiscount", "B"), List.of(), configured,
                storeId, companyId, null, 2, null, Map.of("discountType", true)));

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.excelData()).containsEntry("discountType", "1")
                    .containsEntry("prohibitedDiscount", "1");
            assertThat(row.changes()).containsKey("discountType");
        });
    }

    @Test
    void rejectsAmbiguousDiscountAliasPairsInTheContract() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A1"))));
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "discountType", "B", "prohibitedDiscount", "C"), List.of(), options(),
                storeId, companyId, null, 2, null, Map.of()));

        assertThat(result.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("VALUE_SOURCE_INVALID");
    }

    @Test
    void mergesRowsForSameProductsAndSumsCompatibleQuantities() {
        UUID productId = UUID.randomUUID();
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(1L);
        when(product.getCode()).thenReturn("A");
        when(product.getName()).thenReturn("Producto");
        when(product.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(product.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(product.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        UUID product2Id = UUID.randomUUID();
        Product product2 = mock(Product.class);
        when(product2.getId()).thenReturn(product2Id);
        when(product2.getVersion()).thenReturn(1L);
        when(product2.getCode()).thenReturn("B");
        when(product2.getName()).thenReturn("Otro");
        when(product2.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(product2.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(product2.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product, product2));
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(List.of(
                new ProductIdentifier(storeId, productId, com.tpverp.backend.catalog.IdentifierType.CODIGO, "A"),
                new ProductIdentifier(storeId, productId, com.tpverp.backend.catalog.IdentifierType.CODIGO_BARRAS, "X"),
                new ProductIdentifier(storeId, product2Id, com.tpverp.backend.catalog.IdentifierType.CODIGO, "B"),
                new ProductIdentifier(storeId, product2Id, com.tpverp.backend.catalog.IdentifierType.CODIGO_BARRAS, "Z"),
                new ProductIdentifier(storeId, product2Id, com.tpverp.backend.catalog.IdentifierType.CODIGO_BARRAS, "W")));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Barras"), c("Nombre"), c("Cantidad")),
                List.of(c("A"), c("X"), c("Producto"), c("1")),
                List.of(c("A"), c("X"), c("Producto"), c("2")),
                List.of(c("B"), c("Z"), c("Otro"), c("1")),
                List.of(c("B"), c("W"), c("Otro"), c("1"))));
        var result = service.preview(null, request(Map.of("code", "A", "barcode", "B", "name", "C", "quantity", "D"), 2, false));
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows()).anySatisfy(row -> assertThat(row.excelData()).containsEntry("quantity", "3"));
        assertThat(result.rows()).anySatisfy(row -> assertThat(row.excelData()).containsEntry("quantity", "2"));
        assertThat(result.rows()).allSatisfy(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .doesNotContain("DUPLICATE_CONFLICT"));
    }

    @Test
    void blocksDuplicateQuantityWhenTheAggregateExceedsNumeric19Scale3() throws Exception {
        var merge = ProductExcelImportPreviewService.class.getDeclaredMethod("mergeDuplicates", List.class, Map.class);
        merge.setAccessible(true);
        List<ProductExcelImportPreviewService.PreviewRow> sourceRows = java.util.stream.IntStream.range(0, 1_111)
                .mapToObj(index -> new ProductExcelImportPreviewService.PreviewRow(index + 2, List.of(index + 2),
                        "MISSING", Map.of("code", "NUEVO", "name", "Producto", "quantity", "9007199254740"),
                        null, null, Map.of(), List.of()))
                .toList();

        @SuppressWarnings("unchecked")
        List<ProductExcelImportPreviewService.PreviewRow> result =
                (List<ProductExcelImportPreviewService.PreviewRow>) merge.invoke(service, sourceRows,
                        Map.of("quantity", "C"));

        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.rowNumbers()).hasSize(1_111).startsWith(2, 3).endsWith(1_112);
            assertThat(row.classification()).isEqualTo("ERROR");
            assertThat(row.excelData()).containsEntry("quantity", "10006998372016140");
            assertThat(row.errors()).singleElement().satisfies(error -> {
                assertThat(error.code()).isEqualTo("NUMBER_PRECISION_INVALID");
                assertThat(error.row()).isEqualTo(2);
                assertThat(error.column()).isEqualTo(3);
                assertThat(error.attribute()).isEqualTo("quantity");
                assertThat(error.receivedValue()).isEqualTo("10006998372016140");
            });
        });
    }

    @Test
    void validatesMappingEditsDatesModesBooleansAndTax() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Fecha"), c("Modo"), c("Descuento"), c("IVA"), c("Oferta"), c("Tipo")),
                List.of(c("A"), c("31-02-2026"), c("9"), c("2"), c("99"), c("2"), c("BAD"))));
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "offerFrom", "B", "priceUseMode", "C", "discountType", "D", "taxId", "E", "offerActive", "F", "productType", "G"),
                List.of(new ProductExcelImportPreviewService.CellEdit(2, "B", "01-01-2026")),
                options(), storeId, companyId, null, 2, null, Map.of()));
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.excelData()).containsEntry("offerFrom", "2026-01-01");
            assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                    .contains("INVALID_PRICE_MODE", "INVALID_BOOLEAN", "PRODUCT_TYPE_INVALID", "TAX_UNKNOWN");
        });

        var invalidMapping = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", ""), List.of(), options(), storeId, companyId, null, 2, null, Map.of()));
        assertThat(invalidMapping.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("IDENTITY_MAPPING_REQUIRED");
    }

    @Test
    void enforcesRequiredQuantityAndDetectedRowLimit() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A"))));
        var options = new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "WAREHOUSE_INPUT", storeId, companyId, false, true);
        var missing = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A"), List.of(), options, storeId, companyId, null, 2, null, Map.of()));
        assertThat(missing.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("QUANTITY_REQUIRED"));

        @SuppressWarnings("unchecked")
        List<ProductExcelImportReadService.CellView>[] acceptedRows = new List[5_001];
        acceptedRows[0] = List.of(c("Codigo"));
        for (int index = 1; index < acceptedRows.length; index++) acceptedRows[index] = List.of(c("OK" + index));
        when(reader.read(any())).thenReturn(read(acceptedRows));
        var accepted = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A"), List.of(), options(), storeId, companyId, null, 2, null, Map.of()));
        assertThat(accepted.detectedRows()).isEqualTo(5_000);
        assertThat(accepted.errors()).extracting(ProductExcelImportPreviewService.ImportError::code).doesNotContain("ROW_LIMIT");

        @SuppressWarnings("unchecked")
        List<ProductExcelImportReadService.CellView>[] rows = new List[5_002];
        rows[0] = List.of(c("Codigo"));
        for (int index = 1; index < rows.length; index++) rows[index] = List.of(c("A" + index));
        when(reader.read(any())).thenReturn(read(rows));
        var limited = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A"), List.of(), options(), storeId, companyId, null, 2, null, Map.of()));
        assertThat(limited.errors()).extracting(ProductExcelImportPreviewService.ImportError::code).contains("ROW_LIMIT");
    }

    @Test
    void appliesStoreDefaultsForMissingProductRequiredValues() {
        UUID defaultFamilyId = UUID.randomUUID();
        UUID defaultTaxId = UUID.randomUUID();
        var family = mock(Family.class);
        when(family.getId()).thenReturn(defaultFamilyId);
        when(family.isDefaultFamily()).thenReturn(true);
        var tax = mock(com.tpverp.backend.catalog.StoreTax.class);
        when(tax.getId()).thenReturn(defaultTaxId);
        when(tax.getStoreId()).thenReturn(storeId);
        when(tax.isActive()).thenReturn(true);
        when(tax.isDefaultTax()).thenReturn(true);
        when(taxes.findByStoreIdOrderByPorcentaje(storeId)).thenReturn(List.of(tax));
        var familyRepository = mock(FamilyRepository.class);
        when(familyRepository.findByStoreIdOrderByFamilyCodeAscIdAsc(storeId)).thenReturn(List.of(family));
        var defaultsService = new ProductExcelImportPreviewService(reader, organization, identifiers, products, taxes,
                null, familyRepository, mock(SubfamilyRepository.class));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Nombre")), List.of(c("A1"), c("Nuevo"))));

        var result = defaultsService.preview(null, request(Map.of("code", "A", "name", "B"), 2, false));

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.classification()).isEqualTo("MISSING");
            assertThat(row.excelData()).containsEntry("taxId", defaultTaxId.toString())
                    .containsEntry("familyId", defaultFamilyId.toString());
            assertThat(row.errors()).isEmpty();
            assertThat(row.concurrencyToken()).isNotBlank();
        });
    }

    @Test
    void normalizesIdentityCaseWithoutRemovingDiacritics() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c(" Ñ1 ")), List.of(c("n1"))));
        service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A"), List.of(), options(), storeId, companyId, null, 2, null, Map.of()));
        verify(identifiers).findAllByStoreIdAndValorLowerIn(any(), argThat(values -> values.contains("ñ1") && values.contains("n1") && values.size() == 2));
    }

    @Test
    void respectsUpdateFieldsAndSkipsZeroMasterPricesWhileReportingNullBeforeValue() {
        UUID productId = UUID.randomUUID();
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(2L);
        when(product.getCode()).thenReturn("A1");
        when(product.getName()).thenReturn("Existente");
        when(product.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(product.getPurchasePrice()).thenReturn(new BigDecimal("5.00"));
        when(product.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(product.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(
                List.of(new ProductIdentifier(storeId, productId, com.tpverp.backend.catalog.IdentifierType.CODIGO, "A1")));
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Compra"), c("Descripcion")), List.of(c("A1"), c("0"), c("Nuevo texto"))));
        var options = new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "WAREHOUSE_INPUT", storeId, companyId, true, false);

        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "purchasePrice", "B", "description", "C"), List.of(), options,
                storeId, companyId, null, 2, null, Map.of("purchasePrice", true, "description", true)));

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.excelData()).containsEntry("purchasePrice", "0");
            assertThat(row.changes()).doesNotContainKey("purchasePrice");
            assertThat(row.changes()).containsKey("description");
            assertThat(row.changes().get("description")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("before", null).containsEntry("after", "Nuevo texto");
        });
    }

    @Test
    void validatesQuantityDateRangeBooleanAndResultingDiscountMode() {
        UUID productId = UUID.randomUUID();
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(1L);
        when(product.getCode()).thenReturn("A1");
        when(product.getName()).thenReturn("Existente");
        when(product.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(product.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(product.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(
                List.of(new ProductIdentifier(storeId, productId, com.tpverp.backend.catalog.IdentifierType.CODIGO, "A1")));
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(reader.read(any())).thenReturn(read(
                List.of(c("Codigo"), c("Cantidad"), c("Desde"), c("Hasta"), c("Modo"), c("Prohibido"), c("Activa")),
                List.of(c("A1"), c("0"), c("31-12-AAAA"), c("30-12-2026"), c("2"), c("1"), c("2"))));
        var invalid = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "quantity", "B", "offerFrom", "C", "offerUntil", "D", "priceUseMode", "E",
                        "discountType", "F", "offerActive", "G"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "WAREHOUSE_INPUT", storeId, companyId, false, true),
                storeId, companyId, null, 2, null, Map.of("offerFrom", true, "offerUntil", true,
                        "priceUseMode", true, "discountType", true, "offerActive", true)));
        assertThat(invalid.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("NUMBER_INVALID", "DATE_INVALID", "INVALID_BOOLEAN", "DISCOUNT_PROHIBITED_PRICE_MODE"));

        when(reader.read(any())).thenReturn(read(
                List.of(c("Codigo"), c("Cantidad"), c("Desde"), c("Hasta"), c("Modo"), c("Prohibido"), c("Activa")),
                List.of(c("A1"), c("1,5"), c("01-01-2026"), c("31-12-2026"), c("4"), c("0"), c("1"))));
        var valid = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "quantity", "B", "offerFrom", "C", "offerUntil", "D", "priceUseMode", "E",
                        "discountType", "F", "offerActive", "G"), List.of(), options(),
                storeId, companyId, null, 2, null, Map.of()));
        assertThat(valid.rows()).singleElement().satisfies(row -> {
            assertThat(row.excelData()).containsEntry("quantity", "1.5").containsEntry("priceUseMode", "OFFER_DISCOUNT");
            assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                    .doesNotContain("NUMBER_INVALID", "DATE_INVALID", "DATE_RANGE_INVALID", "INVALID_BOOLEAN", "DISCOUNT_PROHIBITED_PRICE_MODE");
        });
    }

    @Test
    void discountConflictUsesOnlyFieldsThatWillBeAppliedToExistingProduct() {
        UUID productId = UUID.randomUUID();
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(1L);
        when(product.getCode()).thenReturn("A1");
        when(product.getName()).thenReturn("Existente");
        when(product.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(product.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(product.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(
                List.of(new ProductIdentifier(storeId, productId, com.tpverp.backend.catalog.IdentifierType.CODIGO, "A1")));
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Modo"), c("Prohibido")), List.of(c("A1"), c("2"), c("1"))));
        var mapping = Map.of("code", "A", "priceUseMode", "B", "discountType", "C");
        var ignored = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(),
                options(), storeId, companyId, null, 2, null,
                Map.of("priceUseMode", false, "discountType", false)));
        assertThat(ignored.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code)
                .doesNotContain("DISCOUNT_PROHIBITED_PRICE_MODE"));

        var applied = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(),
                options(), storeId, companyId, null, 2, null,
                Map.of("priceUseMode", true, "discountType", true)));
        assertThat(applied.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("DISCOUNT_PROHIBITED_PRICE_MODE"));
    }

    @Test
    void prohibitedDiscountRejectsPersistedOrImportedMemberAndOfferPrices() {
        UUID productId = UUID.randomUUID();
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(1L);
        when(product.getCode()).thenReturn("A1");
        when(product.getName()).thenReturn("Existente");
        when(product.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(product.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(product.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        when(product.getMemberPrice()).thenReturn(new BigDecimal("8.50"));
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(
                List.of(new ProductIdentifier(storeId, productId,
                        com.tpverp.backend.catalog.IdentifierType.CODIGO, "A1")));
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(reader.read(any())).thenReturn(read(
                List.of(c("Codigo"), c("Prohibido"), c("Oferta")),
                List.of(c("A1"), c("1"), c("7.00"))));

        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "discountType", "B", "offerPrice", "C"), List.of(), options(),
                storeId, companyId, null, 2, null, Map.of("discountType", true, "offerPrice", true)));

        assertThat(result.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .anySatisfy(error -> {
                    assertThat(error.code()).isEqualTo("DISCOUNT_PROHIBITED_PRICE_MODE");
                    assertThat(error.receivedValue()).contains("memberPrice", "offerPrice");
                }));
    }

    @Test
    void resolvesOperationalFamilyCodeWithinStoreScope() {
        UUID familyId = UUID.randomUUID();
        UUID taxId = UUID.randomUUID();
        var family = mock(Family.class);
        when(family.getId()).thenReturn(familyId);
        when(family.getFamilyCode()).thenReturn("123");
        var tax = mock(com.tpverp.backend.catalog.StoreTax.class);
        when(tax.getId()).thenReturn(taxId);
        when(tax.getStoreId()).thenReturn(storeId);
        when(tax.isActive()).thenReturn(true);
        when(tax.isDefaultTax()).thenReturn(true);
        when(taxes.findByStoreIdOrderByPorcentaje(storeId)).thenReturn(List.of(tax));
        var familyRepository = mock(FamilyRepository.class);
        when(familyRepository.findByStoreIdOrderByFamilyCodeAscIdAsc(storeId)).thenReturn(List.of(family));
        var scopedService = new ProductExcelImportPreviewService(reader, organization, identifiers, products, taxes,
                null, familyRepository, mock(SubfamilyRepository.class));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Nombre"), c("Familia")), List.of(c("NUEVO"), c("Alta"), c("123"))));

        var result = scopedService.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "name", "B", "familyId", "C"), List.of(), options(),
                storeId, companyId, null, 2, null, Map.of()));

        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.classification()).isEqualTo("MISSING");
            assertThat(row.excelData()).containsEntry("familyId", familyId.toString());
            assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                    .doesNotContain("FAMILY_UNKNOWN", "FAMILY_AMBIGUOUS");
        });
    }

    @Test
    void rejectsInvalidContractSourcesAndContextBeforeClassification() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A1"))));
        var options = new ProductExcelImportPreviewService.PreviewOptions(Map.of(),
                Map.of("taxId", new ProductExcelImportPreviewService.ValueSource("manual", "21")), false,
                "UNSUPPORTED", storeId, companyId, false, false);
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A"), List.of(), options, storeId, companyId, null, 2, null,
                Map.of("quantity", true)));
        assertThat(result.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("CONTEXT_INVALID", "VALUE_SOURCE_INVALID", "UPDATE_FIELD_UNKNOWN");
    }

    @Test
    void showOnlyImportedSanitizationPreservesPurchasePriceChangedStatus() {
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING",
                Map.of("purchasePrice", "2"), Map.of("name", "SECRET_DB"), 4L,
                Map.of("purchasePrice", Map.of("before", "1", "after", "2")), List.of(), true, "token");
        var sanitized = row.withoutDatabase();
        assertThat(sanitized.databaseData()).isNull();
        assertThat(sanitized.changes()).isEmpty();
        assertThat(sanitized.purchasePriceChanged()).isTrue();
        assertThat(sanitized.concurrencyToken()).isEqualTo("token");
    }

    @Test
    void rejectsEditsThatExceedLimitsAndReportsNoRowsDetected() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c(""))));
        var noRows = service.preview(null, request(Map.of("code", "A"), 2, false));
        assertThat(noRows.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .containsExactly("NO_ROWS_DETECTED");

        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A"))));
        var tooLong = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A"), List.of(new ProductExcelImportPreviewService.CellEdit(2, "A",
                        "x".repeat(ProductExcelImportPreviewService.MAX_EDIT_VALUE_CHARACTERS + 1))),
                options(), storeId, companyId, null, 2, null, Map.of()));
        assertThat(tooLong.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .containsExactly("EDIT_VALUE_LIMIT");
    }

    @Test
    void capsRowErrorDetailsAcrossTheWholePreviewWithoutChangingErrorClassification() {
        var rows = new java.util.ArrayList<List<ProductExcelImportReadService.CellView>>();
        rows.add(List.of(c("Codigo"), c("Nombre"), c("Modo"), c("Descuento")));
        for (int index = 0; index < ProductExcelImportReadService.MAX_ROWS; index++) {
            rows.add(List.of(c("CODE-" + index), c("Producto " + index), c("invalid-mode"), c("invalid-boolean")));
        }
        when(reader.read(any())).thenReturn(new ProductExcelImportReadService.ReadResult(
                "errors.xlsx", "a".repeat(64), "Hoja", rows, List.of(), rows.size(), 4, rows.size() * 4));

        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "name", "B", "priceUseMode", "C", "discountType", "D"),
                List.of(), options(), storeId, companyId, null, 2, null, Map.of()));

        assertThat(result.rows()).hasSize(ProductExcelImportReadService.MAX_ROWS)
                .allSatisfy(row -> assertThat(row.classification()).isEqualTo("ERROR"));
        assertThat(result.rows().stream().mapToInt(row -> row.errors().size()).sum())
                .isEqualTo(5_000);
        assertThat(result.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .containsExactly("ERROR_LIMIT");
        assertThat(result.errors()).singleElement().satisfies(error ->
                assertThat(error.receivedValue()).isEqualTo("15000"));
        assertThat(result.rows().stream().filter(row -> row.errors().isEmpty())).isNotEmpty();
    }

    @Test
    void parsesFormattedEuroAndPercentValuesWithoutChangingIdentityText() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Precio"), c("IVA")),
                List.of(c("0007"), c("1,38 €"), c("20 %"))));
        var tax = mock(com.tpverp.backend.catalog.StoreTax.class);
        when(tax.getId()).thenReturn(UUID.randomUUID());
        when(tax.getStoreId()).thenReturn(storeId);
        when(tax.isActive()).thenReturn(true);
        when(tax.getPercentage()).thenReturn(new BigDecimal("20"));
        when(taxes.findByStoreIdOrderByPorcentaje(storeId)).thenReturn(List.of(tax));
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "purchasePrice", "B", "taxId", "C"), List.of(), options(),
                storeId, companyId, null, 2, null, Map.of()));
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.excelData()).containsEntry("code", "0007")
                    .containsEntry("purchasePrice", "1.38")
                    .containsEntry("taxId", tax.getId().toString());
        });
    }

    @Test
    void rejectsAmbiguousQuantitiesButTreatsSingleSeparatorPricesAsDecimals() {
        when(reader.read(any())).thenReturn(read(
                List.of(c("Codigo"), c("Cantidad"), c("Precio"), c("Venta"), c("Precio US")),
                List.of(c("001.234"), c("1,234"), c("1.234,56 €"), c("$1,234.56"), c("1,234"))));
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "quantity", "B", "purchasePrice", "C", "salePrice", "D", "memberPrice", "E"),
                List.of(), options(), storeId, companyId, null, 2, null, Map.of()));
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.excelData()).containsEntry("code", "001.234")
                    .containsEntry("purchasePrice", "1234.56")
                    .containsEntry("salePrice", "1234.56");
            assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                    .contains("NUMBER_FORMAT_AMBIGUOUS");
        });
    }

    @Test
    void numericParserKeepsGroupingAndResolvesSingleSeparatorPricesAsDecimals() throws Exception {
        var parser = ProductExcelImportPreviewService.class.getDeclaredMethod(
                "parseDecimal", String.class, String.class, int.class, Integer.class, List.class);
        parser.setAccessible(true);
        for (String field : List.of("quantity", "stockMin", "purchaseDiscountPercent")) {
            List<ProductExcelImportPreviewService.ImportError> errors = new java.util.ArrayList<>();
            assertThat(parser.invoke(null, "1,234", field, 2, 3, errors)).isNull();
            assertThat(errors).extracting(ProductExcelImportPreviewService.ImportError::code)
                    .containsExactly("NUMBER_FORMAT_AMBIGUOUS");
        }
        List<ProductExcelImportPreviewService.ImportError> errors = new java.util.ArrayList<>();
        assertThat(parser.invoke(null, "1,234,567", "purchasePrice", 2, 3, errors).toString())
                .isEqualTo("1234567");
        assertThat(errors).isEmpty();
    }

    @Test
    void allPricesAcceptThreeDecimalsFromTextOrNumericCellsButRejectFour() throws Exception {
        var parser = ProductExcelImportPreviewService.class.getDeclaredMethod(
                "parseDecimal", String.class, String.class, int.class, Integer.class, List.class, boolean.class);
        parser.setAccessible(true);
        for (String field : List.of("purchasePrice", "salePrice", "memberPrice", "wholesalePrice", "offerPrice")) {
            for (boolean numeric : List.of(false, true)) {
                for (String text : List.of("1.234", "1,234", "1.234,567", "1,234.567")) {
                    List<ProductExcelImportPreviewService.ImportError> errors = new java.util.ArrayList<>();
                    assertThat((BigDecimal) parser.invoke(null, text, field, 2, 3, errors, numeric))
                            .isEqualByComparingTo(text.length() == 5 ? "1.234" : "1234.567");
                    assertThat(errors).isEmpty();
                }
                List<ProductExcelImportPreviewService.ImportError> errors = new java.util.ArrayList<>();
                assertThat(parser.invoke(null, "2.2081", field, 2, 3, errors, numeric)).isNull();
                assertThat(errors).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .containsExactly("NUMBER_SCALE_INVALID");
            }
        }
    }

    @Test
    void resolvesNumericTaxFromRawValueWithoutRoundingAndKeepsUuidDisplay() {
        var taxId = UUID.randomUUID();
        var tax = mock(com.tpverp.backend.catalog.StoreTax.class);
        when(tax.getId()).thenReturn(taxId);
        when(tax.getStoreId()).thenReturn(storeId);
        when(tax.isActive()).thenReturn(true);
        when(tax.getPercentage()).thenReturn(new BigDecimal("20"));
        when(taxes.findByStoreIdOrderByPorcentaje(storeId)).thenReturn(List.of(tax));
        var mapping = Map.of("code", "A", "name", "B", "taxId", "C");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Nombre"), c("IVA")),
                List.of(c("P1"), c("Producto"), new ProductExcelImportReadService.CellView(
                        "20.0000000001", null, "20.0000000001", false))));
        var notRounded = service.preview(null, request(mapping, 2, false));
        assertThat(notRounded.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("TAX_UNKNOWN"));

        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Nombre"), c("IVA")),
                List.of(c("P2"), c("Producto"), new ProductExcelImportReadService.CellView(
                        "20%", null, "20", true))));
        var percent = service.preview(null, request(mapping, 2, false));
        assertThat(percent.rows()).singleElement().satisfies(row ->
                assertThat(row.excelData()).containsEntry("taxId", taxId.toString()));
    }

    @Test
    void onlyDiscountsAndTaxMayUseNumericPercentageProvenance() {
        var mapping = Map.of("code", "A", "name", "B", "purchasePrice", "C", "quantity", "D");
        var percentage = new ProductExcelImportReadService.CellView("0.10%", null, "0.1", true, true);
        when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Nombre"), c("Precio"), c("Cantidad")),
                List.of(c("P1"), c("Producto"), percentage, percentage)));
        var result = service.preview(null, request(mapping, 2, false));
        assertThat(result.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("PERCENTAGE_FORMAT_NOT_ALLOWED"));

        var discountMapping = Map.of("code", "A", "name", "B", "purchaseDiscountPercent", "C");
        when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Nombre"), c("Descuento")),
                List.of(c("P2"), c("Producto"), new ProductExcelImportReadService.CellView("10%", null, "10", true, true))));
        var discount = service.preview(null, request(discountMapping, 2, false));
        assertThat(discount.rows()).singleElement().satisfies(row -> {
            assertThat(row.excelData()).containsEntry("purchaseDiscountPercent", "10");
            assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                    .doesNotContain("NUMBER_FORMAT_UNSUPPORTED");
        });

        when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Nombre"), c("Precio")),
                List.of(c("P3"), c("Producto"), c("10%"))));
        var textualPercentage = service.preview(null,
                request(Map.of("code", "A", "name", "B", "salePrice", "C"), 2, false));
        assertThat(textualPercentage.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("PERCENTAGE_FORMAT_NOT_ALLOWED"));
    }

    @Test
    void rejectsDecimalsThatCannotRoundTripThroughJavascriptWithoutChangingValue() {
        when(reader.read(any())).thenReturn(read(
                List.of(c("Código"), c("Nombre"), c("Precio"), c("Stock"), c("Cantidad")),
                List.of(c("P1"), c("Precio inseguro"), c("9007199254740993.01"), c(""), c("0.001")),
                List.of(c("P2"), c("Stock inseguro"), c(""), c("9007199254740993"), c("0.001")),
                List.of(c("P3"), c("Cantidad segura"), c("0.01"), c("1.001"), c("0.001"))));
        var mapping = Map.of("code", "A", "name", "B", "purchasePrice", "C", "stockMax", "D", "quantity", "E");

        var result = service.preview(null, request(mapping, 2, false));

        assertThat(result.rows()).hasSize(3);
        assertThat(result.rows().get(0).errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("NUMBER_PRECISION_INVALID");
        assertThat(result.rows().get(1).errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("NUMBER_PRECISION_INVALID");
        assertThat(result.rows().get(2).errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .doesNotContain("NUMBER_PRECISION_INVALID");
        assertThat(result.rows().get(2).excelData()).containsEntry("quantity", "0.001");
    }

    @Test
    void validatesNumericSelectorsReferencesTypesAndDatesBeforeNormalization() {
        var mapping = Map.of("code", "A", "name", "B", "priceUseMode", "C", "offerActive", "D",
                "familyId", "E", "productType", "F", "offerFrom", "G");
        when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Nombre"), c("Modo"), c("Oferta"), c("Familia"), c("Tipo"), c("Desde")),
                List.of(c("P1"), c("Producto"),
                        new ProductExcelImportReadService.CellView("1", null, "1.0001", false, true),
                        new ProductExcelImportReadService.CellView("1", null, "1.0001", false, true),
                        new ProductExcelImportReadService.CellView("1%", null, "1", true, true),
                        new ProductExcelImportReadService.CellView("UNIT", null, "1", false, true),
                        new ProductExcelImportReadService.CellView("2026-01-01", null, "2026", false, true))));
        var result = service.preview(null, request(mapping, 2, false));
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                    .contains("INVALID_PRICE_MODE", "INVALID_BOOLEAN", "PERCENTAGE_FORMAT_NOT_ALLOWED",
                            "PRODUCT_TYPE_INVALID", "DATE_INVALID");
        });

        when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Nombre"), c("Familia")),
                List.of(c("P2"), c("Producto"), new ProductExcelImportReadService.CellView("1.2", null, "1.2", false, true))));
        var fraction = service.preview(null, request(Map.of("code", "A", "name", "B", "familyId", "C"), 2, false));
        assertThat(fraction.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("FAMILY_UNKNOWN"));
    }

    @Test
    void rejectsUnsafeNumericIdentityDisplayAndAcceptsCleanFifteenDigitTextOrNumber() {
        var mapping = Map.of("code", "A", "name", "B");
        for (var unsafe : List.of(
                new ProductExcelImportReadService.CellView("-123", null, "-123", false, true),
                new ProductExcelImportReadService.CellView("1,234", null, "1234", false, true),
                new ProductExcelImportReadService.CellView("123.5", null, "123.5", false, true),
                new ProductExcelImportReadService.CellView("20%", null, "20", true, true))) {
            when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Nombre")), List.of(unsafe, c("Producto"))));
            var result = service.preview(null, request(mapping, 2, false));
            assertThat(result.rows()).singleElement().satisfies(row ->
                    assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                            .contains("IDENTIFIER_NUMERIC_PRECISION"));
        }

        when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Nombre")), List.of(
                new ProductExcelImportReadService.CellView("123456789012345", null, "123456789012345", false, true), c("Producto"))));
        var numericSafe = service.preview(null, request(mapping, 2, false));
        assertThat(numericSafe.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .doesNotContain("IDENTIFIER_NUMERIC_PRECISION"));

        when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Nombre")), List.of(c("000000000000015"), c("Producto"))));
        var textSafe = service.preview(null, request(mapping, 2, false));
        assertThat(textSafe.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .doesNotContain("IDENTIFIER_NUMERIC_PRECISION"));
    }

    @Test
    void rejectsUnsafeNumericIdentifiersButPreservesTextIdentifiers() {
        var mapping = Map.of("code", "A", "name", "B");
        when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Nombre")), List.of(
                new ProductExcelImportReadService.CellView("123456789012345", null, "123456789012345", false),
                c("Producto"))));
        var safe = service.preview(null, request(mapping, 2, false));
        assertThat(safe.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code)
                .doesNotContain("IDENTIFIER_NUMERIC_PRECISION"));

        for (ProductExcelImportReadService.CellView unsafe : List.of(
                new ProductExcelImportReadService.CellView("1234567890123456", null, "1234567890123456", false),
                new ProductExcelImportReadService.CellView("123456789012345.5", null, "123456789012345.5", false),
                new ProductExcelImportReadService.CellView("20%", null, "20", true),
                new ProductExcelImportReadService.CellView("31-12-2026", null, null, false, true))) {
            when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Nombre")), List.of(unsafe, c("Producto"))));
            var result = service.preview(null, request(mapping, 2, false));
            assertThat(result.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                    .extracting(ProductExcelImportPreviewService.ImportError::code)
                    .contains("IDENTIFIER_NUMERIC_PRECISION"));
        }

        String textCode = "0000000000000000-ABC";
        when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Nombre")), List.of(c(textCode), c("Producto"))));
        var text = service.preview(null, request(mapping, 2, false));
        assertThat(text.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code)
                .doesNotContain("IDENTIFIER_NUMERIC_PRECISION", "FIELD_LENGTH_INVALID"));

        when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Nombre"), c("Referencia")), List.of(
                c("SAFE"), c("Producto"),
                new ProductExcelImportReadService.CellView("1234567890123456", null, "1234567890123456", false))));
        var explicitReference = service.preview(null,
                request(Map.of("code", "A", "name", "B", "supplierReference", "C"), 2, false));
        assertThat(explicitReference.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .anySatisfy(error -> {
                    assertThat(error.code()).isEqualTo("IDENTIFIER_NUMERIC_PRECISION");
                    assertThat(error.attribute()).isEqualTo("supplierReference");
                }));
    }

    @Test
    void rejectsImportTextLengthsWithFieldSpecificLimits() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Nombre"), c("Referencia")),
                List.of(c("x".repeat(129)), c("n".repeat(256)), c("r".repeat(129)))));
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "name", "B", "supplierReference", "C"), List.of(), options(),
                storeId, companyId, null, 2, null, Map.of()));
        assertThat(result.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("FIELD_LENGTH_INVALID", "FIELD_LENGTH_INVALID", "FIELD_LENGTH_INVALID"));
    }

    @Test
    void validatesStockRangeAgainstResultingExistingValues() {
        UUID productId = UUID.randomUUID();
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(2L);
        when(product.getCode()).thenReturn("A1");
        when(product.getName()).thenReturn("Producto");
        when(product.getStockMin()).thenReturn(new BigDecimal("5"));
        when(product.getStockMax()).thenReturn(new BigDecimal("10"));
        when(product.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(product.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        when(product.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(
                List.of(new ProductIdentifier(storeId, productId, com.tpverp.backend.catalog.IdentifierType.CODIGO, "A1")));
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(taxes.findByStoreIdOrderByPorcentaje(storeId)).thenReturn(List.of());
        var mapping = Map.of("code", "A", "stockMin", "B", "stockMax", "C");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Min"), c("Max")),
                List.of(c("A1"), c("6"), c("4"))));
        var invalid = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(), options(),
                storeId, companyId, null, 2, null, Map.of("stockMin", true, "stockMax", true)));
        assertThat(invalid.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code).contains("STOCK_RANGE_INVALID"));

        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Min"), c("Max")),
                List.of(c("A1"), c("99"), c("1"))));
        var ignored = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(), options(),
                storeId, companyId, null, 2, null, Map.of("stockMin", false, "stockMax", false)));
        assertThat(ignored.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code).doesNotContain("STOCK_RANGE_INVALID"));
    }

    @Test
    void validatesAndCanonicalizesWarehouseDocumentPricesEvenWhenMasterUpdatesAreDisabled() {
        UUID productId = UUID.randomUUID();
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(2L);
        when(product.getCode()).thenReturn("A1");
        when(product.getName()).thenReturn("Producto");
        when(product.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(product.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(product.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(
                List.of(new ProductIdentifier(storeId, productId, com.tpverp.backend.catalog.IdentifierType.CODIGO, "A1")));
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        var mapping = Map.of("code", "A", "purchasePrice", "B", "purchaseDiscountPercent", "C", "salePrice", "D");
        var documentOptions = new ProductExcelImportPreviewService.PreviewOptions(
                Map.of(), Map.of(), false, "WAREHOUSE_INPUT", storeId, companyId, false, false, "salePrice");
        var noMasterPrices = Map.of("purchasePrice", false, "purchaseDiscountPercent", false, "salePrice", false);

        when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Compra"), c("Descuento"), c("Venta")),
                List.of(c("A1"), c("1.234,56"), c("10,5"), c("2.345,67"))));
        var valid = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                mapping, List.of(), documentOptions, storeId, companyId, null, 2, null, noMasterPrices));
        assertThat(valid.rows()).singleElement().satisfies(row -> {
            assertThat(row.classification()).isEqualTo("EXISTING");
            assertThat(row.excelData()).containsEntry("purchasePrice", "1234.56")
                    .containsEntry("purchaseDiscountPercent", "10.5")
                    .containsEntry("salePrice", "2345.67");
        });

        when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Compra"), c("Descuento"), c("Venta")),
                List.of(c("A1"), c("1"), c("100,01"), c("importe"))));
        var invalid = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                mapping, List.of(), documentOptions, storeId, companyId, null, 2, null, noMasterPrices));
        assertThat(invalid.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code)
                .containsExactlyInAnyOrder("NUMBER_INVALID", "NUMBER_INVALID"));

        var memberPriceMapping = Map.of("code", "A", "memberPrice", "B");
        var memberPriceDocumentOptions = new ProductExcelImportPreviewService.PreviewOptions(
                Map.of(), Map.of(), false, "WAREHOUSE_INPUT", storeId, companyId, false, false, "memberPrice");
        when(reader.read(any())).thenReturn(read(List.of(c("Código"), c("Miembro")),
                List.of(c("A1"), c("0"))));
        var explicitDocumentZero = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                memberPriceMapping, List.of(), memberPriceDocumentOptions, storeId, companyId, null, 2, null,
                Map.of("memberPrice", false)));
        assertThat(explicitDocumentZero.rows()).singleElement().satisfies(row -> {
            assertThat(row.classification()).isEqualTo("EXISTING");
            assertThat(row.excelData()).containsEntry("memberPrice", "0");
            assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                    .doesNotContain("ZERO_PRICE_INVALID");
        });
    }

    @Test
    void rejectsUnsupportedWarehouseDocumentPriceSource() {
        when(reader.read(any())).thenReturn(read(List.of(c("Código")), List.of(c("A1"))));
        var invalidOptions = new ProductExcelImportPreviewService.PreviewOptions(
                Map.of(), Map.of(), false, "WAREHOUSE_INPUT", storeId, companyId, false, false, "retailPrice");
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A"), List.of(), invalidOptions, storeId, companyId, null, 2, null, Map.of()));

        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("VALUE_SOURCE_INVALID");
    }

    @Test
    void validatesResultingOfferActiveModesBeforeWriter() {
        UUID productId = UUID.randomUUID();
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(product.getVersion()).thenReturn(2L);
        when(product.getCode()).thenReturn("A1");
        when(product.getName()).thenReturn("Producto");
        when(product.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(product.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(product.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        when(product.getSalePrice()).thenReturn(new BigDecimal("10"));
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(
                List.of(new ProductIdentifier(storeId, productId, com.tpverp.backend.catalog.IdentifierType.CODIGO, "A1")));
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(taxes.findByStoreIdOrderByPorcentaje(storeId)).thenReturn(List.of());
        var mapping = Map.of("code", "A", "offerActive", "B", "priceUseMode", "C", "offerPrice", "D",
                "offerDiscountPercent", "E", "offerFrom", "F");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Activa"), c("Modo"), c("Precio"), c("Descuento"), c("Desde")),
                List.of(c("A1"), c("1"), c("1"), c(""), c(""), c(""))));
        var normalMissing = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(), options(),
                storeId, companyId, null, 2, null, Map.of("offerActive", true, "priceUseMode", true)));
        assertThat(normalMissing.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code).contains("OFFER_REQUIRED"));

        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Activa"), c("Modo"), c("Precio"), c("Descuento"), c("Desde")),
                List.of(c("A1"), c("1"), c("4"), c(""), c("20"), c("01-01-2026"))));
        var validDiscount = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(), options(),
                storeId, companyId, null, 2, null, Map.of("offerActive", true, "priceUseMode", true,
                        "offerDiscountPercent", true, "offerFrom", true)));
        assertThat(validDiscount.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code)
                .doesNotContain("OFFER_REQUIRED", "ZERO_PRICE_INVALID"));

        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Activa"), c("Modo"), c("Precio"), c("Descuento"), c("Desde")),
                List.of(c("A1"), c("1"), c("3"), c("8"), c(""), c("01-01-2026"))));
        var validPrice = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(), options(),
                storeId, companyId, null, 2, null, Map.of("offerActive", true, "priceUseMode", true,
                        "offerPrice", true, "offerFrom", true)));
        assertThat(validPrice.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code).doesNotContain("OFFER_REQUIRED"));

        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Activa"), c("Modo"), c("Precio"), c("Descuento"), c("Desde")),
                List.of(c("A1"), c("1"), c("4"), c(""), c("100"), c("01-01-2026"))));
        var zeroDiscount = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(), options(),
                storeId, companyId, null, 2, null, Map.of("offerActive", true, "priceUseMode", true,
                        "offerDiscountPercent", true, "offerFrom", true)));
        assertThat(zeroDiscount.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code).contains("ZERO_PRICE_INVALID"));

        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Activa"), c("Modo"), c("Precio"), c("Descuento"), c("Desde")),
                List.of(c("A1"), c("1"), c("3"), c("0"), c(""), c("01-01-2026"))));
        var skipZeroWithoutStoredOffer = new ProductExcelImportPreviewService.PreviewOptions(
                Map.of(), Map.of(), false, "WAREHOUSE_INPUT", storeId, companyId, true, false);
        var ignoredZero = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(),
                skipZeroWithoutStoredOffer, storeId, companyId, null, 2, null,
                Map.of("offerActive", true, "priceUseMode", true, "offerPrice", true, "offerFrom", true)));
        assertThat(ignoredZero.rows()).singleElement().satisfies(row -> assertThat(row.errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code).contains("OFFER_REQUIRED"));
    }

    @Test
    void validatesThousandsGroupingWithoutChangingNumericFieldSemantics() {
        when(reader.read(any())).thenReturn(read(
                List.of(c("Codigo"), c("ES"), c("EN"), c("Mal"), c("Ambiguo")),
                List.of(c("001.234"), c("1.234.567,89"), c("$1,234,567.89"), c("1,2,3.45"), c("1,234"))));
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "purchasePrice", "B", "salePrice", "C", "memberPrice", "D", "wholesalePrice", "E"),
                List.of(), options(), storeId, companyId, null, 2, null, Map.of()));
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.excelData()).containsEntry("code", "001.234")
                    .containsEntry("purchasePrice", "1234567.89")
                    .containsEntry("salePrice", "1234567.89");
            assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                    .contains("NUMBER_INVALID").doesNotContain("NUMBER_FORMAT_AMBIGUOUS");
            assertThat(row.excelData()).containsEntry("wholesalePrice", "1.234");
        });
    }

    @Test
    void rejectsBarcode2EqualToPrimaryIdentifierForNewAndExistingRows() {
        when(reader.read(any())).thenReturn(read(
                List.of(c("Codigo"), c("Barras2"), c("Nombre")),
                List.of(c("NUEVO"), c("NUEVO"), c("Alta"))));
        var newResult = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "barcode2", "B", "name", "C"), List.of(), options(),
                storeId, companyId, null, 2, null, Map.of()));
        assertThat(newResult.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("IDENTIFIER_DUPLICATE"));

        UUID productId = UUID.randomUUID();
        Product existing = mock(Product.class);
        when(existing.getId()).thenReturn(productId);
        when(existing.getVersion()).thenReturn(3L);
        when(existing.getCode()).thenReturn("EXISTENTE");
        when(existing.getName()).thenReturn("Existente");
        when(existing.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(existing.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(existing.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(
                List.of(new ProductIdentifier(storeId, productId, com.tpverp.backend.catalog.IdentifierType.CODIGO,
                        "EXISTENTE")));
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(existing));
        when(reader.read(any())).thenReturn(read(
                List.of(c("Codigo"), c("Barras2"), c("Nombre")),
                List.of(c("EXISTENTE"), c("EXISTENTE"), c("Cambio"))));
        var existingResult = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "barcode2", "B", "name", "C"), List.of(), options(),
                storeId, companyId, null, 2, null, Map.of("barcode2", true)));
        assertThat(existingResult.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("IDENTIFIER_DUPLICATE"));

        var ignoredExistingResult = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "barcode2", "B", "name", "C"), List.of(), options(),
                storeId, companyId, null, 2, null, Map.of("barcode2", false)));
        assertThat(ignoredExistingResult.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .doesNotContain("IDENTIFIER_DUPLICATE"));
    }

    @Test
    void unicodeWhitespaceIsNotARowAndCanonicalUnicodeResolvesExistingIdentity() {
        existingProduct("CAFÉ");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Nombre")),
                List.of(c("\u00a0\u2007"), c("\u202f")), List.of(c("\u00a0CAFE\u0301\u00a0"), c(""))));
        var result = service.preview(null, request(Map.of("code", "A", "name", "B"), 2, false));
        assertThat(result.detectedRows()).isEqualTo(1);
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.rowNumber()).isEqualTo(3);
            assertThat(row.classification()).isEqualTo("EXISTING");
            assertThat(row.excelData()).containsEntry("code", "CAFÉ");
        });
    }

    @Test
    void checksIdentifierLengthAfterUppercaseExpansion() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("ß".repeat(65)))));
        var result = service.preview(null, request(Map.of("code", "A"), 2, false));
        assertThat(result.rows().get(0).errors()).anySatisfy(error -> {
            assertThat(error.code()).isEqualTo("FIELD_LENGTH_INVALID");
            assertThat(error.attribute()).isEqualTo("code");
        });
    }

    @Test
    void uncheckedOptionalFieldsDoNotBlockButConsumedWarehouseFieldsStillValidate() {
        existingProduct("A1");
        when(reader.read(any())).thenReturn(read(
                List.of(c("Codigo"), c("Nombre"), c("Fecha"), c("Precio"), c("Referencia")),
                List.of(c("A1"), c("n".repeat(256)),
                        new ProductExcelImportReadService.CellView("123", null, "123", false, true),
                        c("20%"), c("R".repeat(129)))));
        Map<String, String> mapping = Map.of("code", "A", "name", "B", "offerFrom", "C",
                "salePrice", "D", "supplierReference", "E");
        var stockOptions = new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false,
                "STOCK", storeId, companyId, false, false);
        var stock = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(),
                stockOptions, storeId, companyId, null, 2, null, Map.of()));
        assertThat(stock.rows().get(0).errors()).isEmpty();
        var warehouseOptions = new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false,
                "WAREHOUSE_INPUT", storeId, companyId, false, false, "salePrice");
        var warehouse = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(),
                warehouseOptions, storeId, companyId, null, 2, null, Map.of()));
        assertThat(warehouse.rows().get(0).errors()).extracting(ProductExcelImportPreviewService.ImportError::attribute)
                .contains("salePrice", "supplierReference").doesNotContain("name", "offerFrom");
    }

    @Test
    void onlyImportedConflictDoesNotLeakPersistedSpecialPricesToJsonOrXlsx() throws Exception {
        Product product = existingProduct("A1");
        when(product.getMemberPrice()).thenReturn(new BigDecimal("87654.32"));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Prohibido")), List.of(c("A1"), c("1"))));
        var request = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A", "discountType", "B"),
                List.of(), new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), true,
                        "WAREHOUSE_INPUT", storeId, companyId, false, false),
                storeId, companyId, null, 2, null, Map.of("discountType", true));
        var result = service.preview(null, request);
        assertThat(result.rows().get(0).errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("DISCOUNT_PROHIBITED_PRICE_MODE");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result)).doesNotContain("87654.32");
        var exported = new ProductExcelImportSummaryService(service).export(null, request);
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
            for (var sheet : workbook) for (var row : sheet) for (var cell : row)
                assertThat(cell.toString()).doesNotContain("87654.32");
        }
    }

    @Test
    void previewShowsOfferActivationForcedByOfferModeEvenWithExplicitZero() {
        Product product = existingProduct("A1");
        when(product.getOfferPrice()).thenReturn(new BigDecimal("5"));
        when(product.getOfferFrom()).thenReturn(java.time.LocalDate.of(2026, 9, 1));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Modo"), c("Activa")),
                List.of(c("A1"), c("3"), c("0"))));
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "priceUseMode", "B", "offerActive", "C"), List.of(), options(),
                storeId, companyId, null, 2, null, Map.of("priceUseMode", true, "offerActive", true)));
        assertThat(result.rows().get(0).errors()).isEmpty();
        assertThat(result.rows().get(0).changes()).containsEntry("offerActive", Map.of("before", "0", "after", "1"));
        assertThat(result.rows().get(0).excelData()).containsEntry("offerActive", "0");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"001,true", "001002,true", "001999,false", "999002,false"})
    void combinedFamilyResolvesBothIdentifiersAndExplicitClear(String code, boolean valid) {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        Product product = existingProduct("A1");
        when(product.getFamilyId()).thenReturn(parentId);
        when(product.getSubfamilyId()).thenReturn(UUID.randomUUID());
        var parent = mock(Family.class);
        when(parent.getId()).thenReturn(parentId);
        when(parent.getFamilyCode()).thenReturn("001");
        var child = mock(com.tpverp.backend.catalog.Subfamily.class);
        when(child.getId()).thenReturn(childId);
        when(child.getFamilyId()).thenReturn(parentId);
        when(child.getSubfamilyCode()).thenReturn("001002");
        var familyRepository = mock(FamilyRepository.class);
        var subfamilyRepository = mock(SubfamilyRepository.class);
        when(familyRepository.findByStoreIdOrderByFamilyCodeAscIdAsc(storeId)).thenReturn(List.of(parent));
        when(subfamilyRepository.findByStoreIdAndSubfamilyCodeIn(any(), any())).thenReturn(List.of(child));
        var scopedService = new ProductExcelImportPreviewService(reader, organization, identifiers, products, taxes,
                null, familyRepository, subfamilyRepository);
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Familia")), List.of(c("A1"), c(code))));
        var request = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A", "familyId", "B"),
                List.of(), options(), storeId, companyId, null, 2, null, Map.of("familyId", true));
        var preview = scopedService.preview(null, request);
        var row = preview.rows().get(0);
        assertThat(preview.sourceRows().get(0).excelData()).containsEntry("familyId", code);
        assertThat(preview.sourceRows().get(0).databaseData()).containsEntry("familyId", "001");
        if (valid) {
            assertThat(row.errors()).isEmpty();
            assertThat(row.excelData()).containsEntry("familyId", parentId.toString())
                    .containsEntry("subfamilyId", code.length() == 3 ? "" : childId.toString())
                    .containsEntry("familyBusinessCode", code);
            assertThat(row.changes()).containsKey("subfamilyId");
            assertThat(row.masterDataChanged()).isTrue();
        } else {
            assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code).contains("FAMILY_UNKNOWN");
        }
        // An unchecked family control cannot change or clear either identifier.
        var unchecked = new ProductExcelImportPreviewService.PreviewRequest(request.mapping(), List.of(), options(),
                storeId, companyId, null, 2, null, Map.of("familyId", false, "subfamilyId", false));
        var unchanged = scopedService.preview(null, unchecked).rows().get(0);
        assertThat(unchanged.errors()).isEmpty();
        assertThat(unchanged.changes()).doesNotContainKeys("familyId", "subfamilyId");
    }

    @Test
    void stockIgnoresLegacyQuantityMappingAndMergesWithoutFictitiousQuantities() {
        existingProduct("A1");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Cantidad"), c("Paquete")),
                List.of(c("A1"), c("not a number"), c("2")), List.of(c("A1"), c("-9"), c("2"))));
        var stock = new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false,
                "STOCK", storeId, companyId, false, true);
        var request = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A", "quantity", "B", "packageQuantity", "C"),
                List.of(), stock, storeId, companyId, null, 2, "B", Map.of("quantity", true, "packageQuantity", true));
        var result = service.preview(null, request);
        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.rowNumbers()).containsExactly(2, 3);
            assertThat(row.excelData()).doesNotContainKey("quantity").containsEntry("packageQuantity", "2");
            assertThat(row.changes()).doesNotContainKey("quantity");
        });
        assertThat(result.sourceRows()).hasSize(2);
        var warehouse = new ProductExcelImportPreviewService.PreviewRequest(request.mapping(), List.of(), options(),
                storeId, companyId, null, 2, "B", Map.of());
        assertThat(service.preview(null, warehouse).rows().stream().flatMap(row -> row.errors().stream()).toList())
                .extracting(ProductExcelImportPreviewService.ImportError::attribute).contains("quantity");
    }

    @Test
    void manualBindingResolvesCreatedProductWithoutRewritingOriginalExcelIdentity() {
        Product product = existingProduct("EDITED-CODE");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Nombre")), List.of(c("ORIGINAL-CODE"), c("Original Excel"))));
        var request = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A", "name", "B"), List.of(), options(),
                storeId, companyId, "a".repeat(64), 2, null, Map.of(), Map.of(2, product.getId()));
        var result = service.preview(null, request);
        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.classification()).isEqualTo("EXISTING");
            assertThat(row.excelData()).containsEntry("code", "ORIGINAL-CODE").containsEntry("name", "Original Excel");
            assertThat(row.databaseData()).containsEntry("code", "EDITED-CODE");
        });
    }

    @Test
    void reportsMappedCellErrorWithCoordinatesAndManualRepairInstructions() {
        existingProduct("A1");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Precio")),
                List.of(c("A1"), new ProductExcelImportReadService.CellView("#VALUE!", "1+2", null, false, false, "FORMULA_RESULT_ERROR"))));
        var result = service.preview(null, request(Map.of("code", "A", "purchasePrice", "B"), 2, false));
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.classification()).isEqualTo("ERROR");
            assertThat(row.excelData()).containsEntry("purchasePrice", "#VALUE!");
            assertThat(row.errors()).filteredOn(error -> error.code().equals("FORMULA_RESULT_ERROR"))
                    .singleElement().satisfies(error -> {
                        assertThat(error.row()).isEqualTo(2);
                        assertThat(error.column()).isEqualTo(2);
                        assertThat(error.attribute()).isEqualTo("purchasePrice");
                        assertThat(error.receivedValue()).isEqualTo("#VALUE!");
                        assertThat(error.recommendedFix()).contains("B2", "manualmente", "Excel", "guarda", "reintentar");
                    });
        });
    }

    @Test
    void doesNotBlockAnUnmappedCellErrorOrIgnoredStockQuantity() {
        existingProduct("A1");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Auxiliar")),
                List.of(c("A1"), new ProductExcelImportReadService.CellView("#VALUE!", "1+2", null, false, false, "FORMULA_RESULT_ERROR"))));
        for (var mapping : List.of(Map.of("code", "A"), Map.of("code", "A", "quantity", "B"))) {
            var config = new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(),
                    new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "STOCK", storeId, companyId, false, false),
                    storeId, companyId, null, 2, "B", Map.of());
            var result = service.preview(null, config);
            assertThat(result.errors()).isEmpty();
            assertThat(result.rows()).singleElement().satisfies(row -> {
                assertThat(row.classification()).isEqualTo("EXISTING");
                assertThat(row.excelData()).doesNotContainKey("quantity");
                assertThat(row.errors()).isEmpty();
            });
        }
    }

    @Test
    void doesNotAcceptErrorValuesAsProductIdentityOrName() {
        for (String field : List.of("code", "name")) {
            when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Dato")),
                    List.of(c("A1"), new ProductExcelImportReadService.CellView("#N/A", null, null, false, false, "CELL_ERROR_VALUE"))));
            var result = service.preview(null, request(field.equals("code") ? Map.of("code", "B") : Map.of("code", "A", "name", "B"), 2, false));
            assertThat(result.rows()).singleElement().satisfies(row -> {
                assertThat(row.classification()).isEqualTo("ERROR");
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code).contains("CELL_ERROR_VALUE");
            });
        }
    }

    @Test
    void keepsFormulaWithoutCacheVisibleAndInvalidWhenMapped() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")),
                List.of(new ProductExcelImportReadService.CellView("=1+2", "1+2", null, false, false, "FORMULA_NO_CACHE"))));
        var result = service.preview(null, request(Map.of("code", "A"), 2, false));
        assertThat(result.detectedRows()).isEqualTo(1);
        assertThat(result.rows().get(0).errors()).extracting(ProductExcelImportPreviewService.ImportError::code).contains("FORMULA_NO_CACHE");
    }

    @Test
    void globalSelectorDoesNotConsumeAnErrorInTheReplacedExcelColumn() {
        existingProduct("A1");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Impuestos incluidos")),
                List.of(c("A1"), new ProductExcelImportReadService.CellView("#VALUE!", "1+2", null, false, false, "FORMULA_RESULT_ERROR"))));
        var options = new ProductExcelImportPreviewService.PreviewOptions(Map.of(),
                Map.of("taxesIncluded", new ProductExcelImportPreviewService.ValueSource("global", "1")), false,
                "STOCK", storeId, companyId, false, false);
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A", "taxesIncluded", "B"),
                List.of(), options, storeId, companyId, null, 2, null, Map.of("taxesIncluded", true)));
        assertThat(result.errors()).isEmpty();
        assertThat(result.rows().get(0).errors()).isEmpty();
        assertThat(result.rows().get(0).excelData()).containsEntry("taxesIncluded", "1");
    }

    @Test
    void productTypeUsesAnExcelColumnOrValidatedGlobalSelection() {
        existingProduct("A1");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Tipo")), List.of(c("A1"), c("SERVICE"))));
        for (String type : List.of("UNIT", "WEIGHT", "SERVICE", "INVALID")) {
            var options = new ProductExcelImportPreviewService.PreviewOptions(Map.of(),
                    Map.of("productType", new ProductExcelImportPreviewService.ValueSource("global", type)), false,
                    "STOCK", storeId, companyId, false, false);
            var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A", "productType", "B"),
                    List.of(), options, storeId, companyId, null, 2, null, Map.of("productType", true)));
            if (type.equals("INVALID")) {
                assertThat(result.rows().get(0).errors()).extracting(ProductExcelImportPreviewService.ImportError::code).contains("PRODUCT_TYPE_INVALID");
            } else {
                assertThat(result.errors()).isEmpty();
                assertThat(result.rows().get(0).errors()).isEmpty();
                assertThat(result.rows().get(0).excelData()).containsEntry("productType", type);
            }
        }
        var fromExcel = service.preview(null, request(Map.of("code", "A", "productType", "B"), 2, false));
        assertThat(fromExcel.rows().get(0).excelData()).containsEntry("productType", "SERVICE");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Tipo")),
                List.of(c("A1"), new ProductExcelImportReadService.CellView("#VALUE!", "1+2", null, false, false, "FORMULA_RESULT_ERROR"))));
        var global = new ProductExcelImportPreviewService.PreviewOptions(Map.of(),
                Map.of("productType", new ProductExcelImportPreviewService.ValueSource("global", "UNIT")), false,
                "STOCK", storeId, companyId, false, false);
        var replaced = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A", "productType", "B"),
                List.of(), global, storeId, companyId, null, 2, null, Map.of("productType", true)));
        assertThat(replaced.rows().get(0).errors()).isEmpty();
        assertThat(replaced.rows().get(0).excelData()).containsEntry("productType", "UNIT");
        assertThat(replaced.sourceRows().get(0).excelData()).containsEntry("productType", "UNIT");
    }

    @Test
    void submittingTheSameErrorAsAnEditDoesNotRemoveItsDiagnostic() {
        existingProduct("A1");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Nombre")),
                List.of(c("A1"), new ProductExcelImportReadService.CellView("#VALUE!", null, null, false, false, "CELL_ERROR_VALUE"))));
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A", "name", "B"),
                List.of(new ProductExcelImportPreviewService.CellEdit(2, "B", "#VALUE!")), options(),
                storeId, companyId, null, 2, null, Map.of("name", true)));
        assertThat(result.rows().get(0).errors()).extracting(ProductExcelImportPreviewService.ImportError::code).contains("CELL_ERROR_VALUE");
    }

    @Test
    void numericProductTypesResolveTheSameWayFromExcelAndGlobalSelections() {
        existingProduct("A1");
        for (var entry : Map.of("1", "UNIT", "2", "WEIGHT", "3", "SERVICE").entrySet()) {
            when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Tipo")), List.of(c("A1"), c(entry.getKey()))));
            var excel = service.preview(null, request(Map.of("code", "A", "productType", "B"), 2, false));
            assertThat(excel.rows().get(0).errors()).isEmpty();
            assertThat(excel.rows().get(0).excelData()).containsEntry("productType", entry.getValue());
            assertThat(excel.sourceRows().get(0).excelData()).containsEntry("productType", entry.getKey());
            var options = new ProductExcelImportPreviewService.PreviewOptions(Map.of(),
                    Map.of("productType", new ProductExcelImportPreviewService.ValueSource("global", entry.getKey())), false,
                    "STOCK", storeId, companyId, false, false);
            var global = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"),
                    List.of(), options, storeId, companyId, null, 2, null, Map.of("productType", true)));
            assertThat(global.rows().get(0).errors()).isEmpty();
            assertThat(global.rows().get(0).excelData()).containsEntry("productType", entry.getValue());
        }
    }

    @Test
    void offerActiveGlobalValueReplacesTheColumnAndStillRequiresAValidOffer() {
        existingProduct("A1");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Oferta activa")),
                List.of(c("A1"), new ProductExcelImportReadService.CellView("#VALUE!", "1+2", null, false, false, "FORMULA_RESULT_ERROR"))));
        for (String value : List.of("0", "1", "2")) {
            var options = new ProductExcelImportPreviewService.PreviewOptions(Map.of(),
                    Map.of("offerActive", new ProductExcelImportPreviewService.ValueSource("global", value)), false,
                    "STOCK", storeId, companyId, false, false);
            var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A", "offerActive", "B"),
                    List.of(), options, storeId, companyId, null, 2, null, Map.of("offerActive", true)));
            assertThat(result.errors()).isEmpty();
            assertThat(result.rows().get(0).errors()).extracting(ProductExcelImportPreviewService.ImportError::code).doesNotContain("FORMULA_RESULT_ERROR");
            if (value.equals("0")) {
                assertThat(result.rows().get(0).errors()).isEmpty();
                assertThat(result.rows().get(0).excelData()).containsEntry("offerActive", "0");
            } else assertThat(result.rows().get(0).errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                    .contains(value.equals("1") ? "OFFER_REQUIRED" : "INVALID_BOOLEAN");
            assertThat(result.sourceRows().get(0).excelData()).containsEntry("offerActive", value);
        }
    }

    @Test
    void activeOfferGlobalSelectionAcceptsACompleteOffer() {
        existingProduct("A1");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Oferta"), c("Desde")),
                List.of(c("A1"), c("1.234"), c("08-09-26"))));
        var options = new ProductExcelImportPreviewService.PreviewOptions(Map.of(),
                Map.of("offerActive", new ProductExcelImportPreviewService.ValueSource("global", "1")), false,
                "STOCK", storeId, companyId, false, false);
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "offerPrice", "B", "offerFrom", "C"), List.of(), options,
                storeId, companyId, null, 2, null, Map.of("offerActive", true, "offerPrice", true, "offerFrom", true)));
        assertThat(result.errors()).isEmpty();
        assertThat(result.rows().get(0).errors()).isEmpty();
        assertThat(result.rows().get(0).excelData()).containsEntry("offerActive", "1")
                .containsEntry("offerPrice", "1.234").containsEntry("offerFrom", "2026-09-08");
    }

    @Test
    void errorCellsBlockMasterWritesAndDestinationPreparationOnTheServer() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("test", "",
                        List.of(() -> "GESTION_PRODUCTO", () -> "GESTION_ALMACEN")));
        try {
            existingProduct("A1");
            when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Precio")),
                    List.of(c("A1"), new ProductExcelImportReadService.CellView("#VALUE!", "1+2", null, false, false, "FORMULA_RESULT_ERROR"))));
            var writer = mock(ProductExcelImportApplyWriter.class);
            var apply = new ProductExcelImportApplyService(service, writer, audit);
            var config = new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A", "purchasePrice", "B"),
                    List.of(), options(), storeId, companyId, "a".repeat(64), 2, null, Map.of("purchasePrice", true));
            var file = new org.springframework.mock.web.MockMultipartFile("file", "test.xlsx", null, new byte[]{1});
            var fingerprint = service.preview(file, config).previewFingerprint();
            for (var operation : List.of(ProductExcelImportApplyService.Operation.UPDATE_SELECTED_FIELDS,
                    ProductExcelImportApplyService.Operation.PREPARE_DESTINATION)) {
                var result = apply.apply(file, new ProductExcelImportApplyService.ApplyRequest(config, Map.of(), false,
                        true, null, null, null, false, operation, fingerprint));
                assertThat(result.appliedCount()).isZero();
                assertThat(result.errors()).extracting(ProductExcelImportApplyService.ApplyError::code).contains("FORMULA_RESULT_ERROR");
                org.mockito.Mockito.verifyNoInteractions(writer);
            }
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private Product existingProduct(String code) {
        UUID id = UUID.randomUUID();
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(id);
        when(product.getVersion()).thenReturn(1L);
        when(product.getCode()).thenReturn(code);
        when(product.getName()).thenReturn("Existente");
        when(product.getProductType()).thenReturn(com.tpverp.backend.catalog.ProductType.UNIT);
        when(product.getPriceUseMode()).thenReturn(com.tpverp.backend.catalog.PriceUseMode.NORMAL);
        when(product.getDiscountType()).thenReturn(com.tpverp.backend.catalog.DiscountType.NORMAL);
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(List.of(
                new ProductIdentifier(storeId, id, com.tpverp.backend.catalog.IdentifierType.CODIGO, code)));
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        return product;
    }

    private ProductExcelImportPreviewService.PreviewRequest request(Map<String, String> mapping, int startRow, boolean onlyImported) {
        var options = new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), onlyImported, "WAREHOUSE_INPUT", storeId, companyId, false, false);
        return new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(), options, storeId, companyId, null, startRow, null, Map.of());
    }

    private ProductExcelImportPreviewService.PreviewOptions options() {
        return new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "WAREHOUSE_INPUT", storeId, companyId, false, false);
    }

    private static ProductExcelImportReadService.ReadResult read(List<ProductExcelImportReadService.CellView>... rows) {
        int columns = Arrays.stream(rows).mapToInt(List::size).max().orElse(0);
        return new ProductExcelImportReadService.ReadResult("test.xlsx", "a".repeat(64), "Hoja",
                Arrays.asList(rows), List.of(), rows.length, columns, columns * rows.length);
    }

    private static ProductExcelImportReadService.CellView c(String value) {
        return new ProductExcelImportReadService.CellView(value, null);
    }

}
