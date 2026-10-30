package com.tpverp.backend.pdawork;

import com.tpverp.backend.shared.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pda-observability")
public class PdaOperationalObservabilityController {
    private final PdaOperationalObservabilityService service;

    public PdaOperationalObservabilityController(PdaOperationalObservabilityService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_ALMACEN')")
    public PdaOperationalObservabilityService.Dashboard dashboard(
            @RequestParam(defaultValue = "24") @Min(1) @Max(720) int windowHours,
            @RequestParam(defaultValue = "30") @Min(5) @Max(1440) int inactiveMinutes,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @RequestParam(required = false) UUID terminalId) {
        return service.dashboard(windowHours, inactiveMinutes, page, size, terminalId);
    }

    @PostMapping("/events")
    @PreAuthorize("hasAnyRole('ADMIN','PDA') or hasAnyAuthority('GESTION_ALMACEN','STOCK_READ','STOCK_ADJUST','STOCK_TRANSFER')")
    public ResponseEntity<PdaOperationalObservabilityService.AcceptedEvent> record(
            @Valid @RequestBody EventRequest body, Authentication authentication, HttpServletRequest request) {
        var result = service.record(authentication, body.command(), CorrelationIdFilter.getOrCreate(request));
        return result.duplicate() ? ResponseEntity.ok(result) : ResponseEntity.status(201).body(result);
    }

    public record EventRequest(
            @NotNull UUID clientEventId,
            @NotNull PdaOperationalEventType type,
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_.-]+") String code,
            UUID workId,
            @Min(0) @Max(120000) Long durationMs,
            @NotNull Instant occurredAt,
            @Size(min = 8, max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String traceId) {
        PdaOperationalObservabilityService.RecordCommand command() {
            return new PdaOperationalObservabilityService.RecordCommand(
                    clientEventId, type, code, workId, durationMs, occurredAt, traceId);
        }
    }
}
