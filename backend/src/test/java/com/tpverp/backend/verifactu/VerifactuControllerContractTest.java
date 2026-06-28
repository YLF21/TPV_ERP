package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;

class VerifactuControllerContractTest {

    @Test
    void exposesDefectiveRecordsEndpointWithSalesManagementPermission()
            throws NoSuchMethodException {
        assertThat(DefectiveFiscalRecordController.class
                .getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/verifactu/defective-records");

        var method = DefectiveFiscalRecordController.class.getDeclaredMethod("list");
        assertThat(method.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_VENTAS");
        assertThat(Arrays.stream(DefectiveFiscalRecordController.class.getDeclaredMethods())
                .filter(Method::isSynthetic)
                .toList()).isEmpty();
    }

    @Test
    void exposesFiscalAttemptHistoryEndpoint() throws NoSuchMethodException {
        var method = DefectiveFiscalRecordController.class.getDeclaredMethod(
                "attempts", UUID.class);

        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/{recordId}/attempts");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_VENTAS");
    }

    @Test
    void exposesFiscalCorrectionEndpoint() throws NoSuchMethodException {
        var method = DefectiveFiscalRecordController.class.getDeclaredMethod(
                "correct", UUID.class, FiscalCorrectionRequest.class, Authentication.class);

        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/{recordId}/corrections");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_VENTAS");
    }

    @Test
    void exposesVerifactuAdminEndpoints() throws NoSuchMethodException {
        assertThat(VerifactuAdminController.class
                .getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/verifactu/admin");

        assertSecuredGet("status");
        assertSecuredGet("queue");
        assertSecuredGet("clock");
        assertSecuredGet("attempts", UUID.class);

        assertAdminOnlyPost("activateVoluntary", "/activate-voluntary");
        assertAdminOnlyPost("deactivateVoluntary", "/deactivate-voluntary");

        var retry = VerifactuAdminController.class.getDeclaredMethod("retryNext");
        assertThat(retry.getAnnotation(PostMapping.class).value())
                .containsExactly("/retry-next");
        assertThat(retry.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_VENTAS");
    }

    @Test
    void exposesAdminOnlyManagedCertificateEndpoints() throws NoSuchMethodException {
        assertThat(VerifactuCertificateController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/verifactu/admin/certificates");
        assertAdminOnly(VerifactuCertificateController.class.getDeclaredMethod("list"));
        assertAdminOnly(VerifactuCertificateController.class.getDeclaredMethod(
                "importCertificate", MultipartFile.class, char[].class, Authentication.class));
        assertAdminOnly(VerifactuCertificateController.class.getDeclaredMethod(
                "delete", VerifactuCertificateController.DeleteCertificateRequest.class,
                Authentication.class));
    }

    private static void assertSecuredGet(String methodName) throws NoSuchMethodException {
        var method = VerifactuAdminController.class.getDeclaredMethod(methodName);
        assertThat(method.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_VENTAS");
    }

    private static void assertSecuredGet(String methodName, Class<?> parameter)
            throws NoSuchMethodException {
        var method = VerifactuAdminController.class.getDeclaredMethod(methodName, parameter);
        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/records/{recordId}/attempts");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_VENTAS");
    }

    private static void assertSecuredPost(String methodName, String path) throws NoSuchMethodException {
        var method = VerifactuAdminController.class.getDeclaredMethod(methodName);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_VENTAS");
    }

    private static void assertAdminOnlyPost(String methodName, String path) throws NoSuchMethodException {
        var method = VerifactuAdminController.class.getDeclaredMethod(methodName);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
        assertAdminOnly(method);
    }

    private static void assertAdminOnly(Method method) {
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('ADMIN')");
    }
}
