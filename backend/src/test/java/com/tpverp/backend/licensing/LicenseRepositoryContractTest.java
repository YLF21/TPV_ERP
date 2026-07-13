package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class LicenseRepositoryContractTest {

    @Test
    void activeLicenseQueryNavigatesMappedStoreAndInstallationAssociations() throws Exception {
        var method = LicenseRepository.class.getMethod(
                "findByTiendaIdAndInstalacionIdAndActivaTrue", UUID.class, UUID.class);

        var query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value()).contains("license.tienda.id = :tiendaId");
        assertThat(query.value()).contains("license.instalacion.id = :instalacionId");
    }

    @Test
    void storeHistoryQueryNavigatesMappedStoreAssociation() throws Exception {
        var method = LicenseRepository.class.getMethod(
                "findByTiendaIdOrderByValidaDesdeDesc", UUID.class);

        var query = method.getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value()).contains("license.tienda.id = :tiendaId");
        assertThat(query.value()).contains("order by license.validaDesde desc");
    }
}
