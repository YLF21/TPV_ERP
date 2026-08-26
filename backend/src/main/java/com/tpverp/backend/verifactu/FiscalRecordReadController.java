package com.tpverp.backend.verifactu;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** General fiscal-record catalogue used by the APP GESTION redesign. */
@RestController
@RequestMapping("/api/v1/verifactu/admin/records")
@PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAuthority('VERIFACTU_READ'))")
public class FiscalRecordReadController {

    private final FiscalRecordReadService service;

    public FiscalRecordReadController(FiscalRecordReadService service) {
        this.service = service;
    }

    public FiscalRecordReadPage records(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) FiscalRecordOperation operation,
            @RequestParam(required = false) FiscalDocumentType documentType,
            @RequestParam(name = "documentNumber", required = false) String documentNumber,
            @RequestParam(name = "number", required = false) String number,
            @RequestParam(name = "fiscalMode", required = false) FiscalMode fiscalMode,
            @RequestParam(name = "mode", required = false) FiscalMode mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        if (documentNumber != null && number != null
                && !documentNumber.trim().equals(number.trim())) {
            throw new IllegalArgumentException("documentNumber y number no pueden diferir");
        }
        if (fiscalMode != null && mode != null && fiscalMode != mode) {
            throw new IllegalArgumentException("fiscalMode y mode no pueden diferir");
        }
        var effectiveDocumentNumber = documentNumber == null ? number : documentNumber;
        return service.records(dateFrom, dateTo, operation, documentType, effectiveDocumentNumber,
                fiscalMode == null ? mode : fiscalMode, page, size);
    }

    @GetMapping
    public FiscalRecordReadPage records(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) FiscalRecordOperation operation,
            @RequestParam(required = false) FiscalDocumentType documentType,
            @RequestParam(name = "documentNumber", required = false) String documentNumber,
            @RequestParam(name = "number", required = false) String number,
            @RequestParam(name = "fiscalMode", required = false) FiscalMode fiscalMode,
            @RequestParam(name = "mode", required = false) FiscalMode mode,
            @RequestParam(defaultValue = "PREFIX") FiscalRecordNumberMatch numberMatch,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        if (documentNumber != null && number != null
                && !documentNumber.trim().equals(number.trim())) {
            throw new IllegalArgumentException("documentNumber y number no pueden diferir");
        }
        if (fiscalMode != null && mode != null && fiscalMode != mode) {
            throw new IllegalArgumentException("fiscalMode y mode no pueden diferir");
        }
        var effectiveDocumentNumber = documentNumber == null ? number : documentNumber;
        return service.records(dateFrom, dateTo, operation, documentType, effectiveDocumentNumber,
                numberMatch, fiscalMode == null ? mode : fiscalMode, page, size);
    }

    public FiscalRecordReadCursorPage recordsCursor(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) FiscalRecordOperation operation,
            @RequestParam(required = false) FiscalDocumentType documentType,
            @RequestParam(name = "documentNumber", required = false) String documentNumber,
            @RequestParam(name = "number", required = false) String number,
            @RequestParam(name = "fiscalMode", required = false) FiscalMode fiscalMode,
            @RequestParam(name = "mode", required = false) FiscalMode mode,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String cursor) {
        return recordsCursor(dateFrom, dateTo, operation, documentType, documentNumber, number,
                fiscalMode, mode, FiscalRecordNumberMatch.PREFIX, size, cursor);
    }

    @GetMapping("/cursor")
    public FiscalRecordReadCursorPage recordsCursor(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) FiscalRecordOperation operation,
            @RequestParam(required = false) FiscalDocumentType documentType,
            @RequestParam(name = "documentNumber", required = false) String documentNumber,
            @RequestParam(name = "number", required = false) String number,
            @RequestParam(name = "fiscalMode", required = false) FiscalMode fiscalMode,
            @RequestParam(name = "mode", required = false) FiscalMode mode,
            @RequestParam(defaultValue = "PREFIX") FiscalRecordNumberMatch numberMatch,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String cursor) {
        if (documentNumber != null && number != null
                && !documentNumber.trim().equals(number.trim())) {
            throw new IllegalArgumentException("documentNumber y number no pueden diferir");
        }
        if (fiscalMode != null && mode != null && fiscalMode != mode) {
            throw new IllegalArgumentException("fiscalMode y mode no pueden diferir");
        }
        var effectiveDocumentNumber = documentNumber == null ? number : documentNumber;
        return service.recordsCursor(dateFrom, dateTo, operation, documentType,
                effectiveDocumentNumber, numberMatch, fiscalMode == null ? mode : fiscalMode,
                size, cursor);
    }

    @GetMapping("/{recordId}")
    public FiscalRecordDetailView record(@PathVariable UUID recordId) {
        return service.record(recordId);
    }
}
