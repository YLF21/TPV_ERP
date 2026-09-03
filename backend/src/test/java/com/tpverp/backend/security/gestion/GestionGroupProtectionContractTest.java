package com.tpverp.backend.security.gestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.document.PaymentMethodController;
import com.tpverp.backend.licensing.api.LicenseController;
import com.tpverp.backend.security.api.SecurityAdministrationController;
import com.tpverp.backend.security.sales.SaleOperationSecurityController;
import com.tpverp.backend.terminal.TerminalController;
import com.tpverp.backend.verifactu.FiscalController;
import com.tpverp.backend.verifactu.VerifactuAdminController;
import com.tpverp.backend.verifactu.VerifactuPosController;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;

class GestionGroupProtectionContractTest {

    @Test
    void fiscalAdministrationIsProtectedButVerifactuPosRemainsAvailable() {
        assertThat(requirement(FiscalController.class).value()).isEqualTo(GestionGroup.FISCAL);
        assertThat(requirement(VerifactuPosController.class)).isNull();
        assertThat(requirement(VerifactuAdminController.class)).isNull();
        assertThat(requirement(method(VerifactuAdminController.class, "status"))).isNull();
        assertThat(requirement(method(VerifactuAdminController.class, "clock"))).isNull();
        assertThat(requirement(method(VerifactuAdminController.class, "retryNext"))).isNull();
        assertThat(requirement(method(VerifactuAdminController.class, "queue")).value())
                .isEqualTo(GestionGroup.FISCAL);
    }

    @Test
    void sharedRoleReadsStayAvailableWhileSecurityAdministrationMutationsAreProtected() {
        assertThat(requirement(SecurityAdministrationController.class)).isNull();
        assertThat(requirement(method(SecurityAdministrationController.class, "roles"))).isNull();
        assertThat(requirement(method(SecurityAdministrationController.class, "createUser")).value())
                .isEqualTo(GestionGroup.SEGURIDAD);
        assertThat(requirement(method(SaleOperationSecurityController.class, "current"))).isNull();
        assertThat(requirement(method(SaleOperationSecurityController.class, "update"))).isNotNull();
        assertThat(requirement(method(SaleOperationSecurityController.class, "reset"))).isNotNull();
    }

    @Test
    void initialLicenseBootstrapStaysAvailableWhileLicenseManagementIsProtected() {
        assertThat(requirement(LicenseController.class)).isNull();
        assertThat(requirement(method(LicenseController.class, "bootstrapEmptyDatabase"))).isNull();
        assertThat(requirement(method(LicenseController.class, "preview")).value())
                .isEqualTo(GestionGroup.CONFIGURACION);
        assertThat(requirement(method(LicenseController.class, "linkSaas")).value())
                .isEqualTo(GestionGroup.CONFIGURACION);
    }

    @Test
    void paymentMethodReadsStayAvailableToCheckoutWhileMutationsAreProtected() {
        assertThat(requirement(method(PaymentMethodController.class, "list"))).isNull();
        assertThat(requirement(method(PaymentMethodController.class, "create")).value())
                .isEqualTo(GestionGroup.CONFIGURACION);
        assertThat(requirement(method(PaymentMethodController.class, "setActive")).value())
                .isEqualTo(GestionGroup.CONFIGURACION);
        assertThat(requirement(method(PaymentMethodController.class, "configure")).value())
                .isEqualTo(GestionGroup.CONFIGURACION);
    }

    @Test
    void terminalEnrollmentAndInitialServerProvisioningStayOutsideTheGestionLock() {
        assertThat(requirement(method(TerminalController.class, "request"))).isNull();
        assertThat(requirement(method(TerminalController.class, "requestPda"))).isNull();
        assertThat(requirement(method(TerminalController.class, "linkPda"))).isNull();
        assertThat(requirement(method(TerminalController.class, "provisionServer"))).isNull();
        assertThat(requirement(method(TerminalController.class, "list")).value())
                .isEqualTo(GestionGroup.SEGURIDAD);
    }

    private static Method method(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static RequireGestionGroup requirement(java.lang.reflect.AnnotatedElement element) {
        return AnnotatedElementUtils.findMergedAnnotation(element, RequireGestionGroup.class);
    }
}
