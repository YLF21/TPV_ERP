package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.Family;
import com.tpverp.backend.catalog.FamilyRepository;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductIdentifier;
import com.tpverp.backend.catalog.ProductIdentifierRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.PriceUseMode;
import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.StoreTax;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.catalog.Subfamily;
import com.tpverp.backend.catalog.SubfamilyRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductExcelImportPreviewContractMatrixTest {
    private final ProductExcelImportReadService reader = mock(ProductExcelImportReadService.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final ProductIdentifierRepository identifiers = mock(ProductIdentifierRepository.class);
    private final com.tpverp.backend.catalog.ProductRepository products = mock(com.tpverp.backend.catalog.ProductRepository.class);
    private final StoreTaxRepository taxes = mock(StoreTaxRepository.class);
    private final FamilyRepository families = mock(FamilyRepository.class);
    private final SubfamilyRepository subfamilies = mock(SubfamilyRepository.class);
    private final UUID storeId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private ProductExcelImportPreviewService service;

    @BeforeEach
    void setup() {
        Store store = mock(Store.class);
        Company company = mock(Company.class);
        when(store.getId()).thenReturn(storeId);
        when(store.getEmpresa()).thenReturn(company);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(List.of());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of());
        when(families.findByStoreIdOrderByFamilyCodeAscIdAsc(any())).thenReturn(List.of());
        when(subfamilies.findByStoreIdAndIdIn(any(), any())).thenReturn(List.of());
        when(subfamilies.findByStoreIdAndSubfamilyCodeIn(any(), any())).thenReturn(List.of());
        service = new ProductExcelImportPreviewService(reader, organization, identifiers, products, taxes,
                mock(AuditService.class), families, subfamilies);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "3", "4"})
    void canonicalizesEveryExcelPriceMode(String mode) {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Nombre"), c("Modo")), List.of(c("X"), c("Nuevo"), c(mode))));
        var result = preview(Map.of("code", "A", "name", "B", "priceUseMode", "C"), options("excel", null), Map.of());
        assertThat(result.rows()).singleElement().extracting(row -> row.excelData().get("priceUseMode"))
                .isEqualTo(List.of("NORMAL", "MEMBER_PRICE", "OFFER_PRICE", "OFFER_DISCOUNT").get(Integer.parseInt(mode) - 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "3", "4"})
    void canonicalizesEveryGlobalPriceMode(String mode) {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Nombre")), List.of(c("X"), c("Nuevo"))));
        var result = preview(Map.of("code", "A", "name", "B"), options("global", mode), Map.of());
        assertThat(result.rows()).singleElement().extracting(row -> row.excelData().get("priceUseMode"))
                .isEqualTo(List.of("NORMAL", "MEMBER_PRICE", "OFFER_PRICE", "OFFER_DISCOUNT").get(Integer.parseInt(mode) - 1));
    }

    @Test
    void validatesBooleanDomainForDiscountAndTaxesIncluded() {
        Product product = existing("A");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Prohibido"), c("Incluidos")), List.of(c("A"), c("2"), c("2"))));
        doReturn(List.of(identifier(product, "A"))).when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        var invalid = preview(Map.of("code", "A", "discountType", "B", "taxesIncluded", "C"), options(), Map.of("discountType", true, "taxesIncluded", true));
        assertThat(invalid.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("INVALID_BOOLEAN"));
    }

    @Test
    void acceptsZeroAndOneForBothBooleanAttributes() {
        Product product = existing("A");
        doReturn(List.of(identifier(product, "A"))).when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Prohibido"), c("Incluidos")),
                List.of(c("A"), c("1"), c("0"))));
        var result = preview(Map.of("code", "A", "discountType", "B", "taxesIncluded", "C"), options(),
                Map.of("discountType", true, "taxesIncluded", true));
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                    .doesNotContain("INVALID_BOOLEAN");
            assertThat(row.excelData()).containsEntry("discountType", "1").containsEntry("taxesIncluded", "0");
        });
    }

    @Test
    void appliesModernGlobalBooleanSources() {
        Product product = existing("A");
        doReturn(List.of(identifier(product, "A"))).when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A"))));
        var options = new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(
                "discountType", new ProductExcelImportPreviewService.ValueSource("global", "1"),
                "taxesIncluded", new ProductExcelImportPreviewService.ValueSource("global", "0")),
                false, "STOCK", storeId, companyId, false, false);
        var result = previewWithOptions(Map.of("code", "A"), options,
                Map.of("discountType", true, "taxesIncluded", true));
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                    .doesNotContain("INVALID_BOOLEAN");
            assertThat(row.excelData()).containsEntry("discountType", "1").containsEntry("taxesIncluded", "0");
        });
    }

    @Test
    void rejectsInvalidGlobalSpecialValuesInsteadOfFallingBack() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A"))));
        for (Map.Entry<String, String> entry : Map.of("priceUseMode", "9", "discountType", "2",
                "prohibitedDiscount", "2", "taxesIncluded", "2").entrySet()) {
        var invalid = new ProductExcelImportPreviewService.PreviewOptions(Map.of(entry.getKey(), entry.getValue()),
                    Map.of(entry.getKey(), new ProductExcelImportPreviewService.ValueSource("global", entry.getValue())),
                    false, "STOCK", storeId, companyId, false, false);
            var result = previewWithOptions(Map.of("code", "A"), invalid, Map.of());
            assertThat(result.rows()).singleElement().satisfies(row ->
                    assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                            .contains(entry.getKey().equals("priceUseMode") ? "INVALID_PRICE_MODE" : "INVALID_BOOLEAN"));
        }
    }

    @Test
    void resolvesInactiveUnknownAndAmbiguousTaxes() {
        Product product = existing("A");
        doReturn(List.of(identifier(product, "A"))).when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        StoreTax inactive = tax(false, new java.math.BigDecimal("21"));
        when(taxes.findByStoreIdOrderByPorcentaje(storeId)).thenReturn(List.of(inactive));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("IVA")), List.of(c("A"), c("21"))));
        assertThat(preview(Map.of("code", "A", "taxId", "B"), options(), Map.of("taxId", true)).rows().get(0).errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code).contains("TAX_UNKNOWN");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("IVA")), List.of(c("A"), c("99"))));
        assertThat(preview(Map.of("code", "A", "taxId", "B"), options(), Map.of("taxId", true)).rows().get(0).errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code).contains("TAX_UNKNOWN");
        StoreTax one = tax(true, new java.math.BigDecimal("10"));
        StoreTax two = tax(true, new java.math.BigDecimal("10.00"));
        when(taxes.findByStoreIdOrderByPorcentaje(storeId)).thenReturn(List.of(one, two));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("IVA")), List.of(c("A"), c("10"))));
        assertThat(preview(Map.of("code", "A", "taxId", "B"), options(), Map.of("taxId", true)).rows().get(0).errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code).contains("TAX_AMBIGUOUS");
        StoreTax canonical = tax(true, new java.math.BigDecimal("21.00"));
        when(taxes.findByStoreIdOrderByPorcentaje(storeId)).thenReturn(List.of(canonical));
        var global = new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(
                "taxId", new ProductExcelImportPreviewService.ValueSource("global", "21")), false,
                "STOCK", storeId, companyId, false, false);
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A"))));
        var resolved = previewWithOptions(Map.of("code", "A"), global, Map.of("taxId", true));
        assertThat(resolved.rows()).singleElement().extracting(row -> row.excelData().get("taxId"))
                .isEqualTo(canonical.getId().toString());
    }

    @Test
    void acceptsShortAndLongLeapDatesAndUsesResultingRange() {
        Product product = existing("A");
        doReturn(java.time.LocalDate.of(2025, 1, 1)).when(product).getOfferUntil();
        doReturn(List.of(identifier(product, "A"))).when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Desde"), c("Hasta")), List.of(c("A"), c("29-02-2024"), c("31-12-2024"))));
        var valid = preview(Map.of("code", "A", "offerFrom", "B", "offerUntil", "C"), options(), Map.of("offerFrom", true, "offerUntil", true));
        assertThat(valid.rows()).singleElement().extracting(row -> row.excelData().get("offerFrom")).isEqualTo("2024-02-29");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Desde")), List.of(c("A"), c("01-01-2026"))));
        var onlyFrom = preview(Map.of("code", "A", "offerFrom", "B"), options(), Map.of("offerFrom", true, "offerUntil", false));
        assertThat(onlyFrom.rows().get(0).errors()).extracting(ProductExcelImportPreviewService.ImportError::code).contains("DATE_RANGE_INVALID");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Desde"), c("Hasta")), List.of(c("A"), c("31-12-AAAA"), c("30-12-2026"))));
        var invalid = preview(Map.of("code", "A", "offerFrom", "B", "offerUntil", "C"), options(), Map.of("offerFrom", true, "offerUntil", true));
        assertThat(invalid.rows()).singleElement().extracting(row -> row.errors().stream().map(ProductExcelImportPreviewService.ImportError::code).toList())
                .asList().contains("DATE_INVALID");
    }

    @Test
    void validatesFourDigitDatesInvalidLeapAndIgnoredResultingRange() {
        Product product = existing("A");
        doReturn(List.of(identifier(product, "A"))).when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(product.getOfferFrom()).thenReturn(java.time.LocalDate.of(2026, 12, 31));
        when(product.getOfferUntil()).thenReturn(java.time.LocalDate.of(2027, 1, 1));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Desde"), c("Hasta")),
                List.of(c("A"), c("29-02-2024"), c("01-03-2024"))));
        var valid = preview(Map.of("code", "A", "offerFrom", "B", "offerUntil", "C"), options(),
                Map.of("offerFrom", true, "offerUntil", true));
        assertThat(valid.rows()).singleElement().satisfies(row ->
                assertThat(row.excelData()).containsEntry("offerFrom", "2024-02-29"));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Desde")),
                List.of(c("A"), c("29-02-2023"))));
        var invalidLeap = preview(Map.of("code", "A", "offerFrom", "B"), options(), Map.of("offerFrom", true));
        assertThat(invalidLeap.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("DATE_INVALID"));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Hasta")),
                List.of(c("A"), c("01-01-2026"))));
        var onlyUntil = preview(Map.of("code", "A", "offerUntil", "B"), options(), Map.of("offerUntil", true));
        assertThat(onlyUntil.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("DATE_RANGE_INVALID"));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Desde"), c("Hasta")),
                List.of(c("A"), c("01-01-2030"), c("01-01-2020"))));
        var ignored = preview(Map.of("code", "A", "offerFrom", "B", "offerUntil", "C"), options(),
                Map.of("offerFrom", false, "offerUntil", false));
        assertThat(ignored.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .doesNotContain("DATE_RANGE_INVALID"));
    }

    @Test
    void mapsColumnAA() {
        List<ProductExcelImportReadService.CellView> row = new ArrayList<>();
        for (int i = 0; i < 27; i++) row.add(c(i == 26 ? "AA-VALUE" : ""));
        when(reader.read(any())).thenReturn(read(List.of(c("Cabecera")), row));
        var result = preview(Map.of("code", "AA"), options(), Map.of());
        assertThat(result.rows()).singleElement().extracting(r -> r.excelData().get("code")).isEqualTo("AA-VALUE");
    }

    @Test
    void reportsAmbiguousIdentityAndPreservesExistingWithoutName() {
        Product p1 = existing("A"); Product p2 = existing("B");
        doReturn(List.of(identifier(p1, "A"), identifier(p2, "X"))).when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(p1, p2));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Barras")), List.of(c("A"), c("X"))));
        var ambiguous = preview(Map.of("code", "A", "barcode", "B"), options(), Map.of());
        assertThat(ambiguous.rows()).singleElement().extracting(r -> r.errors().stream().map(ProductExcelImportPreviewService.ImportError::code).toList())
                .asList().contains("PRODUCT_AMBIGUOUS");
        Product nameless = existing("N"); when(nameless.getName()).thenReturn(null);
        doReturn(List.of(identifier(nameless, "N"))).when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(nameless));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("N"))));
        assertThat(preview(Map.of("code", "A"), options(), Map.of()).rows()).singleElement()
                .extracting(ProductExcelImportPreviewService.PreviewRow::classification).isEqualTo("EXISTING");
    }

    @Test
    void distinguishesMissingNameAndSupplierReferenceFallback() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Nombre")), List.of(c("NEW"), c(""))));
        var missing = preview(Map.of("code", "A", "name", "B"), options(), Map.of());
        assertThat(missing.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("NAME_REQUIRED"));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Barras")), List.of(c("C"), c("B"))));
        var fallback = preview(Map.of("code", "A", "barcode", "B"), options(), Map.of());
        assertThat(fallback.rows()).singleElement().extracting(r -> r.excelData().get("supplierReference")).isEqualTo("C");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Barras"), c("Referencia")),
                List.of(c(""), c("BAR-1"), c(""))));
        var barcodeFallback = preview(Map.of("code", "A", "barcode", "B", "supplierReference", "C"), options(), Map.of());
        assertThat(barcodeFallback.rows()).singleElement().extracting(r -> r.excelData().get("supplierReference"))
                .isEqualTo("BAR-1");
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Referencia")),
                List.of(c("NEW"), c("SUP-42"))));
        var explicit = preview(Map.of("code", "A", "supplierReference", "B"), options(), Map.of());
        assertThat(explicit.rows()).singleElement().extracting(r -> r.excelData().get("supplierReference"))
                .isEqualTo("SUP-42");
    }

    @Test
    void rejects5001BeforeDuplicateMergeAndKeepsNameOnlyRowsIndependent() {
        @SuppressWarnings("unchecked") List<ProductExcelImportReadService.CellView>[] rows = new List[5_002];
        rows[0] = List.of(c("Codigo"));
        for (int i = 1; i < rows.length; i++) rows[i] = List.of(c("SAME"));
        when(reader.read(any())).thenReturn(read(rows));
        assertThat(preview(Map.of("code", "A"), options(), Map.of()).errors())
                .extracting(ProductExcelImportPreviewService.ImportError::code).contains("ROW_LIMIT");
        when(reader.read(any())).thenReturn(read(List.of(c("Nombre")), List.of(c("Uno")), List.of(c("Dos"))));
        var names = preview(Map.of("name", "A"), options(), Map.of());
        assertThat(names.detectedRows()).isEqualTo(2);
        assertThat(names.rows()).hasSize(2);
        assertThat(names.rows()).allSatisfy(row -> assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code).contains("IDENTIFIER_REQUIRED"));
    }

    @Test
    void acceptsExactlyFiveThousandDetectedRows() {
        @SuppressWarnings("unchecked") List<ProductExcelImportReadService.CellView>[] rows = new List[5_001];
        rows[0] = List.of(c("Codigo"));
        for (int i = 1; i < rows.length; i++) rows[i] = List.of(c("N-" + i));
        when(reader.read(any())).thenReturn(read(rows));
        assertThat(preview(Map.of("code", "A"), options(), Map.of()).detectedRows()).isEqualTo(5_000);
    }

    @Test
    void blocksCrossIdentityDuplicateAndScopedFamilyReferences() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Barras"), c("Nombre")),
                List.of(c("X"), c(""), c("Uno")), List.of(c(""), c("X"), c("Uno"))));
        var duplicate = preview(Map.of("code", "A", "barcode", "B", "name", "C"), options(), Map.of());
        assertThat(duplicate.rows()).hasSize(2).allSatisfy(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("DUPLICATE_CONFLICT"));

        Product existing = existing("A");
        doReturn(List.of(identifier(existing, "A"))).when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(existing));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Barras"), c("Nombre")),
                List.of(c("A"), c("X"), c("Uno")), List.of(c("NEW"), c("X"), c("Dos"))));
        var existingNew = preview(Map.of("code", "A", "barcode", "B", "name", "C"), options(), Map.of());
        assertThat(existingNew.rows()).hasSize(2).allSatisfy(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("DUPLICATE_CONFLICT"));

        UUID familyId = UUID.randomUUID();
        Family family = mock(Family.class);
        when(family.getId()).thenReturn(familyId); when(family.getFamilyCode()).thenReturn("001");
        when(family.isDefaultFamily()).thenReturn(false);
        when(families.findByStoreIdOrderByFamilyCodeAscIdAsc(storeId)).thenReturn(List.of(family));
        UUID otherFamily = UUID.randomUUID();
        Subfamily sub = mock(Subfamily.class);
        UUID validSubId = UUID.randomUUID();
        when(sub.getId()).thenReturn(validSubId); when(sub.getSubfamilyCode()).thenReturn("002001");
        when(sub.getFamilyId()).thenReturn(otherFamily);
        when(subfamilies.findByStoreIdAndSubfamilyCodeIn(any(), any())).thenReturn(List.of(sub));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Familia"), c("Subfamilia")),
                List.of(c("NEW"), c("001"), c("002001"))));
        var mismatch = preview(Map.of("code", "A", "familyId", "B", "subfamilyId", "C"), options(), Map.of());
        assertThat(mismatch.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("SUBFAMILY_FAMILY_MISMATCH"));
        when(subfamilies.findByStoreIdAndIdIn(any(), any())).thenReturn(List.of(sub));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Subfamilia")),
                List.of(c("NEW-UUID"), c(validSubId.toString()))));
        var validUuid = preview(Map.of("code", "A", "subfamilyId", "B"), options(), Map.of());
        assertThat(validUuid.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .doesNotContain("SUBFAMILY_UNKNOWN"));
        Product existingProduct = existing("E");
        UUID oldFamily = UUID.randomUUID();
        when(existingProduct.getFamilyId()).thenReturn(oldFamily);
        doReturn(List.of(identifier(existingProduct, "E"))).when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(existingProduct));
        Subfamily childOfNewFamily = mock(Subfamily.class);
        when(childOfNewFamily.getId()).thenReturn(UUID.randomUUID()); when(childOfNewFamily.getSubfamilyCode()).thenReturn("001002");
        when(childOfNewFamily.getFamilyId()).thenReturn(familyId);
        when(subfamilies.findByStoreIdAndSubfamilyCodeIn(any(), any())).thenReturn(List.of(childOfNewFamily));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Familia"), c("Subfamilia")),
                List.of(c("E"), c("001"), c("001002"))));
        var ignoredFamily = preview(Map.of("code", "A", "familyId", "B", "subfamilyId", "C"), options(),
                Map.of("familyId", false, "subfamilyId", true));
        assertThat(ignoredFamily.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("SUBFAMILY_FAMILY_MISMATCH"));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Familia")),
                List.of(c("NEW-2"), c(UUID.randomUUID().toString()))));
        var foreign = preview(Map.of("code", "A", "familyId", "B"), options(), Map.of());
        assertThat(foreign.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("FAMILY_UNKNOWN"));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Subfamilia")),
                List.of(c("NEW-FOREIGN"), c(UUID.randomUUID().toString()))));
        var foreignSubfamily = preview(Map.of("code", "A", "subfamilyId", "B"), options(), Map.of());
        assertThat(foreignSubfamily.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("SUBFAMILY_UNKNOWN"));
        Subfamily sameCode = mock(Subfamily.class);
        when(sameCode.getId()).thenReturn(UUID.randomUUID()); when(sameCode.getSubfamilyCode()).thenReturn("002001");
        when(sameCode.getFamilyId()).thenReturn(familyId);
        when(subfamilies.findByStoreIdAndSubfamilyCodeIn(any(), any())).thenReturn(List.of(sub, sameCode));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Familia"), c("Subfamilia")),
                List.of(c("NEW-3"), c("001"), c("002001"))));
        var ambiguousSubfamily = preview(Map.of("code", "A", "familyId", "B", "subfamilyId", "C"), options(), Map.of());
        assertThat(ambiguousSubfamily.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("SUBFAMILY_AMBIGUOUS"));
    }

    @Test
    void malformedEditAndNonPaddedDateAreStructuredErrors() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Fecha")),
                List.of(c("A"), c("1-1-24"))));
        var malformed = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A", "offerFrom", "B"), Arrays.asList((ProductExcelImportPreviewService.CellEdit) null),
                options(), storeId, companyId, null, 2, null, Map.of()));
        assertThat(malformed.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("CELL_EDIT_INVALID");
        Product product = existing("A");
        doReturn(List.of(identifier(product, "A"))).when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        var invalidDate = preview(Map.of("code", "A", "offerFrom", "B"), options(), Map.of("offerFrom", true));
        assertThat(invalidDate.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("DATE_INVALID"));
    }

    @Test
    void rejectsUnknownValueSourceAndInvalidContext() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A"))));
        var invalid = new ProductExcelImportPreviewService.PreviewOptions(Map.of(),
                Map.of("unknown", new ProductExcelImportPreviewService.ValueSource("excel", null),
                        "priceUseMode", new ProductExcelImportPreviewService.ValueSource("stored", null)),
                false, "ALMACEN", storeId, companyId, false, false);
        var result = previewWithOptions(Map.of("code", "A"), invalid, Map.of());
        assertThat(result.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("CONTEXT_INVALID", "VALUE_SOURCE_UNKNOWN", "VALUE_SOURCE_INVALID");
    }

    @Test
    void previewBlocksOfferModeWhenResultingOfferFieldsAreMissing() {
        Product product = existing("A");
        doReturn(List.of(identifier(product, "A"))).when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Modo")), List.of(c("A"), c("OFFER_PRICE"))));
        var result = preview(Map.of("code", "A", "priceUseMode", "B"), options(), Map.of("priceUseMode", true));
        assertThat(result.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("OFFER_REQUIRED"));
    }

    @Test
    void rejectsInvalidGlobalForeignOptionsAndPrimaryIdentifiersInUpdateFields() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A"))));
        var foreign = new ProductExcelImportPreviewService.PreviewOptions(Map.of("other", "x"), Map.of(), false,
                "STOCK", UUID.randomUUID(), UUID.randomUUID(), false, false);
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"), List.of(), foreign,
                null, null, null, 2, null, Map.of("code", true)));
        assertThat(result.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("GLOBAL_VALUE_UNKNOWN", "UPDATE_FIELD_UNKNOWN");
        var foreignContext = new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false,
                "STOCK", UUID.randomUUID(), UUID.randomUUID(), false, false);
        var foreignOnly = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A"), List.of(), foreignContext, null, null, null, 2, null, Map.of()));
        assertThat(foreignOnly.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("STORE_CONTEXT_MISMATCH");
        var foreignCompany = new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false,
                "STOCK", storeId, UUID.randomUUID(), false, false);
        var companyResult = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A"), List.of(), foreignCompany, null, null, null, 2, null, Map.of()));
        assertThat(companyResult.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("COMPANY_CONTEXT_MISMATCH");
    }

    @Test
    void rejectsOversizedConfigurationMapsBeforeIteratingUntrustedKeys() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A"))));
        Map<String, String> mapping = new LinkedHashMap<>();
        Map<String, String> globalValues = new LinkedHashMap<>();
        Map<String, ProductExcelImportPreviewService.ValueSource> valueSources = new LinkedHashMap<>();
        Map<String, Boolean> updateFields = new LinkedHashMap<>();
        IntStream.range(0, 64).forEach(index -> {
            mapping.put("mapping-" + index, "A");
            globalValues.put("global-" + index, "1");
            valueSources.put("source-" + index, new ProductExcelImportPreviewService.ValueSource("excel", null));
            updateFields.put("update-" + index, true);
        });
        var options = new ProductExcelImportPreviewService.PreviewOptions(
                globalValues, valueSources, false, "STOCK", storeId, companyId, false, false);
        var result = previewWithOptions(mapping, options, updateFields);

        assertThat(result.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("CONTRACT_LIMIT");
        assertThat(result.errors()).filteredOn(error -> "CONTRACT_LIMIT".equals(error.code()))
                .hasSize(4);
    }

    @Test
    void rejectsMalformedHashAndOversizedContractValuesWithoutEchoingPayload() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A"))));
        String huge = "x".repeat(32_768);
        var options = new ProductExcelImportPreviewService.PreviewOptions(
                Map.of("priceUseMode", huge), Map.of(), false, "STOCK", storeId, companyId, false, false);
        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "AAA"), List.of(), options, storeId, companyId, "not-a-sha", 2, "AAA", Map.of()));
        assertThat(result.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("HASH_REQUIRED", "EDIT_VALUE_LIMIT");
        assertThat(result.errors()).allSatisfy(error -> {
            if (error.receivedValue() != null) assertThat(error.receivedValue()).hasSizeLessThanOrEqualTo(256);
        });
    }

    @Test
    void rejectsOutOfRangeIdentityColumnAfterContractValidation() {
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo")), List.of(c("A"))));
        var result = previewWithOptions(Map.of("code", "AAA"), options(), Map.of());
        assertThat(result.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("COLUMN_INVALID");
    }

    @Test
    void warehouseMergesDuplicateRowsWithoutQuantityUsingOneUnitPerRow() {
        UUID defaultFamilyId = UUID.randomUUID();
        Family defaultFamily = mock(Family.class);
        when(defaultFamily.getId()).thenReturn(defaultFamilyId);
        when(defaultFamily.isDefaultFamily()).thenReturn(true);
        when(families.findByStoreIdOrderByFamilyCodeAscIdAsc(storeId)).thenReturn(List.of(defaultFamily));
        UUID defaultTaxId = UUID.randomUUID();
        StoreTax defaultTax = mock(StoreTax.class);
        when(defaultTax.getId()).thenReturn(defaultTaxId);
        when(defaultTax.getStoreId()).thenReturn(storeId);
        when(defaultTax.isDefaultTax()).thenReturn(true);
        when(defaultTax.isActive()).thenReturn(true);
        when(taxes.findByStoreIdOrderByPorcentaje(storeId)).thenReturn(List.of(defaultTax));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Nombre")),
                List.of(c("A"), c("Cafe")), List.of(c("A"), c("Cafe"))));
        var warehouseOptions = new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false,
                "WAREHOUSE_INPUT", storeId, companyId, false, false);
        var result = previewWithOptions(Map.of("code", "A", "name", "B"), warehouseOptions, Map.of());
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.rowNumbers()).containsExactly(2, 3);
            assertThat(row.excelData()).containsEntry("quantity", "2");
        });
    }

    @Test
    void rejectsEditsThatWouldExpandTheMaterializedGrid() {
        List<ProductExcelImportReadService.CellView> sourceRow = List.of(c("A"));
        List<List<ProductExcelImportReadService.CellView>> rows = new ArrayList<>();
        rows.add(List.of(c("Codigo")));
        IntStream.range(0, 5_000).forEach(ignored -> rows.add(sourceRow));
        when(reader.read(any())).thenReturn(read(rows.toArray(List[]::new)));
        List<ProductExcelImportPreviewService.CellEdit> edits = IntStream.rangeClosed(2, 5_001)
                .mapToObj(row -> new ProductExcelImportPreviewService.CellEdit(row, "IV", "x"))
                .toList();

        var result = service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(
                Map.of("code", "A"), edits, options(), storeId, companyId, null, 2, null, Map.of()));

        assertThat(result.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                .contains("EDIT_LIMIT");
    }

    @Test
    void changesRespectUpdateFieldsAndSkipZeroIncludingNullBefore() {
        Product product = existing("A");
        doReturn(new java.math.BigDecimal("1.00")).when(product).getPurchasePrice();
        doReturn(List.of(identifier(product, "A"))).when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Compra"), c("Nombre")),
                List.of(c("A"), c("0"), c("Nuevo"))));
        var skipOptions = new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false,
                "STOCK", storeId, companyId, true, false);
        var skipped = previewWithOptions(Map.of("code", "A", "purchasePrice", "B", "name", "C"), skipOptions,
                Map.of("purchasePrice", true, "name", true));
        assertThat(skipped.rows()).singleElement().satisfies(row ->
                assertThat(row.changes()).doesNotContainKey("purchasePrice").containsKey("name"));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Nombre")), List.of(c("A"), c("Nuevo"))));
        var disabled = preview(Map.of("code", "A", "name", "B"), options(), Map.of("name", false));
        assertThat(disabled.rows()).singleElement().satisfies(row -> assertThat(row.changes()).doesNotContainKey("name"));
        Product nullBefore = existing("B");
        doReturn(null).when(nullBefore).getName();
        doReturn(List.of(identifier(nullBefore, "B"))).when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(nullBefore));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Nombre")), List.of(c("B"), c("Alta"))));
        var fromNull = preview(Map.of("code", "A", "name", "B"), options(), Map.of("name", true));
        assertThat(fromNull.rows()).singleElement().satisfies(row ->
                assertThat(row.changes()).containsKey("name"));
    }

    @Test
    void rejectsBarcode2CollisionsAgainstCatalogAndWithinTheImportBatch() {
        Product existing = existing("A");
        Product owner = existing("OWNER");
        doReturn(List.of(identifier(existing, "A"),
                new ProductIdentifier(storeId, owner.getId(), com.tpverp.backend.catalog.IdentifierType.CODIGO_BARRAS_2, "B2")))
                .when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(existing, owner));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Barras2"), c("Nombre")),
                List.of(c("A"), c("B2"), c("Producto"))));
        var catalogCollision = preview(Map.of("code", "A", "barcode2", "B", "name", "C"), options(),
                Map.of("barcode2", true));
        assertThat(catalogCollision.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("IDENTIFIER_DUPLICATE"));

        when(identifiers.findAllByStoreIdAndValorLowerIn(any(), any())).thenReturn(List.of());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of());
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Barras2"), c("Nombre")),
                List.of(c("N1"), c("B2"), c("Uno")), List.of(c("N2"), c("B2"), c("Dos"))));
        var batchCollision = preview(Map.of("code", "A", "barcode2", "B", "name", "C"), options(), Map.of());
        assertThat(batchCollision.rows()).hasSize(2).allSatisfy(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("IDENTIFIER_DUPLICATE"));
    }

    @Test
    void permitsRepeatedSecondaryBarcodeForOneExistingProductButNotItsPrimaryCode() {
        Product product = existing("A");
        when(product.getBarcode2()).thenReturn("B2");
        doReturn(List.of(identifier(product, "A"),
                new ProductIdentifier(storeId, product.getId(), com.tpverp.backend.catalog.IdentifierType.CODIGO_BARRAS_2, "B2")))
                .when(identifiers).findAllByStoreIdAndValorLowerIn(any(), any());
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));
        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Barras2"), c("Nombre")),
                List.of(c("A"), c("B2"), c("Producto")), List.of(c("A"), c("B2"), c("Producto"))));

        var repeated = preview(Map.of("code", "A", "barcode2", "B", "name", "C"), options(), Map.of());

        assertThat(repeated.rows()).singleElement().satisfies(row -> {
            assertThat(row.rowNumbers()).containsExactly(2, 3);
            assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                    .doesNotContain("IDENTIFIER_DUPLICATE");
        });

        when(reader.read(any())).thenReturn(read(List.of(c("Codigo"), c("Barras2"), c("Nombre")),
                List.of(c("A"), c("A"), c("Producto"))));
        var primaryCollision = preview(Map.of("code", "A", "barcode2", "B", "name", "C"), options(),
                Map.of("barcode2", true));
        assertThat(primaryCollision.rows()).singleElement().satisfies(row ->
                assertThat(row.errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .contains("IDENTIFIER_DUPLICATE"));
    }

    private ProductExcelImportPreviewService.PreviewResult preview(Map<String, String> mapping,
            ProductExcelImportPreviewService.PreviewOptions options, Map<String, Boolean> updateFields) {
        return service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(), options,
                storeId, companyId, null, 2, null, updateFields));
    }

    private ProductExcelImportPreviewService.PreviewOptions options() {
        return new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "STOCK", storeId, companyId, false, false);
    }

    private ProductExcelImportPreviewService.PreviewOptions options(String source, String value) {
        return new ProductExcelImportPreviewService.PreviewOptions(
                Map.of(),
                Map.of("priceUseMode", new ProductExcelImportPreviewService.ValueSource(source, value)),
                false, "STOCK", storeId, companyId, false, false);
    }

    private ProductExcelImportPreviewService.PreviewResult previewWithOptions(Map<String, String> mapping,
            ProductExcelImportPreviewService.PreviewOptions options, Map<String, Boolean> updateFields) {
        return service.preview(null, new ProductExcelImportPreviewService.PreviewRequest(mapping, List.of(), options,
                storeId, companyId, null, 2, null, updateFields));
    }

    private Product existing(String code) {
        Product product = mock(Product.class);
        UUID id = UUID.randomUUID();
        when(product.getId()).thenReturn(id); when(product.getVersion()).thenReturn(1L); when(product.getCode()).thenReturn(code);
        when(product.getProductType()).thenReturn(ProductType.UNIT); when(product.getPriceUseMode()).thenReturn(PriceUseMode.NORMAL);
        when(product.getDiscountType()).thenReturn(DiscountType.NORMAL); when(product.getName()).thenReturn("Producto");
        return product;
    }

    private ProductIdentifier identifier(Product product, String value) {
        return new ProductIdentifier(storeId, product.getId(), com.tpverp.backend.catalog.IdentifierType.CODIGO, value);
    }

    private StoreTax tax(boolean active, java.math.BigDecimal percentage) {
        StoreTax tax = mock(StoreTax.class); when(tax.getId()).thenReturn(UUID.randomUUID()); when(tax.getStoreId()).thenReturn(storeId);
        when(tax.isActive()).thenReturn(active); when(tax.getPercentage()).thenReturn(percentage); return tax;
    }

    private static ProductExcelImportReadService.ReadResult read(List<ProductExcelImportReadService.CellView>... rows) {
        int columns = Arrays.stream(rows).mapToInt(List::size).max().orElse(0);
        return new ProductExcelImportReadService.ReadResult("test.xlsx", "a".repeat(64), "Hoja", Arrays.asList(rows), List.of(), rows.length, columns, columns * rows.length);
    }

    private static ProductExcelImportReadService.CellView c(String value) { return new ProductExcelImportReadService.CellView(value, null); }
}
