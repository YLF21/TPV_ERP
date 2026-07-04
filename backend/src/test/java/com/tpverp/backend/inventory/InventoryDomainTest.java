package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryDomainTest {

    @Test
    void stockAllowsNegativeIntegerQuantities() {
        var stock = new StockLevel(UUID.randomUUID(), UUID.randomUUID());

        stock.apply(-7);

        assertThat(stock.getQuantity()).isEqualByComparingTo("-7");
    }

    @Test
    void manualAdjustmentRequiresReason() {
        assertThatThrownBy(() -> StockMovement.adjustment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2, " ", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transferCreatesLinkedOppositeMovements() {
        var transferId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var source = StockMovement.transferOut(
                productId, UUID.randomUUID(), userId, 3, transferId, Instant.now());
        var target = StockMovement.transferIn(
                productId, UUID.randomUUID(), userId, 3, transferId, Instant.now());

        assertThat(source.getQuantity()).isEqualByComparingTo("-3");
        assertThat(target.getQuantity()).isEqualByComparingTo("3");
        assertThat(source.getTransferId()).isEqualTo(target.getTransferId());
    }

    @Test
    void warehouseOutputIsImmutableAfterConfirmation() {
        var output = new WarehouseOutput(
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 6, 8), UUID.randomUUID());
        output.addLine(UUID.randomUUID(), 2);
        output.confirm("SAL-2026-000001", UUID.randomUUID(), Instant.now());

        assertThatThrownBy(() -> output.addLine(UUID.randomUUID(), 1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(output.getNumber()).isEqualTo("SAL-2026-000001");
    }
}
