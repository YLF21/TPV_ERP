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
                "Producto", null, BigDecimal.ZERO, true);
        warehouse = Warehouse.general(store.getId());
        supplier = new Supplier(
                store.getEmpresa(), "Proveedor SL", null, DocumentType.NIF,
                "B12345678", null, null, null, null);
        lenient().when(organization.currentStore()).thenReturn(store);
        lenient().when(organization.currentCompany()).thenReturn(store.getEmpresa());
        lenient().when(organization.currentUser(any())).thenReturn(user);
        lenient().when(inputs.save(any())).thenAnswer(call -> call.getArgument(0));
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
                        List.of(new WarehouseInputLineCommand(product.getId(), 4))),
                authentication());

        assertThat(input.getStatus()).isEqualTo(WarehouseInputStatus.BORRADOR);
        assertThat(input.getSupplierId()).isEqualTo(supplier.getId());
        assertThat(input.getLines()).singleElement()
                .extracting(WarehouseInputLine::getQuantity).isEqualTo(4);
    }

    @Test
    void confirmsWithAnnualNumberAndAddsStock() {
        var input = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 8), user.getId());
        input.replace(
                supplier.getId(), "Proveedor SL", "Compra",
                List.of(new WarehouseInputLineCommand(product.getId(), 5)));
        var stock = new StockLevel(product.getId(), warehouse.getId());
        when(inputs.findById(input.getId())).thenReturn(Optional.of(input));
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(counters.findByTiendaIdAndTipoAndPeriodo(store.getId(), "ENT", "2026"))
                .thenReturn(Optional.empty());
        when(stockLevels.findByProductIdAndWarehouseId(
                product.getId(), warehouse.getId())).thenReturn(Optional.of(stock));

        var confirmed = service.confirm(input.getId(), authentication());

        assertThat(confirmed.getNumber()).isEqualTo("ENT-2026-000001");
        assertThat(stock.getQuantity()).isEqualByComparingTo("5");
        var movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(movements).save(movement.capture());
        assertThat(movement.getValue().getType()).isEqualTo(StockMovementType.ENTRADA_ALMACEN);
        assertThat(movement.getValue().getQuantity()).isEqualByComparingTo("5");
        verify(syncOutbox).enqueue(any());
    }

    @Test
    void rejectsSecondConfirmation() {
        var input = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 8), user.getId());
        input.replace(
                supplier.getId(), "Proveedor SL", "Compra",
                List.of(new WarehouseInputLineCommand(product.getId(), 1)));
        when(inputs.findById(input.getId())).thenReturn(Optional.of(input));
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(movements.existsByWarehouseInputId(input.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.confirm(input.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entrada ya tiene movimientos");
    }

    @Test
    void rejectsConfirmationThatWouldLeaveNegativeStockWithoutNumberingOrMovements() {
        var input = new WarehouseInput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 7, 8), user.getId());
        input.replace(
                supplier.getId(), "Proveedor SL", "Reposicion parcial",
                List.of(new WarehouseInputLineCommand(product.getId(), 5)));
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
