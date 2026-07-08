package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

class ProductRepositoryContractTest {

    @Test
    void doesNotFetchMultipleProductBagsWithOneEntityGraph() throws Exception {
        assertNoMultipleBagGraph("findByStoreIdOrderByNombre", java.util.UUID.class);
        assertNoMultipleBagGraph("findById", Object.class);
        assertNoMultipleBagGraph("findAllByStoreIdAndIdIn", java.util.UUID.class, java.util.Collection.class);
    }

    private static void assertNoMultipleBagGraph(String methodName, Class<?>... parameterTypes) throws Exception {
        var graph = ProductRepository.class.getMethod(methodName, parameterTypes).getAnnotation(EntityGraph.class);

        if (graph != null) {
            assertThat(graph.attributePaths()).doesNotContain("identifiers", "prices");
        }
    }
}
