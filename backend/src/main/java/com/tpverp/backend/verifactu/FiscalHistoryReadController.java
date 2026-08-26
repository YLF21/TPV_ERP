package com.tpverp.backend.verifactu;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only fiscal history endpoints for APP GESTION. */
@RestController
@RequestMapping("/api/v1/fiscal")
@PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and "
        + "hasAuthority('VERIFACTU_READ'))")
public class FiscalHistoryReadController {

    private final FiscalHistoryReadService history;

    public FiscalHistoryReadController(FiscalHistoryReadService history) {
        this.history = history;
    }

    @GetMapping("/exports")
    public List<FiscalExportHistoryView> exports(
            @RequestParam(required = false) Integer limit) {
        return history.exports(limit);
    }

    @GetMapping("/exports/cursor")
    public FiscalHistoryReadCursorPage<FiscalExportHistoryView> exportsCursor(
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return history.exportsCursor(effectiveSize(size, limit), cursor);
    }

    @GetMapping("/required-submissions")
    public List<FiscalRequiredSubmissionHistoryView> requiredSubmissions(
            @RequestParam(required = false) Integer limit) {
        return history.requiredSubmissions(limit);
    }

    @GetMapping("/required-submissions/cursor")
    public FiscalHistoryReadCursorPage<FiscalRequiredSubmissionHistoryView> requiredSubmissionsCursor(
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return history.requiredSubmissionsCursor(effectiveSize(size, limit), cursor);
    }

    private static Integer effectiveSize(Integer size, Integer limit) {
        if (size != null && limit != null && !size.equals(limit)) {
            throw new IllegalArgumentException("size y limit no pueden diferir");
        }
        return size == null ? limit : size;
    }
}
