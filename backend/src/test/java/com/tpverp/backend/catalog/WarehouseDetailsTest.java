package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class WarehouseDetailsTest {
    @Test
    void keepsAddressAndNotesWhenCreatingAndEditingWarehouse() {
        var warehouse = new Warehouse(UUID.randomUUID(), "RESERVA", " Calle Norte 8 ", " Reposición ");
        assertThat(warehouse.getAddress()).isEqualTo("Calle Norte 8");
        assertThat(warehouse.getNotes()).isEqualTo("Reposición");

        warehouse.updateDetails("RESERVA", "Calle Nueva 2", "");
        assertThat(warehouse.getAddress()).isEqualTo("Calle Nueva 2");
        assertThat(warehouse.getNotes()).isNull();
    }

    @Test
    void defaultWarehouseAllowsNotesButProtectsItsAddressAndName() {
        var general = Warehouse.general(UUID.randomUUID());
        general.updateDetails("GENERAL", "Calle Mayor 1", "Principal");
        assertThat(general.getAddress()).isNull();
        assertThat(general.getNotes()).isEqualTo("Principal");
        assertThatThrownBy(() -> general.updateDetails("CAMBIADO", "Calle Mayor 1", "Principal"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(general.getName()).isEqualTo("GENERAL");
    }
}
