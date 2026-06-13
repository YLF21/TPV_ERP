package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.organization.Empresa;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupplierSalesRepresentativeTest {

    @Test
    void selectingANewPrimaryRepresentativeDemotesThePreviousOne() {
        var company = company();
        var supplier = new Supplier(
                company, "Proveedor", null, DocumentType.CIF, " b1 ",
                null, null, null, null);
        var first = new SalesRepresentative(company, "Primero", null, null, null);
        var second = new SalesRepresentative(company, "Segundo", null, null, null);

        supplier.linkRepresentative(first, true);
        supplier.linkRepresentative(second, true);

        assertThat(supplier.getRepresentatives())
                .filteredOn(SupplierRepresentative::isPrimary)
                .extracting(link -> link.getRepresentative().getName())
                .containsExactly("Segundo");
    }

    private Empresa company() {
        return new Empresa("B00000000", "Empresa", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES"));
    }
}
