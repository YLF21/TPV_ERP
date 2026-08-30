package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class FiscalExportDownloadCapabilityContractTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatedCapabilityHasAtLeast256BitsAndUrlSafeForm() throws Exception {
        Method generator = FiscalExportJobService.class.getDeclaredMethod("randomDownloadToken");
        generator.setAccessible(true);
        var token = (String) generator.invoke(null);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
    }

    @Test
    void migrationStoresOnlyHashAndHasExpiryAndSingleUseColumns() throws Exception {
        var sql = Files.readString(Path.of("src/main/resources/db/migration/V227__fiscal_export_download_capability.sql"));
        assertThat(sql).contains("token_hash varchar(64) primary key")
                .contains("expira_en timestamptz not null")
                .contains("consumido_en timestamptz")
                .doesNotContain("token varchar")
                .doesNotContain("token_text");
    }

    @Test
    void streamingRouteIsPostOnlyAndHasExactPath() throws Exception {
        var mapping = FiscalExportDownloadController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/api/v1/fiscal/export-jobs/download");
        var post = FiscalExportDownloadController.class.getDeclaredMethod("download",
                org.springframework.util.MultiValueMap.class, jakarta.servlet.http.HttpServletRequest.class)
                .getAnnotation(PostMapping.class);
        assertThat(post).isNotNull();
        assertThat(post.consumes()).containsExactly("application/x-www-form-urlencoded");
    }

    @Test
    void opensRegularFileWithValidatedSizeAndClosesTheSameHandleAfterResponseStream() throws Exception {
        var file = temporaryDirectory.resolve("export.zip");
        Files.writeString(file, "ZIP-CONTENT", StandardCharsets.UTF_8);
        var service = service(temporaryDirectory);
        var handle = service.openDownloadHandle(file, "export.zip", "ZIP-CONTENT".length());
        var token = "A".repeat(43);
        var jobs = mock(FiscalExportJobService.class);
        when(jobs.consumeDownloadTokenForStreaming(token)).thenReturn(handle);
        var request = mock(HttpServletRequest.class);
        when(request.getQueryString()).thenReturn(null);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("token", token);

        var response = new FiscalExportDownloadController(jobs).download(form, request);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getHeaders().getContentLength()).isEqualTo("ZIP-CONTENT".length());
        try (InputStream input = response.getBody().getInputStream()) {
            assertThat(input.readAllBytes()).isEqualTo("ZIP-CONTENT".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(handle.isOpen()).isFalse();
    }

    @Test
    void rejectsSymbolicLinkBeforeAPathCanBeServed() throws Exception {
        var target = temporaryDirectory.resolve("outside.zip");
        var link = temporaryDirectory.resolve("export.zip");
        Files.writeString(target, "outside", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException exception) {
            Assumptions.assumeTrue(false, "El proveedor de archivos no permite crear symlinks: "
                    + exception.getClass().getSimpleName());
        }
        var service = service(temporaryDirectory);
        assertThatThrownBy(() -> service.openDownloadHandle(link, "export.zip", "outside".length()))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void rejectsWhenTheOpenedSizeDiffersFromThePersistedJobSize() throws Exception {
        var file = temporaryDirectory.resolve("export.zip");
        Files.writeString(file, "ZIP-CONTENT", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service(temporaryDirectory)
                .openDownloadHandle(file, "export.zip", "ZIP-CONTENT".length() + 1))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void servesTheOpenedFileWhenItsNameIsReplacedAfterValidation() throws Exception {
        var file = temporaryDirectory.resolve("export.zip");
        var moved = temporaryDirectory.resolve("export-original.zip");
        Files.writeString(file, "ORIGINAL", StandardCharsets.UTF_8);
        var handle = service(temporaryDirectory).openDownloadHandle(file, "export.zip", "ORIGINAL".length());
        try {
            try {
                Files.move(file, moved);
                Files.writeString(file, "REPLACEMENT", StandardCharsets.UTF_8);
            } catch (java.io.IOException exception) {
                Assumptions.assumeTrue(false, "El proveedor no permite reemplazar un archivo abierto: "
                        + exception.getClass().getSimpleName());
            }
            try (InputStream input = handle.openStream()) {
                assertThat(input.readAllBytes()).isEqualTo("ORIGINAL".getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            handle.close();
        }
    }

    @Test
    void closesHandleWhenTheOwningTransactionRollsBack() throws Exception {
        var file = temporaryDirectory.resolve("export.zip");
        Files.writeString(file, "ZIP-CONTENT", StandardCharsets.UTF_8);
        var service = service(temporaryDirectory);
        var handle = service.openDownloadHandle(file, "export.zip", "ZIP-CONTENT".length());
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.registerDownloadHandleRollbackCleanup(handle);
            assertThat(handle.isOpen()).isTrue();
            TransactionSynchronizationManager.getSynchronizations().getFirst()
                    .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(handle.isOpen()).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            handle.close();
        }
    }

    private static FiscalExportJobService service(Path directory) {
        return new FiscalExportJobService(mock(com.tpverp.backend.organization.CurrentOrganization.class),
                mock(com.tpverp.backend.installation.InstallationRepository.class),
                mock(com.tpverp.backend.licensing.LicenseRepository.class),
                mock(FiscalExportJobRepository.class), mock(NamedParameterJdbcTemplate.class),
                mock(FiscalExportJobEvidenceService.class), null, directory.toString(),
                1_000_000L, 2_000_000_000L, mock(VerifactuOfficialXsdValidator.class));
    }
}
