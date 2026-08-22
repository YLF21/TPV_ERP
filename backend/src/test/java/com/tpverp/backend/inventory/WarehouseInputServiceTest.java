package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.DocumentCounterRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.sync.SyncOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class WarehouseInputServiceTest {

    @Mock private WarehouseInputRepository inputs;
    @Mock private DocumentCounterRepository counters;
    @Mock private StockLevelRepository stockLevels;
    @Mock private StockSettingsRepository settings;
    @Mock private StockMovementRepository movements;
    @Mock private CurrentOrganization organization;
    @Mock private ProductRepository products;
    @Mock private WarehouseRepository warehouses;
    @Mock private SupplierRepository suppliers;
    @Mock private SyncOutboxService syncOutbox;

    private WarehouseInputService service;
    private Store store;
    private UserAccount user;
    private Product product;
    private Warehouse warehouse;
    private Supplier supplier;

    @BeforeEach
    void setUp() {
        service = new WarehouseInputService(
                inputs, counters, stockLevels, settings, movements, organization, products,
                warehouses, suppliers, new StockMovementSyncPublisher(syncOutbox),
                Clock.fixed(Instant.parse("2026-07-08T10:00:00Z"), ZoneOffset.UTC));
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        store = new Store(
                new Company("B00000000", "Company", address),
                "Store", address, "hash", "Atlantic/Canary", "EUR", "es-ES");
        var role = new Role(store, "ADMIN");
        user = new UserAccount(store, "ADMIN", "hash", role);
        product = new Product(
                store.getId(), UUID.randomUUID(), null, UUID.randomUUID(),
                "Producto", null, new BigDecimal("4.20"), true);
        warehouse = Warehouse.general(store.getId());
        supplier = new Supplier(
                store.getEmpresa(), "Proveedor SL", null, DocumentType.NIF,
                "B12345678", null, null, null, null);
        lenient().when(organization.currentStore()).thenReturn(store);
        lenient().when(organization.currentCompany()).thenReturn(store.getEmpresa());
        lenient().when(organization.currentUser(any())).thenReturn(user);
        lenient().when(inputs.save(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(inputs.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(movements.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createsEditableDraftWithSupplierAndLines() {
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(suppliers.findByIdAndCompanyId(supplier.getId(), store.getEmpresa().getId()))
                .thenReturn(Optional.of(supplier));

        var input = service.create(
                new WarehouseInputCommand(
                        warehouse.getId(), LocalDate.of(2026, 7, 8), supplier.getId(),
                        "Proveedor SL", "Compra inicial",
                        List.of(new WarehouseInputLineCommand(
                                product.getId(), BigDecimal.valueOf(4), null,
                                BigDecimal.ZERO, false, product.getName())),
                        new WarehouseExcelImportMetadata(
                                "productos.xlsx",
                                List.of(new WarehouseExcelImportMetadata.Formula(
                                        "I2", "E2*2.5", "10.25")))),
                authentication());

        assertThat(input.getStatus()).isEqualTo(WarehouseInputStatus.BORRADOR);
        assertThat(input.getSupplierId()).isEqualTo(supplier.getId());
        assertThat(input.getLines()).singleElement()
                .extracting(WarehouseInputLine::getQuantity).isEqualTo(new BigDecimal("4.000"));
        assertThat(input.getLines()).singleElement()
                .extracting(WarehouseInputLine::getPurchaseUnitPrice)
                .isEqualTo(new BigDecimal("4.20"));
        assertThat(input.getExcelImport().formulas()).singleElement()
                .extracting(WarehouseExcelImportMetadata.Formula::formula)
                .isEqualTo("E2*2.5");
    }

    @Test
    void resolvesNonOverriddenLinePriceFromSelectedSourceOnTheServer() {
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(suppliers.findByIdAndCompanyId(supplier.getId(), store.getEmpresa().getId()))
                .thenReturn(Optional.of(supplier));

        var input = service.create(
                new WarehouseInputCommand(
                        warehouse.getId(), LocalDate.of(2026, 7, 8), supplier.getId(),
                        "Proveedor SL", "Compra", "Factura",
                        WarehouseInputDocumentType.FACTURA_ENTRADA,
                        WarehouseInputPriceSource.PURCHASE, BigDecimal.ZERO, List.of(),
                        List.of(new WarehouseInputLineCommand(
                                product.getId(), BigDecimal.ONE, new BigDecimal("99.00"),
                                BigDecimal.ZERO, false, product.getName())), null),
                authentication());

        assertThat(input.getLines()).singleElement()
                .satisfies(line -> assertThat(line.getPurchaseUnitPrice()).isEqualByComparingTo("4.20"));
    }

    @Test
    void snapshotsPurchasePriceForEveryRepeatedProductLine() {
        var input = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 8), user.getId());
        input.replace(
                supplier.getId(), "Proveedor SL", "Compra",
                List.of(
                        new WarehouseInputLineCommand(
                                product.getId(), BigDecimal.valueOf(2), null,
                                BigDecimal.ZERO, false, product.getName()),
                        new WarehouseInputLineCommand(
                                product.getId(), BigDecimal.valueOf(3), null,
                                BigDecimal.ZERO, false, product.getName())));

        input.snapshotPurchasePrices(Map.of(product.getId(), new BigDecimal("4.20")));

        assertThat(input.getLines())
                .extracting(WarehouseInputLine::getPurchaseTotal)
                .containsExactly(new BigDecimal("8.40"), new BigDecimal("12.60"));
    }

    @Test
    void confirmedInputCannotReplaceItsHistoricalPurchasePrice() {
        var input = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 8), user.getId());
        input.replace(
                supplier.getId(), "Proveedor SL", "Compra",
                List.of(new WarehouseInputLineCommand(
                        product.getId(), BigDecimal.ONE, null,
                        BigDecimal.ZERO, false, product.getName())));
        input.snapshotPurchasePrices(Map.of(product.getId(), new BigDecimal("4.20")));
        input.confirm("ENT-2026-000001", user.getId(), Instant.parse("2026-07-08T10:00:00Z"));

        assertThatThrownBy(() -> input.snapshotPurchasePrices(
                Map.of(product.getId(), new BigDecimal("99.00"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inmutable");
        assertThat(input.getLines()).singleElement()
                .extracting(WarehouseInputLine::getPurchaseUnitPrice)
                .isEqualTo(new BigDecimal("4.20"));
    }

    @Test
    void confirmsWithAnnualNumberAndAddsStock() {
        var input = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 8), user.getId());
        input.replace(
                supplier.getId(), "Proveedor SL", "Compra",
                List.of(new WarehouseInputLineCommand(
                        product.getId(), new BigDecimal("5.000"),
                        new BigDecimal("4.20"), BigDecimal.ZERO, false, product.getName())));
        var stock = new StockLevel(product.getId(), warehouse.getId());
        when(inputs.findById(input.getId())).thenReturn(Optional.of(input));
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(counters.findByTiendaIdAndTipoAndPeriodo(store.getId(), "ENT", "2026"))
                .thenReturn(Optional.empty());
        when(inputs.findByStoreIdAndNumero(store.getId(), "ENT-2026-000001"))
                .thenReturn(Optional.empty());
        when(stockLevels.findByProductIdAndWarehouseId(
                product.getId(), warehouse.getId())).thenReturn(Optional.of(stock));

        var confirmed = service.confirm(input.getId(), authentication());

        assertThat(confirmed.getNumber()).isEqualTo("ENT-2026-000001");
        assertThat(confirmed.getLines()).singleElement()
                .satisfies(line -> {
                    assertThat(line.getPurchaseUnitPrice()).isEqualByComparingTo("4.20");
                    assertThat(line.getPurchaseTotal()).isEqualByComparingTo("21.00");
                });
        assertThat(stock.getQuantity()).isEqualByComparingTo("5");
        var movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(movements).save(movement.capture());
        assertThat(movement.getValue().getType()).isEqualTo(StockMovementType.ENTRADA_ALMACEN);
        assertThat(movement.getValue().getQuantity()).isEqualByComparingTo("5");
        verify(syncOutbox).enqueue(any());
    }

    @Test
    void skipsExistingAnnualNumberWhenCounterIsStale() {
        var input = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 8), user.getId());
        input.replace(
                supplier.getId(), "Proveedor SL", "Compra",
                List.of(new WarehouseInputLineCommand(
                        product.getId(), new BigDecimal("5.000"),
                        new BigDecimal("4.20"), BigDecimal.ZERO, false, product.getName())));
        var existing = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 1), user.getId());
        var stock = new StockLevel(product.getId(), warehouse.getId());
        when(inputs.findById(input.getId())).thenReturn(Optional.of(input));
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(counters.findByTiendaIdAndTipoAndPeriodo(store.getId(), "ENT", "2026"))
                .thenReturn(Optional.empty());
        when(inputs.findByStoreIdAndNumero(store.getId(), "ENT-2026-000001"))
                .thenReturn(Optional.of(existing));
        when(inputs.findByStoreIdAndNumero(store.getId(), "ENT-2026-000002"))
                .thenReturn(Optional.empty());
        when(stockLevels.findByProductIdAndWarehouseId(
                product.getId(), warehouse.getId())).thenReturn(Optional.of(stock));

        var confirmed = service.confirm(input.getId(), authentication());

        assertThat(confirmed.getNumber()).isEqualTo("ENT-2026-000002");
        assertThat(stock.getQuantity()).isEqualByComparingTo("5");
    }

    @Test
    void rejectsSecondConfirmation() {
        var input = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 8), user.getId());
        input.replace(
                supplier.getId(), "Proveedor SL", "Compra",
                List.of(new WarehouseInputLineCommand(
                        product.getId(), BigDecimal.ONE, null,
                        BigDecimal.ZERO, false, product.getName())));
        when(inputs.findById(input.getId())).thenReturn(Optional.of(input));
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(movements.existsByWarehouseInputId(input.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.confirm(input.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entrada ya tiene movimientos");
    }

    @Test
    void requiresSupplierForIncomingInvoice() {
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));

        var command = new WarehouseInputCommand(
                warehouse.getId(), LocalDate.of(2026, 7, 8), null,
                "Origen libre", "F-2026-15", "Factura directa",
                WarehouseInputDocumentType.FACTURA_ENTRADA,
                WarehouseInputPriceSource.PURCHASE, BigDecimal.ZERO, List.of(),
                List.of(new WarehouseInputLineCommand(
                        product.getId(), BigDecimal.ONE, null,
                        BigDecimal.ZERO, false, product.getName())), null);

        assertThatThrownBy(() -> service.create(command, authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("necesita proveedor");
        verify(inputs, never()).save(any());
    }

    @Test
    void confirmsDirectIncomingInvoiceAndAddsStockOnce() {
        var invoice = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 8), user.getId(),
                WarehouseInputDocumentType.FACTURA_ENTRADA);
        invoice.replace(
                supplier.getId(), "Proveedor SL", "F-15", "Factura directa",
                WarehouseInputPriceSource.PURCHASE, new BigDecimal("5.00"), List.of(),
                List.of(new WarehouseInputLineCommand(
                        product.getId(), new BigDecimal("2.500"),
                        new BigDecimal("4.20"), new BigDecimal("10.00"), true,
                        product.getName())), null);
        var stock = new StockLevel(product.getId(), warehouse.getId());
        when(inputs.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(counters.findByTiendaIdAndTipoAndPeriodo(store.getId(), "FE", "2026"))
                .thenReturn(Optional.empty());
        when(inputs.findByStoreIdAndNumero(store.getId(), "FE-2026-000001"))
                .thenReturn(Optional.empty());
        when(stockLevels.findByProductIdAndWarehouseId(product.getId(), warehouse.getId()))
                .thenReturn(Optional.of(stock));

        var confirmed = service.confirm(invoice.getId(), authentication());

        assertThat(confirmed.getNumber()).isEqualTo("FE-2026-000001");
        assertThat(confirmed.getSubtotal()).isEqualByComparingTo("9.45");
        assertThat(confirmed.getTotal()).isEqualByComparingTo("8.98");
        assertThat(stock.getQuantity()).isEqualByComparingTo("2.500");
        var movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(movements).save(movement.capture());
        assertThat(movement.getValue().getType()).isEqualTo(StockMovementType.FACTURA_ENTRADA);
    }

    @Test
    void confirmsLinkedInvoiceWithoutDuplicatingDeliveryNoteStock() {
        var deliveryNote = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 7), user.getId(),
                WarehouseInputDocumentType.ALBARAN_ENTRADA);
        deliveryNote.replace(
                supplier.getId(), "Proveedor SL", "A-7", "Entrega",
                WarehouseInputPriceSource.PURCHASE, BigDecimal.ZERO, List.of(),
                List.of(new WarehouseInputLineCommand(
                        product.getId(), new BigDecimal("3.000"),
                        new BigDecimal("4.20"), BigDecimal.ZERO, false,
                        product.getName())), null);
        deliveryNote.confirm("AE-2026-000001", user.getId(), Instant.parse("2026-07-07T10:00:00Z"));
        var invoice = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 8), user.getId(),
                WarehouseInputDocumentType.FACTURA_ENTRADA);
        invoice.replace(
                supplier.getId(), "Proveedor SL", "F-16", "Factura vinculada",
                WarehouseInputPriceSource.PURCHASE, BigDecimal.ZERO, List.of(deliveryNote.getId()),
                List.of(new WarehouseInputLineCommand(
                        product.getId(), new BigDecimal("3.000"),
                        new BigDecimal("4.10"), BigDecimal.ZERO, true,
                        product.getName())), null);
        when(inputs.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(inputs.findByIdAndStoreId(deliveryNote.getId(), store.getId()))
                .thenReturn(Optional.of(deliveryNote));
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(counters.findByTiendaIdAndTipoAndPeriodo(store.getId(), "FE", "2026"))
                .thenReturn(Optional.empty());
        when(inputs.findByStoreIdAndNumero(store.getId(), "FE-2026-000001"))
                .thenReturn(Optional.empty());

        var confirmed = service.confirm(invoice.getId(), authentication());

        assertThat(confirmed.getNumber()).isEqualTo("FE-2026-000001");
        verify(stockLevels, never()).save(any());
        verify(movements, never()).save(any());
        verify(syncOutbox, never()).enqueue(any());
    }

    @Test
    void rejectsDeliveryNoteAlreadyLinkedToAnotherInvoice() {
        var deliveryNote = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 7), user.getId(),
                WarehouseInputDocumentType.ALBARAN_ENTRADA);
        deliveryNote.replace(
                supplier.getId(), "Proveedor SL", "A-7", "Entrega",
                WarehouseInputPriceSource.PURCHASE, BigDecimal.ZERO, List.of(),
                List.of(new WarehouseInputLineCommand(
                        product.getId(), BigDecimal.ONE, new BigDecimal("4.20"),
                        BigDecimal.ZERO, false, product.getName())), null);
        deliveryNote.confirm("AE-2026-000001", user.getId(), Instant.parse("2026-07-07T10:00:00Z"));
        var invoice = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 8), user.getId(),
                WarehouseInputDocumentType.FACTURA_ENTRADA);
        invoice.replace(
                supplier.getId(), "Proveedor SL", "F-17", "Factura vinculada",
                WarehouseInputPriceSource.PURCHASE, BigDecimal.ZERO, List.of(deliveryNote.getId()),
                List.of(new WarehouseInputLineCommand(
                        product.getId(), BigDecimal.ONE, new BigDecimal("4.20"),
                        BigDecimal.ZERO, false, product.getName())), null);
        when(inputs.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(inputs.findByIdAndStoreId(deliveryNote.getId(), store.getId()))
                .thenReturn(Optional.of(deliveryNote));
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(inputs.existsOtherInvoiceForDeliveryNote(invoice.getId(), deliveryNote.getId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.confirm(invoice.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya esta vinculado");
        verify(counters, never()).save(any());
        verify(movements, never()).save(any());
    }

    @Test
    void rejectsConfirmationThatWouldLeaveNegativeStockWithoutNumberingOrMovements() {
        var input = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 8), user.getId());
        input.replace(
                supplier.getId(), "Proveedor SL", "Reposicion parcial",
                List.of(new WarehouseInputLineCommand(
                        product.getId(), BigDecimal.valueOf(5), null,
                        BigDecimal.ZERO, false, product.getName())));
        var stock = StockLevel.snapshot(
                product.getId(), warehouse.getId(), new BigDecimal("-10.000"));
        var policy = new StockSettings(store.getId(), warehouse.getId());
        policy.update(warehouse.getId(), false, StockSettings.DEFAULT_MINIMUM_STOCK, true);
        when(inputs.findById(input.getId())).thenReturn(Optional.of(input));
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(settings.findById(store.getId())).thenReturn(Optional.of(policy));
        when(stockLevels.findByProductIdAndWarehouseIdForUpdate(
                product.getId(), warehouse.getId())).thenReturn(Optional.of(stock));

        assertThatThrownBy(() -> service.confirm(input.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no permite stock negativo");

        assertThat(input.getStatus()).isEqualTo(WarehouseInputStatus.BORRADOR);
        assertThat(stock.getQuantity()).isEqualByComparingTo("-10.000");
        verify(counters, never()).save(any());
        verify(movements, never()).save(any());
        verify(inputs, never()).save(any());
        verify(syncOutbox, never()).enqueue(any());
    }

    private UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("ADMIN", "n/a");
    }
}
