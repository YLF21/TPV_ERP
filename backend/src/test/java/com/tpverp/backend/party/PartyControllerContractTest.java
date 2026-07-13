package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

class PartyControllerContractTest {

    @Test
    void exposesExpectedBasePaths() {
        assertThat(path(CustomerController.class)).isEqualTo("/api/v1/customers");
        assertThat(path(SupplierController.class)).isEqualTo("/api/v1/suppliers");
        assertThat(path(SalesRepresentativeController.class))
                .isEqualTo("/api/v1/sales-representatives");
    }

    @Test
    void protectsReadAndWriteOperationsWithExistingPermissions() throws Exception {
        assertThat(permission(CustomerController.class.getMethod("list")))
                .contains("CUSTOMERS_READ")
                .doesNotContain("VENTA");
        assertThat(permission(CustomerController.class.getMethod("saleOptions")))
                .contains("CUSTOMERS_READ", "VENTA");
        assertThat(permission(CustomerController.class.getMethod(
                "create", CustomerController.CustomerRequest.class)))
                .contains("CUSTOMERS_WRITE");
        assertThat(permission(SupplierController.class.getMethod("list")))
                .contains("SUPPLIERS_READ");
        assertThat(permission(SalesRepresentativeController.class.getMethod("list")))
                .contains("SUPPLIERS_READ");
    }

    @Test
    void saleCustomerOptionExposesOnlyIdentityAndMemberBenefit() {
        assertThat(Arrays.stream(CustomerController.SaleCustomerOption.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "id", "clientId", "fiscalName", "documentNumber",
                        "activeMember", "memberCategoryName", "memberDiscountPercent");
    }

    private String path(Class<?> controller) {
        return controller.getAnnotation(RequestMapping.class).value()[0];
    }

    private String permission(Method method) {
        return method.getAnnotation(PreAuthorize.class).value();
    }
}
