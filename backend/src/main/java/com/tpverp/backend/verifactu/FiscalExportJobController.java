package com.tpverp.backend.verifactu;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.APP_GESTION_ACCESS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VERIFACTU_MANAGE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VERIFACTU_READ;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.PermissionChecks;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/fiscal/export-jobs")
@PreAuthorize("hasRole('ADMIN') or (hasAuthority('" + APP_GESTION_ACCESS + "') and "
        + "hasAuthority('" + VERIFACTU_READ + "') and hasAuthority('" + VERIFACTU_MANAGE + "'))")
public class FiscalExportJobController {
    private final FiscalExportJobService jobs;
    private final FiscalExportJobLauncher launcher;
    private final CurrentOrganization organization;

    public FiscalExportJobController(FiscalExportJobService jobs,
            FiscalExportJobLauncher launcher, CurrentOrganization organization) {
        this.jobs = jobs;
        this.launcher = launcher;
        this.organization = organization;
    }

    @PostMapping
    public FiscalExportJobView create(@Valid @RequestBody FiscalExportJobRequest request,
            Authentication authentication) {
        var user = organization.currentUser(authentication);
        var view = jobs.create(request, user.getId().toString());
        launcher.launch(view.id());
        return view;
    }

    @GetMapping
    public Page<FiscalExportJobView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        var user = organization.currentUser(authentication);
        return jobs.list(page, size, user.getId().toString(), PermissionChecks.hasRole(authentication, "ADMIN"));
    }

    @PostMapping("/{id}/retry")
    public FiscalExportJobView retry(@PathVariable UUID id, Authentication authentication) {
        var user = organization.currentUser(authentication);
        var admin = PermissionChecks.hasRole(authentication, "ADMIN");
        var view = jobs.retry(id, user.getId().toString(), admin);
        launcher.launch(view.id());
        return view;
    }

    @GetMapping("/{id}")
    public FiscalExportJobView status(@PathVariable UUID id, Authentication authentication) {
        var user = organization.currentUser(authentication);
        return jobs.status(id, user.getId().toString(), PermissionChecks.hasRole(authentication, "ADMIN"));
    }

    @PostMapping("/{id}/download-token")
    public FiscalExportDownloadTokenView issueDownloadToken(@PathVariable UUID id,
            Authentication authentication) {
        var user = organization.currentUser(authentication);
        var token = jobs.issueDownloadToken(id, user.getId().toString(),
                PermissionChecks.hasRole(authentication, "ADMIN"));
        return new FiscalExportDownloadTokenView(token);
    }

    @GetMapping(value = "/{id}/download", produces = "application/zip")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID id,
            Authentication authentication) {
        var user = organization.currentUser(authentication);
        var handle = jobs.openAuthorizedDownload(id, user.getId().toString(),
                PermissionChecks.hasRole(authentication, "ADMIN"));
        var file = handle.download();
        StreamingResponseBody body = output -> {
            try (var input = handle.openStream()) {
                input.transferTo(output);
            } finally {
                try {
                    handle.close();
                } catch (java.io.IOException ignored) {
                    // The response already owns the primary streaming result.
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.fileName() + "\"")
                .body(body);
    }
}
