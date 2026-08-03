package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class StockCountServiceTest {
    @Mock StockCountRepository counts;
    @Mock StockCountLineRepository lines;
    @Mock StockLevelRepository stocks;
    @Mock StockMovementRepository movements;
    @Mock ProductRepository products;
    @Mock WarehouseRepository warehouses;
    @Mock CurrentOrganization organization;
    @Mock SyncOutboxService outbox;
    @Mock Store store;
    @Mock Company company;
    @Mock UserAccount user;
    private StockCountService service;
    private final UUID storeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken("ADMIN", "token");

    @BeforeEach
    void setUp() {
        lenient().when(store.getId()).thenReturn(storeId);
        lenient().when(company.getId()).thenReturn(UUID.randomUUID());
        lenient().when(user.getId()).thenReturn(userId);
        lenient().when(organization.currentStore()).thenReturn(store);
        lenient().when(organization.currentCompany()).thenReturn(company);
        lenient().when(organization.currentUser(authentication)).thenReturn(user);
        lenient().when(counts.save(any())).thenAnswer(value -> value.getArgument(0));
        lenient().when(counts.saveAndFlush(any())).thenAnswer(value -> value.getArgument(0));
        lenient().when(lines.save(any())).thenAnswer(value -> value.getArgument(0));
        lenient().when(movements.save(any())).thenAnswer(value -> value.getArgument(0));
        service = new StockCountService(counts, lines, stocks, movements, products, warehouses,
                organization, new StockMovementSyncPublisher(outbox),
                Clock.fixed(Instant.parse("2026-08-03T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void createsDraftOnlyForWarehouseInCurrentStore() {
        var warehouse = new Warehouse(storeId, "SECUNDARIO");
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(lines.findByCountIdOrderByProductId(any())).thenReturn(List.of());

        var result = service.create(warehouse.getId(), "Conteo mensual", authentication);

        assertThat(result.storeId()).isEqualTo(storeId);
        assertThat(result.warehouseId()).isEqualTo(warehouse.getId());
        assertThat(result.status()).isEqualTo(StockCountStatus.DRAFT);
        assertThat(result.notes()).isEqualTo("Conteo mensual");
    }

    @Test
    void rejectsWarehouseFromAnotherStore() {
        var warehouse = new Warehouse(UUID.randomUUID(), "AJENO");
        when(warehouses.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));

        assertThatThrownBy(() -> service.create(warehouse.getId(), null, authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tienda actual");
        verify(counts, never()).saveAndFlush(any());
    }

    @Test
    void confirmationAppliesAdjustmentOnceAndIsIdempotent() {
        var warehouse = new Warehouse(storeId, "SECUNDARIO");
        var product = mockProduct();
        var count = new StockCount(storeId, warehouse.getId(), null, userId, Instant.now());
        var line = new StockCountLine(count.getId(), product.getId(), new BigDecimal("5"), new BigDecimal("7"));
        var stock = StockLevel.snapshot(product.getId(), warehouse.getId(), new BigDecimal("5"));
        when(counts.findLockedByIdAndStoreId(count.getId(), storeId)).thenReturn(Optional.of(count));
        when(lines.findByCountIdOrderByProductId(count.getId())).thenReturn(List.of(line));
        when(stocks.findByProductIdAndWarehouseIdForUpdate(product.getId(), warehouse.getId()))
                .thenReturn(Optional.of(stock));
        when(products.findById(product.getId())).thenReturn(Optional.of(product));

        var first = service.confirm(count.getId(), authentication);
        var second = service.confirm(count.getId(), authentication);

        assertThat(first.status()).isEqualTo(StockCountStatus.CONFIRMED);
        assertThat(second.status()).isEqualTo(StockCountStatus.CONFIRMED);
        assertThat(stock.getQuantity()).isEqualByComparingTo("7.000");
        assertThat(line.getAppliedDifference()).isEqualByComparingTo("2.000");
        var movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(movements, times(1)).save(movement.capture());
        assertThat(movement.getValue().getType()).isEqualTo(StockMovementType.AJUSTE);
        assertThat(movement.getValue().getStockCountId()).isEqualTo(count.getId());
    }

    @Test
    void confirmationRejectsStaleSnapshotWithoutWrites() {
        var product = mockProduct();
        var warehouseId = UUID.randomUUID();
        var count = new StockCount(storeId, warehouseId, null, userId, Instant.now());
        var line = new StockCountLine(count.getId(), product.getId(), new BigDecimal("5"), new BigDecimal("7"));
        when(counts.findLockedByIdAndStoreId(count.getId(), storeId)).thenReturn(Optional.of(count));
        when(lines.findByCountIdOrderByProductId(count.getId())).thenReturn(List.of(line));
        var productId = product.getId();
        var changedStock = StockLevel.snapshot(productId, warehouseId, new BigDecimal("6"));
        when(stocks.findByProductIdAndWarehouseIdForUpdate(productId, warehouseId))
                .thenReturn(Optional.of(changedStock));

        assertThatThrownBy(() -> service.confirm(count.getId(), authentication))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stock cambio");
        assertThat(count.getStatus()).isEqualTo(StockCountStatus.DRAFT);
        verify(movements, never()).save(any());
        verify(stocks, never()).save(any());
    }

    @Test
    void cancelMakesDraftImmutable() {
        var count = new StockCount(storeId, UUID.randomUUID(), null, userId, Instant.now());
        when(counts.findLockedByIdAndStoreId(count.getId(), storeId)).thenReturn(Optional.of(count));
        when(lines.findByCountIdOrderByProductId(count.getId())).thenReturn(List.of());

        var result = service.cancel(count.getId(), authentication);

        assertThat(result.status()).isEqualTo(StockCountStatus.CANCELLED);
        assertThatThrownBy(() -> service.upsertLine(count.getId(), UUID.randomUUID(), BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no es editable");
    }

    private Product mockProduct() {
        var product = mock(Product.class);
        var id = UUID.randomUUID();
        when(product.getId()).thenReturn(id);
        lenient().when(product.getStoreId()).thenReturn(storeId);
        lenient().when(product.getProductType()).thenReturn(ProductType.UNIT);
        lenient().when(product.getCode()).thenReturn("P-1");
        lenient().when(product.getName()).thenReturn("Producto");
        return product;
    }
}
