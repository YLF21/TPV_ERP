package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

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

    @Test
    void previewIsReadOnlyTransactional() throws Exception {
        var annotation = ProductImportService.class
                .getMethod("preview", java.io.InputStream.class, ProductImportMapping.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.readOnly()).isTrue();
    }

    @Test
    void negativePurchasePriceReturnsError() throws Exception {
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC")).thenReturn(Optional.empty());

        var preview = service.preview(workbookWithRow("ABC", null, "Producto Excel", "-1.00", null, null),
                mapping(false));

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).status())
                .isEqualTo(ProductImportPreviewRow.Status.ERROR);
        assertThat(preview.rows().get(0).errors())
                .contains("precioCompra no puede ser negativo");
    }

    @Test
    void optionalWholesalePriceBelowMinimumReturnsError() throws Exception {
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC")).thenReturn(Optional.empty());

        var preview = service.preview(
                workbookWithPriceColumns("ABC", "Producto Excel", "10.00", null, "0.00", null),
                mappingWithOptionalPrices(false));

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).status())
                .isEqualTo(ProductImportPreviewRow.Status.ERROR);
        assertThat(preview.rows().get(0).errors())
                .contains("precioMayorista debe ser mayor o igual a 0.01");
    }

    @Test
    void existingProductUpdateSalePriceValidatesNegativeValue() throws Exception {
        var product = product();
        var identifier = new ProductIdentifier(store.getId(), product.getId(),
                IdentifierType.CODIGO, "ABC");
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC"))
                .thenReturn(Optional.of(identifier));
        when(products.findById(product.getId())).thenReturn(Optional.of(product));

        var preview = service.preview(
                workbookWithPriceColumns("ABC", "Producto Excel", "10.00", "-1.00", null, null),
                mappingWithOptionalPrices(true));

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).status())
                .isEqualTo(ProductImportPreviewRow.Status.ERROR);
        assertThat(preview.rows().get(0).errors())
                .contains("precioVenta no puede ser negativo");
    }

    @Test
    void taxAboveOneHundredReturnsError() throws Exception {
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC")).thenReturn(Optional.empty());

        var preview = service.preview(
                workbookWithRow("ABC", null, "Producto Excel", "10.00", null, null, "101"),
                mappingWithTax());

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).status())
                .isEqualTo(ProductImportPreviewRow.Status.ERROR);
        assertThat(preview.rows().get(0).errors())
                .contains("impuesto debe estar entre 0 y 100");
    }

    @Test
    void codeAndBarcodeMatchingDifferentProductsReturnsError() throws Exception {
        var codeProductId = UUID.randomUUID();
        var barcodeProductId = UUID.randomUUID();
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC"))
                .thenReturn(Optional.of(new ProductIdentifier(store.getId(), codeProductId,
                        IdentifierType.CODIGO, "ABC")));
        when(identifiers.findByStoreIdAndValor(store.getId(), "123"))
                .thenReturn(Optional.of(new ProductIdentifier(store.getId(), barcodeProductId,
                        IdentifierType.CODIGO_BARRAS, "123")));

        var preview = service.preview(workbookWithRow("ABC", "123", "Producto Excel", "10.00", null, null),
                mapping(false));

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).status())
                .isEqualTo(ProductImportPreviewRow.Status.ERROR);
        assertThat(preview.rows().get(0).errors())
                .contains("codigo y codigoBarras pertenecen a productos distintos");
    }

    @Test
    void existingProductWithAllUpdateFlagsFalseDoesNotReadPrices() throws Exception {
        var product = Mockito.spy(product());
        var identifier = new ProductIdentifier(store.getId(), product.getId(),
                IdentifierType.CODIGO, "ABC");
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC"))
                .thenReturn(Optional.of(identifier));
        when(products.findById(product.getId())).thenReturn(Optional.of(product));

        var preview = service.preview(workbookWithRow("ABC", null, "Producto Excel", "10.00", "15.00", null),
                mapping(false));

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).status())
                .isEqualTo(ProductImportPreviewRow.Status.PRODUCT_ONLY);
        verify(product, never()).getPurchasePrice();
        verify(product, never()).getSalePrice();
        verify(product, never()).getWholesalePrice();
        verify(product, never()).getMemberPrice();
    }

    private static ByteArrayInputStream workbookWithRow(
            String code,
            String barcode,
            String name,
            String purchasePrice,
            String salePrice,
            String quantity) throws Exception {
        return workbookWithRow(code, barcode, name, purchasePrice, salePrice, quantity, null);
    }

    private static ByteArrayInputStream workbookWithRow(
            String code,
            String barcode,
            String name,
            String purchasePrice,
            String salePrice,
            String quantity,
            String tax) throws Exception {
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
            if (tax != null) {
                row.createCell(6).setCellValue(tax);
            }
            workbook.write(output);
            return new ByteArrayInputStream(output.toByteArray());
        }
    }

    private static ByteArrayInputStream workbookWithPriceColumns(
            String code,
            String name,
            String purchasePrice,
            String salePrice,
            String wholesalePrice,
            String memberPrice) throws Exception {
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var row = workbook.createSheet().createRow(0);
            if (code != null) {
                row.createCell(0).setCellValue(code);
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
            if (wholesalePrice != null) {
                row.createCell(7).setCellValue(wholesalePrice);
            }
            if (memberPrice != null) {
                row.createCell(8).setCellValue(memberPrice);
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

    private static ProductImportMapping mappingWithTax() {
        return new ProductImportMapping(
                "A",
                "B",
                "C",
                null,
                "D",
                "E",
                null,
                null,
                "G",
                "F",
                null,
                1,
                false,
                false,
                false,
                false,
                false,
                false);
    }

    private static ProductImportMapping mappingWithOptionalPrices(boolean updateSalePrice) {
        return new ProductImportMapping(
                "A",
                "B",
                "C",
                null,
                "D",
                "E",
                "H",
                "I",
                null,
                "F",
                null,
                1,
                false,
                false,
                false,
                updateSalePrice,
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
