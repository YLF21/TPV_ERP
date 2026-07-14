package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

class WarehouseRepositoryContractTest {

    @Test
    void inputListFetchesLinesForApiSerialization() throws Exception {
        var method = WarehouseInputRepository.class.getMethod(
                "findByStoreIdOrderByFechaDesc", UUID.class);

        var graph = method.getAnnotation(EntityGraph.class);

        assertThat(graph).isNotNull();
        assertThat(graph.attributePaths()).containsExactly("lines");
    }

    @Test
    void outputListFetchesLinesForApiSerialization() throws Exception {
        var method = WarehouseOutputRepository.class.getMethod(
                "findByStoreIdOrderByFechaDesc", UUID.class);

        var graph = method.getAnnotation(EntityGraph.class);

        assertThat(graph).isNotNull();
        assertThat(graph.attributePaths()).containsExactly("lines");
    }
}
