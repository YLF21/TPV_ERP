package com.tpverp.backend.verifactu;

import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fiscal/responsible-declaration")
@RequireGestionGroup(GestionGroup.FISCAL)
public class FiscalResponsibleDeclarationController {

    private final FiscalResponsibleDeclarationService declarations;

    public FiscalResponsibleDeclarationController(FiscalResponsibleDeclarationService declarations) {
        this.declarations = declarations;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public FiscalResponsibleDeclarationService.ResponsibleDeclarationStatus status() {
        return declarations.status();
    }

    @GetMapping("/content")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> content(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        final FiscalResponsibleDeclarationService.LoadedDeclaration declaration;
        try {
            declaration = declarations.content();
        } catch (FiscalResponsibleDeclarationService.DeclarationUnavailableException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).headers(safeHeaders()).build();
        }
        var etag = "\"" + declaration.sha256() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .headers(safeHeaders())
                    .build();
        }
        var headers = safeHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentLength(declaration.bytes().length);
        headers.setETag(etag);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(declaration.fileName(), StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(declaration.bytes(), headers, HttpStatus.OK);
    }

    private static HttpHeaders safeHeaders() {
        var headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().mustRevalidate().cachePrivate()
                .maxAge(Duration.ZERO));
        headers.setPragma("no-cache");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'");
        return headers;
    }
}
