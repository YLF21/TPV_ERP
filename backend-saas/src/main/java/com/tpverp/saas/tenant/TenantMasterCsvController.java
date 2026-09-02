package com.tpverp.saas.tenant;

import com.tpverp.saas.master.MasterCsvService;
import com.tpverp.saas.master.MasterImportResult;
import com.tpverp.saas.master.MasterSearchPage;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant/erp/{resource}")
public class TenantMasterCsvController {

    private final MasterCsvService service;

    public TenantMasterCsvController(MasterCsvService service) {
        this.service = service;
    }

    @GetMapping(value = "/csv", produces = "text/csv")
    public ResponseEntity<String> exportCsv(@PathVariable String resource) {
        String csv = service.exportCsv(TenantContextHolder.current().companyId(), resource);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(resource + ".csv", StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }

    @PostMapping(value = "/csv", consumes = "text/csv")
    public MasterImportResult importCsv(@PathVariable String resource, @RequestBody String csv) {
        return service.importCsv(TenantContextHolder.current().companyId(), resource, csv);
    }

    @GetMapping("/search")
    public MasterSearchPage search(
            @PathVariable String resource,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.search(TenantContextHolder.current().companyId(), resource, q, page, size);
    }
}
