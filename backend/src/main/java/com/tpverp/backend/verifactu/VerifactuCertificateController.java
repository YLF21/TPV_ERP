package com.tpverp.backend.verifactu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/verifactu/admin/certificates")
public class VerifactuCertificateController {

    private static final long MAX_CERTIFICATE_BYTES = 10L * 1024 * 1024;

    private final VerifactuCertificateManagementService service;

    public VerifactuCertificateController(VerifactuCertificateManagementService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<ManagedCertificateView> list() {
        return service.list();
    }

    // Recibe la contrasena como char[] y la limpia aunque la importacion falle.
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ManagedCertificateView importCertificate(
            @RequestParam("file") MultipartFile file,
            @RequestParam("password") char[] password,
            @RequestParam(value = "expectedActiveCertificateId", required = false)
                    UUID expectedActiveCertificateId,
            @RequestParam(value = "confirmation", required = false) String confirmation,
            Authentication authentication) {
        byte[] content = null;
        try {
            if (file == null || file.isEmpty()) {
                throw VerifactuCertificateApiException.badRequest(
                        "VERIFACTU_CERTIFICATE_REQUIRED",
                        "message.verifactu.certificate.required");
            }
            if (file.getSize() > MAX_CERTIFICATE_BYTES) {
                throw VerifactuCertificateApiException.payloadTooLarge(
                        "VERIFACTU_CERTIFICATE_TOO_LARGE",
                        "message.verifactu.certificate.too_large");
            }
            content = file.getBytes();
            return service.importCertificate(
                    content, password, expectedActiveCertificateId, confirmation, authentication);
        } catch (IOException exception) {
            throw VerifactuCertificateApiException.badRequest(
                    "VERIFACTU_CERTIFICATE_READ_FAILED",
                    "No se pudo leer el certificado PKCS#12");
        } finally {
            if (content != null) {
                Arrays.fill(content, (byte) 0);
            }
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    @DeleteMapping
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(
            @Valid @RequestBody DeleteCertificateRequest request,
            Authentication authentication) {
        service.deleteActive(request.confirmation(), authentication);
    }

    public record DeleteCertificateRequest(@NotBlank String confirmation) {
    }
}
