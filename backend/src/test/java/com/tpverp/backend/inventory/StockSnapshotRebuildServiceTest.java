package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockSnapshotRebuildServiceTest {

    @Mock
    private StockLevelRepository stockLevels;
    @Mock
    private StockMovementRepository movements;

    @Test
    void reconstruyeSnapshotsDesdeMovimientosComoFuenteDeVerdad() {
        var productId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        when(movements.sumQuantitiesByProductAndWarehouse())
                .thenReturn(List.of(new StockSnapshotQuantity(productId, warehouseId, new BigDecimal("7.000"))));

        var result = new StockSnapshotRebuildService(stockLevels, movements).rebuild();

        assertThat(result.snapshots()).isOne();
        verify(stockLevels).deleteAllInBatch();
        var saved = ArgumentCaptor.forClass(Iterable.class);
        verify(stockLevels).saveAll(saved.capture());
        var iterator = saved.getValue().iterator();
        var snapshot = (StockLevel) iterator.next();
        assertThat(iterator.hasNext()).isFalse();
        assertThat(snapshot.getProductId()).isEqualTo(productId);
        assertThat(snapshot.getWarehouseId()).isEqualTo(warehouseId);
        assertThat(snapshot.getQuantity()).isEqualByComparingTo("7.000");
    }
}
