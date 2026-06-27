package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.organization.Company;
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

    @Test
    void businessCodesAreAssignedOnlyOnce() {
        var company = company();
        var supplier = new Supplier(
                company, "Proveedor", null, DocumentType.CIF, "B1",
                null, null, null, null);
        var commercial = new SalesRepresentative(company, "Comercial", null, null, null);

        supplier.assignCode("S-000001");
        commercial.assignCode("CO-000001");

        assertThat(supplier.getSupplierId()).isEqualTo("S-000001");
        assertThat(commercial.getCommercialId()).isEqualTo("CO-000001");
    }

    private Company company() {
        return new Company("B00000000", "Company", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES"));
    }
}
