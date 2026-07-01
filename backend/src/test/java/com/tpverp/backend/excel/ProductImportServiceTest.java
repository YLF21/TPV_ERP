package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.CatalogService;
import com.tpverp.backend.catalog.CatalogService.ProductRequest;
import com.tpverp.backend.catalog.Family;
import com.tpverp.backend.catalog.FamilyRepository;
import com.tpverp.backend.catalog.IdentifierType;
import com.tpverp.backend.catalog.PriceTier;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductIdentifier;
import com.tpverp.backend.catalog.ProductIdentifierRepository;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.StoreTax;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentCommand;
import com.tpverp.backend.document.DocumentService;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class ProductImportServiceTest {

    @Mock private CurrentOrganization organization;
    @Mock private ProductIdentifierRepository identifiers;
    @Mock private ProductRepository products;
    @Mock private CatalogService catalogService;
    @Mock private DocumentService documentService;
    @Mock private FamilyRepository families;
    @Mock private StoreTaxRepository taxes;
    @Mock private Authentication authentication;

    private ProductImportService service;
    private Store store;
    private Family generalFamily;
    private StoreTax defaultTax;

    @BeforeEach
    void setUp() {
        var company = new Company("B00000000", "Company", address());
        store = new Store(company, "Store", address(), "hash",
                "Atlantic/Canary", "EUR", "es-ES");
        generalFamily = Family.general(store.getId());
        defaultTax = new StoreTax(store.getId(), new BigDecimal("21.00"), true);
        service = new ProductImportService(organization, identifiers, products,
                catalogService, documentService, families, taxes);
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

    @Test
    void confirmNewProductWithQuantityCreatesDraftPurchaseDeliveryNote() throws Exception {
        var warehouseId = UUID.randomUUID();
        var supplierId = UUID.randomUUID();
        var saved = productWithCode("ABC");
        saved.replaceIdentifier(IdentifierType.CODIGO_BARRAS, "123");
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC")).thenReturn(Optional.empty());
        when(identifiers.findByStoreIdAndValor(store.getId(), "123")).thenReturn(Optional.empty());
        when(families.findByStoreIdAndPredeterminadaTrue(store.getId()))
                .thenReturn(Optional.of(generalFamily));
        when(taxes.findByStoreIdAndPredeterminadoTrue(store.getId()))
                .thenReturn(Optional.of(defaultTax));
        when(catalogService.createOrUpdateFromImport(any(), eq(null))).thenReturn(saved);
        when(documentService.createDeliveryNote(any(), eq(authentication)))
                .thenReturn(draftDocument(warehouseId, CommercialDocumentType.ALBARAN_COMPRA, supplierId));

        var result = service.confirm(
                workbookWithRow("ABC", "123", "Producto Excel", "10.00", "15.00", "2"),
                confirmRequest(mapping(false), warehouseId, supplierId,
                        CommercialDocumentType.ALBARAN_COMPRA),
                authentication);

        assertThat(result.getEstado()).isEqualTo(DocumentStatus.BORRADOR);
        var productCaptor = ArgumentCaptor.forClass(ProductRequest.class);
        verify(catalogService).createOrUpdateFromImport(productCaptor.capture(), eq(null));
        assertThat(productCaptor.getValue().familyId()).isEqualTo(generalFamily.getId());
        assertThat(productCaptor.getValue().subfamilyId()).isNull();
        assertThat(productCaptor.getValue().taxId()).isEqualTo(defaultTax.getId());
        assertThat(productCaptor.getValue().code()).isEqualTo("ABC");
        assertThat(productCaptor.getValue().barcode()).isEqualTo("123");
        assertThat(productCaptor.getValue().name()).isEqualTo("Producto Excel");
        assertThat(productCaptor.getValue().purchasePrice()).isEqualByComparingTo("10.00");
        assertThat(productCaptor.getValue().salePrice()).isEqualByComparingTo("15.00");

        var documentCaptor = ArgumentCaptor.forClass(DocumentCommand.class);
        verify(documentService).createDeliveryNote(documentCaptor.capture(), eq(authentication));
        assertThat(documentCaptor.getValue().tipo()).isEqualTo(CommercialDocumentType.ALBARAN_COMPRA);
        assertThat(documentCaptor.getValue().almacenId()).isEqualTo(warehouseId);
        assertThat(documentCaptor.getValue().proveedorId()).isEqualTo(supplierId);
        assertThat(documentCaptor.getValue().directo()).isFalse();
        assertThat(documentCaptor.getValue().lineas()).hasSize(1);
        var line = documentCaptor.getValue().lineas().get(0);
        assertThat(line.productoId()).isEqualTo(saved.getId());
        assertThat(line.cantidad()).isEqualTo(2);
        assertThat(line.precioUnitario()).isEqualByComparingTo("10.00");
        assertThat(line.porcentajeImpuesto()).isEqualByComparingTo("21.00");
    }

    @Test
    void confirmProductOnlyRowCreatesProductButNotDocumentLine() throws Exception {
        var warehouseId = UUID.randomUUID();
        var supplierId = UUID.randomUUID();
        var productOnly = productWithCode("ONLY");
        var lineProduct = productWithCode("LINE");
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(eq(store.getId()), any())).thenReturn(Optional.empty());
        when(families.findByStoreIdAndPredeterminadaTrue(store.getId()))
                .thenReturn(Optional.of(generalFamily));
        when(taxes.findByStoreIdAndPredeterminadoTrue(store.getId()))
                .thenReturn(Optional.of(defaultTax));
        when(catalogService.createOrUpdateFromImport(any(), eq(null)))
                .thenReturn(productOnly, lineProduct);
        when(documentService.createDeliveryNote(any(), eq(authentication)))
                .thenReturn(draftDocument(warehouseId, CommercialDocumentType.ALBARAN_COMPRA, supplierId));

        service.confirm(workbookWithRows(List.of(
                        rowValues("ONLY", null, "Producto sin cantidad", "5.00", null, null, null),
                        rowValues("LINE", null, "Producto con cantidad", "8.00", null, "3", null))),
                confirmRequest(mapping(false), warehouseId, supplierId,
                        CommercialDocumentType.ALBARAN_COMPRA),
                authentication);

        verify(catalogService, Mockito.times(2)).createOrUpdateFromImport(any(), eq(null));
        var documentCaptor = ArgumentCaptor.forClass(DocumentCommand.class);
        verify(documentService).createDeliveryNote(documentCaptor.capture(), eq(authentication));
        assertThat(documentCaptor.getValue().lineas())
                .extracting(line -> line.productoId())
                .containsExactly(lineProduct.getId());
    }

    @Test
    void confirmExistingProductUpdatesOnlyEnabledMappedFields() throws Exception {
        var warehouseId = UUID.randomUUID();
        var supplierId = UUID.randomUUID();
        var existing = productWithTax(defaultTax);
        existing.replaceIdentifier(IdentifierType.CODIGO, "ABC");
        existing.setPrice(PriceTier.MAYORISTA, new BigDecimal("11.00"));
        existing.setPrice(PriceTier.MEMBER, new BigDecimal("12.00"));
        var identifier = new ProductIdentifier(store.getId(), existing.getId(),
                IdentifierType.CODIGO, "ABC");
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC"))
                .thenReturn(Optional.of(identifier));
        when(products.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(taxes.findById(defaultTax.getId()))
                .thenReturn(Optional.of(defaultTax));
        when(catalogService.createOrUpdateFromImport(any(), eq(existing.getId()))).thenReturn(existing);
        when(documentService.createDeliveryNote(any(), eq(authentication)))
                .thenReturn(draftDocument(warehouseId, CommercialDocumentType.ALBARAN_COMPRA, supplierId));

        service.confirm(workbookWithRow("ABC", null, "Nombre Nuevo", "20.00", "30.00", "1"),
                confirmRequest(mapping(true), warehouseId, supplierId,
                        CommercialDocumentType.ALBARAN_COMPRA),
                authentication);

        var productCaptor = ArgumentCaptor.forClass(ProductRequest.class);
        verify(catalogService).createOrUpdateFromImport(productCaptor.capture(), eq(existing.getId()));
        assertThat(productCaptor.getValue().name()).isEqualTo("Nombre Nuevo");
        assertThat(productCaptor.getValue().purchasePrice()).isEqualByComparingTo("10.00");
        assertThat(productCaptor.getValue().salePrice()).isEqualByComparingTo("15.00");
        assertThat(productCaptor.getValue().wholesalePrice()).isEqualByComparingTo("11.00");
        assertThat(productCaptor.getValue().memberPrice()).isEqualByComparingTo("12.00");
    }

    @Test
    void confirmMissingSalePriceDefaultsNewProductSalePriceToZero() throws Exception {
        var warehouseId = UUID.randomUUID();
        var supplierId = UUID.randomUUID();
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC")).thenReturn(Optional.empty());
        when(families.findByStoreIdAndPredeterminadaTrue(store.getId()))
                .thenReturn(Optional.of(generalFamily));
        when(taxes.findByStoreIdAndPredeterminadoTrue(store.getId()))
                .thenReturn(Optional.of(defaultTax));
        when(catalogService.createOrUpdateFromImport(any(), eq(null))).thenReturn(productWithCode("ABC"));
        when(documentService.createDeliveryNote(any(), eq(authentication)))
                .thenReturn(draftDocument(warehouseId, CommercialDocumentType.ALBARAN_COMPRA, supplierId));

        service.confirm(workbookWithRow("ABC", null, "Producto Excel", "10.00", null, "1"),
                confirmRequest(mapping(false), warehouseId, supplierId,
                        CommercialDocumentType.ALBARAN_COMPRA),
                authentication);

        var productCaptor = ArgumentCaptor.forClass(ProductRequest.class);
        verify(catalogService).createOrUpdateFromImport(productCaptor.capture(), eq(null));
        assertThat(productCaptor.getValue().salePrice()).isEqualByComparingTo("0.00");
    }

    @Test
    void confirmBarcodeOnlyNewProductUsesBarcodeAsCode() throws Exception {
        var warehouseId = UUID.randomUUID();
        var supplierId = UUID.randomUUID();
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "777")).thenReturn(Optional.empty());
        when(families.findByStoreIdAndPredeterminadaTrue(store.getId()))
                .thenReturn(Optional.of(generalFamily));
        when(taxes.findByStoreIdAndPredeterminadoTrue(store.getId()))
                .thenReturn(Optional.of(defaultTax));
        when(catalogService.createOrUpdateFromImport(any(), eq(null))).thenReturn(productWithCode("777"));
        when(documentService.createDeliveryNote(any(), eq(authentication)))
                .thenReturn(draftDocument(warehouseId, CommercialDocumentType.ALBARAN_COMPRA, supplierId));

        service.confirm(workbookWithRow(null, "777", "Producto Excel", "10.00", null, "1"),
                confirmRequest(mapping(false), warehouseId, supplierId,
                        CommercialDocumentType.ALBARAN_COMPRA),
                authentication);

        var productCaptor = ArgumentCaptor.forClass(ProductRequest.class);
        verify(catalogService).createOrUpdateFromImport(productCaptor.capture(), eq(null));
        assertThat(productCaptor.getValue().code()).isEqualTo("777");
        assertThat(productCaptor.getValue().barcode()).isEqualTo("777");
    }

    @Test
    void confirmPreviewErrorThrowsAndDoesNotCreateDocument() {
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(
                workbookWithRow("ABC", null, "Producto Excel", "-1.00", null, "1"),
                confirmRequest(mapping(false), UUID.randomUUID(), UUID.randomUUID(),
                        CommercialDocumentType.ALBARAN_COMPRA),
                authentication))
                .isInstanceOf(IllegalArgumentException.class);

        verify(documentService, never()).createDeliveryNote(any(), any());
        verify(documentService, never()).createInvoice(any(), any());
    }

    @Test
    void confirmExistingProductWithBlankTaxPreservesProductTax() throws Exception {
        var warehouseId = UUID.randomUUID();
        var supplierId = UUID.randomUUID();
        var productTax = new StoreTax(store.getId(), new BigDecimal("7.00"), false);
        var existing = productWithTax(productTax);
        existing.replaceIdentifier(IdentifierType.CODIGO, "ABC");
        var identifier = new ProductIdentifier(store.getId(), existing.getId(),
                IdentifierType.CODIGO, "ABC");
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC"))
                .thenReturn(Optional.of(identifier));
        when(products.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(taxes.findById(productTax.getId())).thenReturn(Optional.of(productTax));
        when(catalogService.createOrUpdateFromImport(any(), eq(existing.getId()))).thenReturn(existing);
        when(documentService.createDeliveryNote(any(), eq(authentication)))
                .thenReturn(draftDocument(warehouseId, CommercialDocumentType.ALBARAN_COMPRA, supplierId));

        service.confirm(workbookWithRow("ABC", null, "Nombre Nuevo", "20.00", null, "1"),
                confirmRequest(mapping(true), warehouseId, supplierId,
                        CommercialDocumentType.ALBARAN_COMPRA),
                authentication);

        var productCaptor = ArgumentCaptor.forClass(ProductRequest.class);
        verify(catalogService).createOrUpdateFromImport(productCaptor.capture(), eq(existing.getId()));
        assertThat(productCaptor.getValue().taxId()).isEqualTo(productTax.getId());
        var documentCaptor = ArgumentCaptor.forClass(DocumentCommand.class);
        verify(documentService).createDeliveryNote(documentCaptor.capture(), eq(authentication));
        assertThat(documentCaptor.getValue().lineas().get(0).porcentajeImpuesto())
                .isEqualByComparingTo("7.00");
    }

    @Test
    void nonexistentTaxPercentageReturnsPreviewErrorAndConfirmDoesNotCreateDocument() throws Exception {
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC")).thenReturn(Optional.empty());
        when(taxes.findByStoreIdAndPorcentaje(store.getId(), new BigDecimal("8.00")))
                .thenReturn(Optional.empty());

        var preview = service.preview(
                workbookWithRow("ABC", null, "Producto Excel", "10.00", null, "1", "8"),
                mappingWithTax());

        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).status())
                .isEqualTo(ProductImportPreviewRow.Status.ERROR);
        assertThat(preview.rows().get(0).errors())
                .contains("impuesto no encontrado");

        assertThatThrownBy(() -> service.confirm(
                workbookWithRow("ABC", null, "Producto Excel", "10.00", null, "1", "8"),
                confirmRequest(mappingWithTax(), UUID.randomUUID(), UUID.randomUUID(),
                        CommercialDocumentType.ALBARAN_COMPRA),
                authentication))
                .isInstanceOf(IllegalArgumentException.class);
        verify(documentService, never()).createDeliveryNote(any(), any());
    }

    @Test
    void confirmExistingProductMatchedByBarcodePreservesExistingIdentifiers() throws Exception {
        var warehouseId = UUID.randomUUID();
        var supplierId = UUID.randomUUID();
        var existing = productWithTax(defaultTax);
        existing.replaceIdentifier(IdentifierType.CODIGO, "OLD");
        existing.replaceIdentifier(IdentifierType.CODIGO_BARRAS, "777");
        var identifier = new ProductIdentifier(store.getId(), existing.getId(),
                IdentifierType.CODIGO_BARRAS, "777");
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "NEW")).thenReturn(Optional.empty());
        when(identifiers.findByStoreIdAndValor(store.getId(), "777"))
                .thenReturn(Optional.of(identifier));
        when(products.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(taxes.findById(existing.getTaxId())).thenReturn(Optional.of(defaultTax));
        when(catalogService.createOrUpdateFromImport(any(), eq(existing.getId()))).thenReturn(existing);
        when(documentService.createDeliveryNote(any(), eq(authentication)))
                .thenReturn(draftDocument(warehouseId, CommercialDocumentType.ALBARAN_COMPRA, supplierId));

        service.confirm(workbookWithRow("NEW", "777", "Nombre Nuevo", "20.00", null, "1"),
                confirmRequest(mapping(true), warehouseId, supplierId,
                        CommercialDocumentType.ALBARAN_COMPRA),
                authentication);

        var productCaptor = ArgumentCaptor.forClass(ProductRequest.class);
        verify(catalogService).createOrUpdateFromImport(productCaptor.capture(), eq(existing.getId()));
        assertThat(productCaptor.getValue().code()).isEqualTo("OLD");
        assertThat(productCaptor.getValue().barcode()).isEqualTo("777");
    }

    @Test
    void confirmExistingProductUsesExcelPurchasePriceForLineWithoutUpdatingCatalogPrice() throws Exception {
        var warehouseId = UUID.randomUUID();
        var supplierId = UUID.randomUUID();
        var existing = productWithTax(defaultTax);
        existing.replaceIdentifier(IdentifierType.CODIGO, "ABC");
        var identifier = new ProductIdentifier(store.getId(), existing.getId(),
                IdentifierType.CODIGO, "ABC");
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC"))
                .thenReturn(Optional.of(identifier));
        when(products.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(taxes.findById(existing.getTaxId())).thenReturn(Optional.of(defaultTax));
        when(catalogService.createOrUpdateFromImport(any(), eq(existing.getId()))).thenReturn(existing);
        when(documentService.createDeliveryNote(any(), eq(authentication)))
                .thenReturn(draftDocument(warehouseId, CommercialDocumentType.ALBARAN_COMPRA, supplierId));

        service.confirm(workbookWithRow("ABC", null, "Nombre Nuevo", "20.00", null, "1"),
                confirmRequest(mapping(true), warehouseId, supplierId,
                        CommercialDocumentType.ALBARAN_COMPRA),
                authentication);

        var productCaptor = ArgumentCaptor.forClass(ProductRequest.class);
        verify(catalogService).createOrUpdateFromImport(productCaptor.capture(), eq(existing.getId()));
        assertThat(productCaptor.getValue().purchasePrice()).isEqualByComparingTo("10.00");
        var documentCaptor = ArgumentCaptor.forClass(DocumentCommand.class);
        verify(documentService).createDeliveryNote(documentCaptor.capture(), eq(authentication));
        assertThat(documentCaptor.getValue().lineas().get(0).precioUnitario())
                .isEqualByComparingTo("20.00");
    }

    @Test
    void confirmPurchaseInvoiceCallsCreateInvoice() throws Exception {
        var warehouseId = UUID.randomUUID();
        var supplierId = UUID.randomUUID();
        var saved = productWithCode("ABC");
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findByStoreIdAndValor(store.getId(), "ABC")).thenReturn(Optional.empty());
        when(families.findByStoreIdAndPredeterminadaTrue(store.getId()))
                .thenReturn(Optional.of(generalFamily));
        when(taxes.findByStoreIdAndPredeterminadoTrue(store.getId()))
                .thenReturn(Optional.of(defaultTax));
        when(catalogService.createOrUpdateFromImport(any(), eq(null))).thenReturn(saved);
        when(documentService.createInvoice(any(), eq(authentication)))
                .thenReturn(draftDocument(warehouseId, CommercialDocumentType.FACTURA_COMPRA, supplierId));

        service.confirm(workbookWithRow("ABC", null, "Producto Excel", "10.00", null, "1"),
                confirmRequest(mapping(false), warehouseId, supplierId,
                        CommercialDocumentType.FACTURA_COMPRA),
                authentication);

        var documentCaptor = ArgumentCaptor.forClass(DocumentCommand.class);
        verify(documentService).createInvoice(documentCaptor.capture(), eq(authentication));
        assertThat(documentCaptor.getValue().tipo()).isEqualTo(CommercialDocumentType.FACTURA_COMPRA);
        verify(documentService, never()).createDeliveryNote(any(), any());
    }

    @Test
    void confirmNonPurchaseDocumentTypeThrows() {
        assertThatThrownBy(() -> service.confirm(
                workbookWithRow("ABC", null, "Producto Excel", "10.00", null, "1"),
                confirmRequest(mapping(false), UUID.randomUUID(), UUID.randomUUID(),
                        CommercialDocumentType.ALBARAN_VENTA),
                authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tipo de documento de importacion no permitido");
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

    private static ByteArrayInputStream workbookWithRows(List<String[]> rows) throws Exception {
        try (var workbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                var row = sheet.createRow(rowIndex);
                var values = rows.get(rowIndex);
                for (int cellIndex = 0; cellIndex < values.length; cellIndex++) {
                    if (values[cellIndex] != null) {
                        row.createCell(cellIndex).setCellValue(values[cellIndex]);
                    }
                }
            }
            workbook.write(output);
            return new ByteArrayInputStream(output.toByteArray());
        }
    }

    private static String[] rowValues(
            String code,
            String barcode,
            String name,
            String purchasePrice,
            String salePrice,
            String quantity,
            String tax) {
        return new String[] {code, barcode, name, purchasePrice, salePrice, quantity, tax};
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

    private Product productWithTax(StoreTax tax) {
        var product = new Product(store.getId(), UUID.randomUUID(), null, tax.getId(),
                "Producto Actual", null, new BigDecimal("10.00"), true);
        product.setPrice(PriceTier.VENTA, new BigDecimal("15.00"));
        return product;
    }

    private Product productWithCode(String code) {
        var product = product();
        product.replaceIdentifier(IdentifierType.CODIGO, code);
        return product;
    }

    private ProductImportConfirmRequest confirmRequest(
            ProductImportMapping mapping,
            UUID warehouseId,
            UUID supplierId,
            CommercialDocumentType type) {
        return new ProductImportConfirmRequest(
                mapping, warehouseId, supplierId, "EXT-1", type, LocalDate.of(2026, 7, 1));
    }

    private CommercialDocument draftDocument(
            UUID warehouseId,
            CommercialDocumentType type,
            UUID supplierId) {
        var document = new CommercialDocument(
                store.getId(), warehouseId, type, LocalDate.of(2026, 7, 1),
                UUID.randomUUID(), BigDecimal.ZERO);
        return document;
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
