package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PatchMapping;

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
                .contains("CUSTOMERS_READ", "GESTION_CLIENTE_PROVEEDOR")
                .doesNotContain("VENTA");
        assertThat(permission(CustomerController.class.getMethod("saleOptions")))
                .contains("CUSTOMERS_READ", "VENTA");
        assertThat(permission(CustomerController.class.getMethod(
                "create", CustomerController.CustomerRequest.class)))
                .contains("CUSTOMERS_WRITE", "GESTION_CLIENTE_PROVEEDOR", "VENTA");
        assertThat(permission(SupplierController.class.getMethod("list")))
                .contains("SUPPLIERS_READ", "GESTION_CLIENTE_PROVEEDOR", "GESTION_ALMACEN");
        assertThat(permission(CustomerController.class.getMethod("activate", java.util.UUID.class)))
                .contains("CUSTOMERS_WRITE");
        assertThat(permission(SupplierController.class.getMethod("activate", java.util.UUID.class)))
                .contains("SUPPLIERS_WRITE");
        assertThat(permission(SalesRepresentativeController.class.getMethod("list")))
                .contains("SUPPLIERS_READ", "GESTION_CLIENTE_PROVEEDOR", "GESTION_ALMACEN");
    }

    @Test
    void exposesActivationEndpointsAsPatchOperations() throws Exception {
        assertThat(CustomerController.class.getMethod("activate", java.util.UUID.class)
                .getAnnotation(PatchMapping.class).value())
                .containsExactly("/{id}/activate");
        assertThat(SupplierController.class.getMethod("activate", java.util.UUID.class)
                .getAnnotation(PatchMapping.class).value())
                .containsExactly("/{id}/activate");
    }

    @Test
    void saleCustomerOptionExposesIdentityMemberBenefitAndCreditAssessmentInputs() {
        assertThat(Arrays.stream(CustomerController.SaleCustomerOption.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "id", "clientId", "fiscalName", "documentNumber",
                        "activeMember", "memberCategoryName", "memberDiscountPercent",
                        "creditEnabled", "creditLimit", "paymentTermDays",
                        "creditBlocked", "blockOnOverdue", "outstandingDebt",
                        "overdueDebt", "availableCredit");
    }

    private String path(Class<?> controller) {
        return controller.getAnnotation(RequestMapping.class).value()[0];
    }

    private String permission(Method method) {
        return method.getAnnotation(PreAuthorize.class).value();
    }
}
