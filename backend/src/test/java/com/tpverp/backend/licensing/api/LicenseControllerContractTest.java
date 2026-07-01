package com.tpverp.backend.licensing.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class LicenseControllerContractTest {

    @Test
    void exponeBloqueoManualProtegidoPorPermisoDeLicencias() throws NoSuchMethodException {
        assertThat(LicenseController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/licenses");
        assertThat(LicenseController.class.getAnnotation(PreAuthorize.class).value())
                .contains("LICENSES_MANAGE");

        assertThat(LicenseController.class.getDeclaredMethod("block", String.class)
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/{reference}/block");
        assertThat(LicenseController.class.getDeclaredMethod("unblock", String.class)
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/{reference}/unblock");
    }

    @Test
    void exponeVinculacionSaasConCodigoTemporal() throws NoSuchMethodException {
        assertThat(LicenseController.class.getDeclaredMethod(
                        "linkSaas", LicenseController.LinkSaasRequest.class)
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/link-saas");
        assertThat(LicenseController.LinkSaasResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("installationToken");
    }
}
