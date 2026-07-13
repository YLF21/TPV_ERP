package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock private CurrentOrganization organization;
    @Mock private ProductRepository productRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private StockLevelRepository stockRepository;
    @Mock private StockSettingsRepository settingsRepository;
    @Mock private StockMovementRepository movementRepository;
    @Mock private SyncOutboxService syncOutbox;
    @Mock private Store store;
    @Mock private UserAccount user;
    @Mock private com.tpverp.backend.organization.Company company;

    private InventoryService service;
    private final UUID companyId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken("ADMIN", "token");

    @BeforeEach
    void setUp() {
        lenient().when(store.getId()).thenReturn(storeId);
        lenient().when(store.getEmpresa()).thenReturn(company);
        lenient().when(company.getId()).thenReturn(companyId);
        lenient().when(organization.currentStore()).thenReturn(store);
        lenient().when(organization.currentCompany()).thenReturn(company);
        lenient().when(user.getId()).thenReturn(userId);
        lenient().when(organization.currentUser(authentication)).thenReturn(user);
        lenient().when(movementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new InventoryService(
                organization, productRepository, warehouseRepository,
                stockRepository, settingsRepository, movementRepository,
                new StockMovementSyncPublisher(syncOutbox),
                Clock.fixed(Instant.parse("2026-06-08T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void listsStockOnlyForAuthenticatedStoreWhenTwoStoresExist() {
        var authenticatedStore = org.mockito.Mockito.mock(Store.class);
        var firstStoreId = UUID.randomUUID();
        var authenticatedStoreId = UUID.randomUUID();
        when(authenticatedStore.getId()).thenReturn(authenticatedStoreId);
        when(organization.currentStore()).thenReturn(authenticatedStore);
        var firstStoreWarehouse = new Warehouse(firstStoreId, "PRIMERA");
        var authenticatedWarehouse = new Warehouse(authenticatedStoreId, "AUTENTICADO");
        when(warehouseRepository.findByStoreIdOrderByNombre(any()))
                .thenAnswer(invocation -> firstStoreId.equals(invocation.getArgument(0))
                        ? List.of(firstStoreWarehouse)
                        : List.of(authenticatedWarehouse));

        assertThat(service.stock(null, null)).isEmpty();
        verify(warehouseRepository).findByStoreIdOrderByNombre(authenticatedStoreId);
    }

    @Test
    void adjustmentCreatesStockAndMovement() {
        var product = product();
        var warehouse = new Warehouse(storeId, "GENERAL 2");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(stockRepository.findByProductIdAndWarehouseId(product.getId(), warehouse.getId()))
                .thenReturn(Optional.empty());
        when(stockRepository.save(any(StockLevel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.adjust(
                product.getId(), warehouse.getId(), -3, "ROTURA", authentication);

        assertThat(result.quantity()).isEqualByComparingTo("-3.000");
        verify(movementRepository).save(any(StockMovement.class));
    }

    @Test
    void adjustmentEnqueuesStockMovementSyncEvent() {
        var product = product();
        var warehouse = new Warehouse(storeId, "GENERAL 2");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(stockRepository.findByProductIdAndWarehouseId(product.getId(), warehouse.getId()))
                .thenReturn(Optional.empty());
        when(stockRepository.save(any(StockLevel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(movementRepository.save(any(StockMovement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.adjust(product.getId(), warehouse.getId(), -3, "ROTURA", authentication);

        var command = org.mockito.ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(syncOutbox).enqueue(command.capture());
        assertThat(command.getValue().companyId()).isEqualTo(companyId);
        assertThat(command.getValue().storeId()).isEqualTo(storeId);
        assertThat(command.getValue().terminalId()).isNull();
        assertThat(command.getValue().entityType()).isEqualTo("STOCK_MOVEMENT");
        assertThat(command.getValue().operation()).isEqualTo(SyncOperation.CREAR);
        assertThat(command.getValue().payload())
                .containsEntry("productoId", product.getId().toString())
                .containsEntry("almacenId", warehouse.getId().toString())
                .containsEntry("usuarioId", userId.toString())
                .containsEntry("tipo", "AJUSTE")
                .containsEntry("cantidad", new BigDecimal("-3"))
                .containsEntry("motivo", "ROTURA")
                .containsEntry("creadoEn", "2026-06-08T12:00:00Z");
    }

    @Test
    void adjustmentRequiresReason() {
        assertThatThrownBy(() -> service.adjust(
                UUID.randomUUID(), UUID.randomUUID(), 1, " ", authentication))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unitProductRejectsDecimalStockAdjustment() {
        var product = product();
        var warehouse = new Warehouse(storeId, "GENERAL 2");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.adjust(
                product.getId(), warehouse.getId(), new BigDecimal("1.500"), "ROTURA", authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.product.unit_quantity_must_be_integer");
    }

    @Test
    void serviceProductRejectsStockAdjustment() {
        var product = new Product(
                storeId, UUID.randomUUID(), null, UUID.randomUUID(),
                ProductType.SERVICE, com.tpverp.backend.catalog.DiscountType.NORMAL,
                "SERVICIO", null, null, BigDecimal.ZERO, true);
        var warehouse = new Warehouse(storeId, "GENERAL 2");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.adjust(
                product.getId(), warehouse.getId(), BigDecimal.ONE, "ROTURA", authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.product.service_has_no_stock");
    }

    @Test
    void transferUpdatesBothStocksAndCreatesTwoMovements() {
        var product = product();
        var source = new Warehouse(storeId, "ORIGEN");
        var target = new Warehouse(storeId, "DESTINO");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(warehouseRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(warehouseRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(stockRepository.findByProductIdAndWarehouseId(product.getId(), source.getId()))
                .thenReturn(Optional.of(new StockLevel(product.getId(), source.getId())));
        when(stockRepository.findByProductIdAndWarehouseId(product.getId(), target.getId()))
                .thenReturn(Optional.of(new StockLevel(product.getId(), target.getId())));

        var result = service.transfer(
                product.getId(), source.getId(), target.getId(), 4, authentication);

        assertThat(result.sourceQuantity()).isEqualByComparingTo("-4.000");
        assertThat(result.targetQuantity()).isEqualByComparingTo("4.000");
        verify(movementRepository, times(2)).save(any(StockMovement.class));
    }

    @Test
    void negativeAdjustmentIsRejectedWithoutPartialWritesWhenDisabled() {
        var product = product();
        var warehouse = new Warehouse(storeId, "GENERAL 2");
        var stock = StockLevel.snapshot(
                product.getId(), warehouse.getId(), new BigDecimal("2.000"));
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(settingsRepository.findById(storeId))
                .thenReturn(Optional.of(negativeStockDisabled(warehouse.getId())));
        when(stockRepository.findByProductIdAndWarehouseIdForUpdate(
                product.getId(), warehouse.getId())).thenReturn(Optional.of(stock));

        assertThatThrownBy(() -> service.adjust(
                product.getId(), warehouse.getId(), -3, "ROTURA", authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no permite stock negativo");

        assertThat(stock.getQuantity()).isEqualByComparingTo("2.000");
        verify(stockRepository, never()).save(any());
        verify(movementRepository, never()).save(any());
        verify(syncOutbox, never()).enqueue(any());
    }

    @Test
    void transferIsRejectedBeforeEitherWarehouseOrMovementIsChangedWhenDisabled() {
        var product = product();
        var source = new Warehouse(storeId, "ORIGEN");
        var target = new Warehouse(storeId, "DESTINO");
        var sourceStock = StockLevel.snapshot(
                product.getId(), source.getId(), new BigDecimal("1.000"));
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(warehouseRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(warehouseRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(settingsRepository.findById(storeId))
                .thenReturn(Optional.of(negativeStockDisabled(source.getId())));
        when(stockRepository.findByProductIdAndWarehouseIdForUpdate(
                product.getId(), source.getId())).thenReturn(Optional.of(sourceStock));

        assertThatThrownBy(() -> service.transfer(
                product.getId(), source.getId(), target.getId(), 2, authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no permite stock negativo");

        assertThat(sourceStock.getQuantity()).isEqualByComparingTo("1.000");
        verify(stockRepository, never()).save(any());
        verify(movementRepository, never()).save(any());
        verify(syncOutbox, never()).enqueue(any());
    }

    private StockSettings negativeStockDisabled(UUID warehouseId) {
        var settings = new StockSettings(storeId, warehouseId);
        settings.update(warehouseId, false, StockSettings.DEFAULT_MINIMUM_STOCK, true);
        return settings;
    }

    private Product product() {
        return new Product(
                storeId, UUID.randomUUID(), null, UUID.randomUUID(), "PRODUCTO",
                null, BigDecimal.ZERO, true);
    }
}
