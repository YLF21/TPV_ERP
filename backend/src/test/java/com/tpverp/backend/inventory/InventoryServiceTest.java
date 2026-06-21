package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.security.domain.Usuario;
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
    @Mock private StockMovementRepository movementRepository;
    @Mock private Tienda store;
    @Mock private Usuario user;

    private InventoryService service;
    private final UUID storeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken("ADMIN", "token");

    @BeforeEach
    void setUp() {
        lenient().when(store.getId()).thenReturn(storeId);
        lenient().when(organization.currentStore()).thenReturn(store);
        lenient().when(user.getId()).thenReturn(userId);
        lenient().when(organization.currentUser(authentication)).thenReturn(user);
        service = new InventoryService(
                organization, productRepository, warehouseRepository,
                stockRepository, movementRepository,
                Clock.fixed(Instant.parse("2026-06-08T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void listsStockOnlyForAuthenticatedStoreWhenTwoStoresExist() {
        var authenticatedStore = org.mockito.Mockito.mock(Tienda.class);
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

        assertThat(result.quantity()).isEqualTo(-3);
        verify(movementRepository).save(any(StockMovement.class));
    }

    @Test
    void adjustmentRequiresReason() {
        assertThatThrownBy(() -> service.adjust(
                UUID.randomUUID(), UUID.randomUUID(), 1, " ", authentication))
                .isInstanceOf(IllegalArgumentException.class);
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

        assertThat(result.sourceQuantity()).isEqualTo(-4);
        assertThat(result.targetQuantity()).isEqualTo(4);
        verify(movementRepository, times(2)).save(any(StockMovement.class));
    }

    private Product product() {
        return new Product(
                storeId, UUID.randomUUID(), null, UUID.randomUUID(), "PRODUCTO",
                null, BigDecimal.ZERO, true);
    }
}
