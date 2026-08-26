package com.tpverp.backend.licensing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.licensing.LicenseSaasLinkService;
import com.tpverp.backend.licensing.LicenseSaasValidationService;
import com.tpverp.backend.licensing.application.LicenseService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

class LicenseControllerContractTest {

    @Test
    void doesNotExposeLocalOverridesForTheCentralSaasLicenseState() {
        assertThat(LicenseController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/licenses");
        assertThat(java.util.Arrays.stream(LicenseController.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("block", "unblock");
    }

    @Test
    void exponeVinculacionSaasConCodigoTemporal() throws NoSuchMethodException {
        assertThat(LicenseController.class.getDeclaredMethod(
                        "linkSaas", LicenseController.LinkSaasRequest.class)
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/link-saas");
        assertThat(LicenseController.class.getDeclaredMethod(
                        "linkSaas", LicenseController.LinkSaasRequest.class)
                .getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('ADMIN')");
        assertThat(LicenseController.LinkSaasResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("installationToken");
        assertThat(LicenseController.LinkSaasRequest.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("pairingCode", "localStoreId");
        assertThat(LicenseController.class.getDeclaredMethod(
                        "bootstrapEmptyDatabase",
                        LicenseController.BootstrapLinkSaasRequest.class)
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/link-saas/bootstrap-empty");
    }

    @Test
    void exponeValidacionSaasManual() throws NoSuchMethodException {
        assertThat(LicenseController.class.getDeclaredMethod("validateSaas")
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/validate-saas");
        assertThat(LicenseController.class.getDeclaredMethod("validateSaas")
                .getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void licenseManagersCanReadAndPreviewWithoutMutating() throws NoSuchMethodException {
        assertThat(LicenseController.class.getDeclaredMethod("history")
                .getAnnotation(PreAuthorize.class).value())
                .contains("LICENSES_MANAGE");
        assertThat(LicenseController.class.getDeclaredMethod(
                        "preview", LicenseController.LicenseFileRequest.class)
                .getAnnotation(PreAuthorize.class).value())
                .contains("LICENSES_MANAGE");
        assertThat(LicenseController.class.getDeclaredMethod(
                        "activate", LicenseController.ActivateLicenseRequest.class)
                .getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void bloqueaActivacionLocalPorArchivoCuandoNoEstaHabilitada() {
        var controller = new LicenseController(
                org.mockito.Mockito.mock(LicenseService.class),
                org.mockito.Mockito.mock(LicenseSaasLinkService.class),
                org.mockito.Mockito.mock(LicenseSaasValidationService.class),
                false);

        assertThatThrownBy(() -> controller.activate(new LicenseController.ActivateLicenseRequest("x", "h")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }
}
