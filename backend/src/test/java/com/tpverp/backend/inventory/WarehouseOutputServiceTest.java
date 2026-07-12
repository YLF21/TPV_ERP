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
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.document.DocumentCounterRepository;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.sync.SyncOutboxService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class WarehouseOutputServiceTest {

    @Mock private WarehouseOutputRepository outputs;
    @Mock private DocumentCounterRepository counters;
    @Mock private StockLevelRepository stockLevels;
    @Mock private StockSettingsRepository settings;
    @Mock private StockMovementRepository movements;
    @Mock private CurrentOrganization organization;
    @Mock private ProductRepository products;
    @Mock private WarehouseRepository warehouses;
    @Mock private SyncOutboxService syncOutbox;

    private WarehouseOutputService service;
    private Store store;
    private UserAccount user;
    private Product product;
    private Warehouse warehouse;

    @BeforeEach
    void setUp() {
        service = new WarehouseOutputService(
                outputs, counters, stockLevels, settings, movements, organization, products,
                warehouses, new StockMovementSyncPublisher(syncOutbox), Clock.fixed(
                        Instant.parse("2026-06-09T10:00:00Z"), ZoneOffset.UTC));
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
                "Producto", null, java.math.BigDecimal.ZERO, true);
        warehouse = Warehouse.general(store.getId());
        lenient().when(organization.currentStore()).thenReturn(store);
        lenient().when(organization.currentCompany()).thenReturn(store.getEmpresa());
        lenient().when(organization.currentUser(any())).thenReturn(user);
        lenient().when(outputs.save(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(movements.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createsEditableDraft() {
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));

        var output = service.create(
                new WarehouseOutputCommand(
                        warehouse.getId(), LocalDate.of(2026, 6, 9), "TALLER",
                        "Consumo interno",
                        List.of(new WarehouseOutputLineCommand(product.getId(), 2))),
                authentication());

        assertThat(output.getStatus()).isEqualTo(WarehouseOutputStatus.BORRADOR);
        assertThat(output.getLines()).singleElement()
                .extracting(WarehouseOutputLine::getQuantity).isEqualTo(2);
    }

    @Test
    void confirmsWithAnnualNumberAndRemovesStock() {
        var output = new WarehouseOutput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 6, 9), user.getId());
        output.replace(
                "TALLER", "Consumo",
                List.of(new WarehouseOutputLineCommand(product.getId(), 3)));
        var stock = new StockLevel(product.getId(), warehouse.getId());
        when(outputs.findById(output.getId())).thenReturn(Optional.of(output));
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(counters.findByTiendaIdAndTipoAndPeriodo(store.getId(), "SAL", "2026"))
                .thenReturn(Optional.empty());
        when(stockLevels.findByProductIdAndWarehouseId(
                product.getId(), warehouse.getId())).thenReturn(Optional.of(stock));

        var confirmed = service.confirm(output.getId(), authentication());

        assertThat(confirmed.getNumber()).isEqualTo("SAL-2026-000001");
        assertThat(stock.getQuantity()).isEqualByComparingTo("-3");
        org.mockito.Mockito.verify(syncOutbox).enqueue(any());
    }

    @Test
    void rejectsConfirmationWhenWarehouseWasDeactivated() {
        var secondary = new Warehouse(store.getId(), "SECUNDARIO");
        secondary.deactivate(0);
        var output = new WarehouseOutput(
                store.getId(), secondary.getId(), LocalDate.of(2026, 6, 9), user.getId());
        output.replace(
                "TALLER", "Consumo",
                List.of(new WarehouseOutputLineCommand(product.getId(), 1)));
        when(outputs.findById(output.getId())).thenReturn(Optional.of(output));
        when(warehouses.findById(secondary.getId())).thenReturn(Optional.of(secondary));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.confirm(output.getId(), authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.warehouse.not_available_for_store");
    }

    @Test
    void rejectsCumulativeNegativeOutputBeforeNumberingOrAnyMovement() {
        var output = new WarehouseOutput(
                store.getId(), warehouse.getId(), LocalDate.of(2026, 6, 9), user.getId());
        output.replace(
                "TALLER", "Consumo",
                List.of(
                        new WarehouseOutputLineCommand(product.getId(), 2),
                        new WarehouseOutputLineCommand(product.getId(), 2)));
        var stock = StockLevel.snapshot(
                product.getId(), warehouse.getId(), new java.math.BigDecimal("3.000"));
        var policy = new StockSettings(store.getId(), warehouse.getId());
        policy.update(warehouse.getId(), false, StockSettings.DEFAULT_MINIMUM_STOCK, true);
        when(outputs.findById(output.getId())).thenReturn(Optional.of(output));
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(settings.findById(store.getId())).thenReturn(Optional.of(policy));
        when(stockLevels.findByProductIdAndWarehouseIdForUpdate(
                product.getId(), warehouse.getId())).thenReturn(Optional.of(stock));

        assertThatThrownBy(() -> service.confirm(output.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no permite stock negativo");

        assertThat(output.getStatus()).isEqualTo(WarehouseOutputStatus.BORRADOR);
        assertThat(stock.getQuantity()).isEqualByComparingTo("3.000");
        verify(counters, never()).save(any());
        verify(movements, never()).save(any());
        verify(outputs, never()).save(any());
        verify(syncOutbox, never()).enqueue(any());
    }

    private UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("ADMIN", "n/a");
    }
}
