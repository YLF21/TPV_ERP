package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.IdentifierType;
import com.tpverp.backend.catalog.PriceTier;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductIdentifier;
import com.tpverp.backend.catalog.ProductIdentifierRepository;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductImportServiceTest {

    @Mock private CurrentOrganization organization;
    @Mock private ProductIdentifierRepository identifiers;
    @Mock private ProductRepository products;

    private ProductImportService service;
    private Store store;

    @BeforeEach
    void setUp() {
        var company = new Company("B00000000", "Company", address());
        store = new Store(company, "Store", address(), "hash",
                "Atlantic/Canary", "EUR", "es-ES");
        service = new ProductImportService(organization, identifiers, products);
    }

    @Test
    void previewsNewProductFromWorkbookRow() throws Exception {
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC")).thenReturn(Optional.empty());
        when(identifiers.findByStoreIdAndValor(store.getId(), "123")).thenReturn(Optional.empty());

        var preview = service.preview(workbookWithRow("ABC", "123", "Producto Excel", "10.00", "15.00", "2"),
                mapping(false));

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).status())
                .isEqualTo(ProductImportPreviewRow.Status.NEW_PRODUCT);
        assertThat(preview.rows().get(0).errors()).isEmpty();
    }

    @Test
    void existingProductWithChangedNameReturnsUpdateProduct() throws Exception {
        var product = product();
        product.replaceIdentifier(IdentifierType.CODIGO, "ABC");
        var identifier = new ProductIdentifier(store.getId(), product.getId(),
                IdentifierType.CODIGO, "ABC");
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC"))
                .thenReturn(Optional.of(identifier));
        when(products.findById(product.getId())).thenReturn(Optional.of(product));

        var preview = service.preview(workbookWithRow("ABC", null, "Producto Excel", "10.00", null, null),
                mapping(true));

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).status())
                .isEqualTo(ProductImportPreviewRow.Status.UPDATE_PRODUCT);
        assertThat(preview.rows().get(0).changes())
                .extracting(ProductImportPreviewRow.ProductChange::campo)
                .containsExactly("nombre");
    }

    @Test
    void newProductWithoutQuantityIsValid() throws Exception {
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC")).thenReturn(Optional.empty());

        var preview = service.preview(workbookWithRow("ABC", null, "Producto Excel", "10.00", null, null),
                mapping(false));

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).status())
                .isEqualTo(ProductImportPreviewRow.Status.NEW_PRODUCT);
        assertThat(preview.rows().get(0).errors()).isEmpty();
    }

    @Test
    void negativeQuantityReturnsError() throws Exception {
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC")).thenReturn(Optional.empty());

        var preview = service.preview(workbookWithRow("ABC", null, "Producto Excel", "10.00", null, "-1"),
                mapping(false));

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).status())
                .isEqualTo(ProductImportPreviewRow.Status.ERROR);
        assertThat(preview.rows().get(0).errors())
                .contains("cantidad debe ser positiva");
    }

    private static ByteArrayInputStream workbookWithRow(
            String code,
            String barcode,
            String name,
            String purchasePrice,
            String salePrice,
            String quantity) throws Exception {
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var row = workbook.createSheet().createRow(0);
            if (code != null) {
                row.createCell(0).setCellValue(code);
            }
            if (barcode != null) {
                row.createCell(1).setCellValue(barcode);
            }
            if (name != null) {
                row.createCell(2).setCellValue(name);
            }
            if (purchasePrice != null) {
                row.createCell(3).setCellValue(purchasePrice);
            }
            if (salePrice != null) {
                row.createCell(4).setCellValue(salePrice);
            }
            if (quantity != null) {
                row.createCell(5).setCellValue(quantity);
            }
            workbook.write(output);
            return new ByteArrayInputStream(output.toByteArray());
        }
    }

    private static ProductImportMapping mapping(boolean updateName) {
        return new ProductImportMapping(
                "A",
                "B",
                "C",
                null,
                "D",
                "E",
                null,
                null,
                null,
                "F",
                null,
                1,
                updateName,
                false,
                false,
                false,
                false,
                false);
    }

    private Product product() {
        var product = new Product(store.getId(), UUID.randomUUID(), null, UUID.randomUUID(),
                "Producto Actual", null, new BigDecimal("10.00"), true);
        product.setPrice(PriceTier.VENTA, new BigDecimal("15.00"));
        return product;
    }

    private static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle Uno",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
    }
}
