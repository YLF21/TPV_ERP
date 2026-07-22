package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;

class VerifactuControllerContractTest {

    @Test
    void exposesDefectiveRecordsEndpointWithGestionAccessAndFiscalReadPermission()
            throws NoSuchMethodException {
        assertThat(DefectiveFiscalRecordController.class
                .getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/verifactu/defective-records");

        var method = DefectiveFiscalRecordController.class.getDeclaredMethod("list");
        assertThat(method.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo(fiscalReadAuthorization());
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
                .isEqualTo(fiscalManageAuthorization());
    }

    @Test
    void exposesFiscalCorrectionEndpoint() throws NoSuchMethodException {
        var method = DefectiveFiscalRecordController.class.getDeclaredMethod(
                "correct", UUID.class, FiscalCorrectionRequest.class, Authentication.class);

        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/{recordId}/corrections");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
                        + "hasAuthority('VERIFACTU_READ') and hasAuthority('VERIFACTU_CORRECT'))");
    }

    @Test
    void exposesVerifactuAdminEndpoints() throws NoSuchMethodException {
        assertThat(VerifactuAdminController.class
                .getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/verifactu/admin");

        assertAdminOnlyGet("status");
        assertSecuredGet("queue");
        assertSecuredGet("clock");
        assertManagedGet("attempts", UUID.class);

        assertAdminOnlyPost("activateVoluntary", "/activate-voluntary");
        assertAdminOnlyPost("deactivateVoluntary", "/deactivate-voluntary");

        var retry = VerifactuAdminController.class.getDeclaredMethod("retryNext");
        assertThat(retry.getAnnotation(PostMapping.class).value())
                .containsExactly("/retry-next");
        assertAdminOnly(retry);
    }

    @Test
    void exposesAdminOnlyManagedCertificateEndpoints() throws NoSuchMethodException {
        assertThat(VerifactuCertificateController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/verifactu/admin/certificates");
        assertAdminOnly(VerifactuCertificateController.class.getDeclaredMethod("list"));
        assertAdminOnly(VerifactuCertificateController.class.getDeclaredMethod(
                "importCertificate", MultipartFile.class, char[].class, UUID.class,
                String.class, Authentication.class));
        assertAdminOnly(VerifactuCertificateController.class.getDeclaredMethod(
                "delete", VerifactuCertificateController.DeleteCertificateRequest.class,
                Authentication.class));
    }

    private static void assertSecuredGet(String methodName) throws NoSuchMethodException {
        var method = VerifactuAdminController.class.getDeclaredMethod(methodName);
        assertThat(method.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo(fiscalReadAuthorization());
    }

    private static void assertManagedGet(String methodName, Class<?> parameter)
            throws NoSuchMethodException {
        var method = VerifactuAdminController.class.getDeclaredMethod(methodName, parameter);
        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/records/{recordId}/attempts");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo(fiscalManageAuthorization());
    }

    @Test
    void exposesAdditiveSanitizedAdminReadEndpoints() throws NoSuchMethodException {
        assertThat(VerifactuAdminReadController.class
                .getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/verifactu/admin");
        assertThat(VerifactuAdminReadController.class.getAnnotation(PreAuthorize.class).value())
                .isEqualTo(fiscalReadAuthorization());

        var summary = VerifactuAdminReadController.class.getDeclaredMethod("summary");
        assertThat(summary.getAnnotation(GetMapping.class).value())
                .containsExactly("/summary");
        var submissions = VerifactuAdminReadController.class.getDeclaredMethod(
                "submissions",
                LocalDate.class,
                LocalDate.class,
                FiscalSubmissionStatus.class,
                FiscalDocumentType.class,
                FiscalRecordOperation.class,
                String.class,
                int.class,
                int.class);
        assertThat(submissions.getAnnotation(GetMapping.class).value())
                .containsExactly("/submissions");
        var defectiveRecords = VerifactuAdminReadController.class.getDeclaredMethod(
                "defectiveRecords",
                LocalDate.class,
                LocalDate.class,
                FiscalSubmissionStatus.class,
                FiscalDocumentType.class,
                FiscalRecordOperation.class,
                String.class,
                int.class,
                int.class);
        assertThat(defectiveRecords.getAnnotation(GetMapping.class).value())
                .containsExactly("/defective-records");
        var attempts = VerifactuAdminReadController.class.getDeclaredMethod(
                "attempts", UUID.class, int.class, int.class);
        assertThat(attempts.getAnnotation(GetMapping.class).value())
                .containsExactly("/submissions/{recordId}/attempts");
        var diagnostics = VerifactuAdminReadController.class.getDeclaredMethod("diagnostics");
        assertThat(diagnostics.getAnnotation(GetMapping.class).value())
                .containsExactly("/diagnostics");
        var resolution = VerifactuAdminReadController.class.getDeclaredMethod(
                "resolution", UUID.class, Authentication.class);
        assertThat(resolution.getAnnotation(GetMapping.class).value())
                .containsExactly("/submissions/{recordId}/resolution");
    }

    @Test
    void exposesScopedManualRetryWithFiscalManagePermission() throws NoSuchMethodException {
        assertThat(VerifactuAdminActionController.class
                .getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/verifactu/admin");
        var retry = VerifactuAdminActionController.class.getDeclaredMethod(
                "retry", UUID.class, VerifactuManualRetryRequest.class);
        assertThat(retry.getAnnotation(PostMapping.class).value())
                .containsExactly("/submissions/{recordId}/retry");
        assertThat(retry.getAnnotation(PreAuthorize.class).value())
                .isEqualTo(fiscalManageAuthorization());
    }

    private static void assertAdminOnlyGet(String methodName) throws NoSuchMethodException {
        var method = VerifactuAdminController.class.getDeclaredMethod(methodName);
        assertThat(method.getAnnotation(GetMapping.class)).isNotNull();
        assertAdminOnly(method);
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

    private static String fiscalReadAuthorization() {
        return "hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
                + "hasAuthority('VERIFACTU_READ'))";
    }

    private static String fiscalManageAuthorization() {
        return "hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
                + "hasAuthority('VERIFACTU_READ') and hasAuthority('VERIFACTU_MANAGE'))";
    }
}
