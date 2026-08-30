package com.tpverp.backend.verifactu;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.util.MultiValueMap;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RestController;

/** Narrow, capability-authenticated streaming endpoint. */
@RestController
@RequestMapping("/api/v1/fiscal/export-jobs/download")
public class FiscalExportDownloadController {
    private final FiscalExportJobService jobs;

    public FiscalExportDownloadController(FiscalExportJobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = "application/zip")
    public ResponseEntity<FileSystemResource> download(@RequestBody MultiValueMap<String, String> form,
            HttpServletRequest request) {
        if (request.getQueryString() != null || form == null || form.size() != 1
                || !form.containsKey("token") || form.get("token") == null
                || form.get("token").size() != 1) {
            throw new IllegalArgumentException("fiscal_export_download_token_invalid");
        }
        var token = form.getFirst("token");
        var handle = jobs.consumeDownloadTokenForStreaming(token);
        try {
            var file = handle.download();
            var disposition = ContentDisposition.attachment()
                    .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8).build();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header("Referrer-Policy", "no-referrer")
                    .header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .contentLength(file.size())
                    .body(new VerifiedDownloadResource(handle));
        } catch (RuntimeException | Error exception) {
            close(handle);
            throw exception;
        }
    }

    /**
     * Keeps Spring's existing response type while preventing a path reopen.
     * Spring's resource converter closes the returned stream after writing it;
     * the portable Resource contract has no callback for a response that never
     * asks for the stream, so construction failures are closed by the caller
     * and transaction rollbacks are handled by the service synchronization.
     */
    private static final class VerifiedDownloadResource extends FileSystemResource {
        private final FiscalExportJobService.DownloadHandle handle;

        private VerifiedDownloadResource(FiscalExportJobService.DownloadHandle handle) {
            super(handle.download().path());
            this.handle = handle;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return handle.openStream();
        }

        @Override
        public long contentLength() {
            return handle.download().size();
        }
    }

    private static void close(FiscalExportJobService.DownloadHandle handle) {
        try {
            handle.close();
        } catch (IOException ignored) {
            // Preserve the response construction error.
        }
    }
}
