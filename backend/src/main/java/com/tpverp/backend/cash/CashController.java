package com.tpverp.backend.cash;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tpverp.backend.shared.api.PagedResult;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.CASH_CONFIGURE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.CASH_OPERATE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.CASH_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_CUENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.tpverp.backend.document.template.RenderedDocumentView;

@RestController
@RequestMapping("/api/v1/cash")
public class CashController {

    private final CashSessionService sessions;
    private final CashReceiptService receipts;
    private final CashReportService reports;
    private final CashClosureService closures;
    private final CashCurrentBalanceService currentBalances;
    private final CashOpeningAlertService openingAlerts;
    private final CashAlertService alerts;
    private final CashTimelineService timeline;
    private final CashActivityService activity;

    public CashController(
            CashSessionService sessions,
            CashReceiptService receipts,
            CashReportService reports,
            CashClosureService closures,
            CashCurrentBalanceService currentBalances, CashOpeningAlertService openingAlerts, CashTimelineService timeline,
            CashActivityService activity, CashAlertService alerts) {
        this.sessions = sessions;
        this.receipts = receipts;
        this.reports = reports;
        this.closures = closures;
        this.currentBalances = currentBalances;
        this.openingAlerts = openingAlerts;
        this.timeline = timeline;
        this.activity = activity;
        this.alerts = alerts;
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('"
            + VENTA + "','" + CASH_OPERATE + "','" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public CashSessionView status(
            @RequestParam UUID terminalId,
            Authentication authentication) {
        return sessions.status(terminalId, authentication);
    }

    @PostMapping("/sessions/open")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + VENTA + "','" + CASH_OPERATE + "')")
    public CashSessionView open(
            @Valid @RequestBody CashOpenRequest request,
            Authentication authentication) {
        return sessions.open(request, authentication);
    }

    @PostMapping("/sessions/prepare-sales")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + VENTA + "','" + CASH_OPERATE + "')")
    public CashSalesSessionReadinessView prepareForSales(
            @Valid @RequestBody CashOpenRequest request,
            Authentication authentication) {
        return sessions.prepareForSales(request.terminalId(), authentication);
    }

    @GetMapping("/sessions/readiness")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + VENTA + "','" + CASH_OPERATE + "')")
    public CashSalesSessionReadinessView salesReadiness(
            @RequestParam UUID terminalId,
            Authentication authentication) {
        return sessions.salesReadiness(terminalId, authentication);
    }

    @PostMapping("/sessions/close")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + VENTA + "','" + CASH_OPERATE + "')")
    public CashSessionView close(
            @Valid @RequestBody CloseRequest request,
            Authentication authentication) {
        return sessions.close(request.terminalId(), request.toServiceRequest(), authentication);
    }

    @GetMapping("/sessions/close-operations/{operationId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + VENTA + "','" + CASH_OPERATE + "')")
    public CashCloseOperationView closeOperation(
            @PathVariable UUID operationId,
            @RequestParam UUID terminalId,
            Authentication authentication) {
        return sessions.closeOperation(terminalId, operationId, authentication);
    }

    @PostMapping("/movements/entry")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('"
            + VENTA + "','" + CASH_OPERATE + "','" + GESTION_VENTAS + "','" + GESTION_CUENTAS + "')")
    public CashMovementView entry(
            @Valid @RequestBody EntryRequest request,
            Authentication authentication) {
        return sessions.entry(request.terminalId(), request.toServiceRequest(), authentication);
    }

    @PostMapping("/movements/withdrawal")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('"
            + VENTA + "','" + CASH_OPERATE + "','" + GESTION_VENTAS + "','" + GESTION_CUENTAS + "')")
    public CashMovementView withdrawal(
            @Valid @RequestBody WithdrawalRequest request,
            Authentication authentication) {
        return sessions.withdrawal(request.terminalId(), request.toServiceRequest(), authentication);
    }

    @PostMapping("/movements/between-sessions")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_CUENTAS + "','" + CASH_CONFIGURE + "')")
    public CashMovementView betweenSessions(
            @RequestBody BetweenSessionsRequest request,
            Authentication authentication) {
        return sessions.betweenSessions(request.terminalId(), request.toServiceRequest(), authentication);
    }

    @GetMapping("/receipts/withdrawals/{movementId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('"
            + VENTA + "','" + CASH_OPERATE + "','" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public CashReceiptView withdrawalReceipt(
            @PathVariable UUID movementId,
            Authentication authentication) {
        return receipts.withdrawalReceipt(movementId, authentication);
    }

    @GetMapping("/receipts/withdrawals/{movementId}/print-document")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('"
            + VENTA + "','" + CASH_OPERATE + "','" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public RenderedDocumentView withdrawalPrintDocument(
            @PathVariable UUID movementId,
            Authentication authentication) {
        return receipts.withdrawalPrintDocument(movementId, authentication);
    }

    @GetMapping("/receipts/entries/{movementId}/print-document")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('"
            + VENTA + "','" + CASH_OPERATE + "','" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public RenderedDocumentView entryPrintDocument(
            @PathVariable UUID movementId,
            Authentication authentication) {
        return receipts.entryPrintDocument(movementId, authentication);
    }

    @GetMapping("/receipts/sessions/{sessionId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('"
            + VENTA + "','" + CASH_OPERATE + "','" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public CashReceiptView sessionReceipt(
            @PathVariable UUID sessionId,
            Authentication authentication) {
        return receipts.closeReceipt(sessionId, authentication);
    }

    @GetMapping("/reports")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public CashReportView report(
            @RequestParam(required = false) UUID terminalId,
            @RequestParam(required = false) UUID storeId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            Authentication authentication) {
        return reports.report(terminalId, storeId, from, to, authentication);
    }

    @GetMapping("/activity")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public PagedResult<CashActivityView> activity(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) UUID terminalId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String cashState,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection,
            Authentication authentication) {
        return activity.list(from, to, terminalId, userId, action, cashState, limit, cursor,
                sortBy, sortDirection, authentication);
    }

    @GetMapping("/activity/filter-options")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public CashActivityFilterOptionsView activityFilterOptions(Authentication authentication) {
        return activity.filterOptions(authentication);
    }

    @GetMapping("/closures")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public PagedResult<CashClosureView> closures(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) UUID terminalId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(defaultValue = "false") boolean onlyDiscrepancies,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection,
            Authentication authentication) {
        if ((sortBy == null || sortBy.isBlank()) && (sortDirection == null || sortDirection.isBlank())) {
            return closures.list(
                    from, to, terminalId, userId, onlyDiscrepancies,
                    limit, cursor, authentication);
        }
        return closures.list(
                from, to, terminalId, userId, onlyDiscrepancies,
                limit, cursor, sortBy, sortDirection, authentication);
    }

    @GetMapping("/closures/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public CashClosureView closure(@PathVariable UUID id, Authentication authentication) {
        return closures.detail(id, authentication);
    }

    @GetMapping("/alerts")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public CashAlertPage alerts(@RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to, @RequestParam(required=false) UUID terminalId,
            @RequestParam(required=false) UUID userId, @RequestParam(required=false) String status,
            @RequestParam(required=false) String type, @RequestParam(defaultValue="50") int limit,
            @RequestParam(required=false) String cursor, Authentication authentication) {
        return alerts.list(from,to,terminalId,userId,status,type,limit,cursor,authentication);
    }

    @GetMapping("/alerts/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public CashAlertDetailView alertDetail(@PathVariable UUID id, @RequestParam String type,
            Authentication authentication) {
        return alerts.detail(id,type,authentication);
    }

    @PostMapping("/alerts/{id}/review")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_CUENTAS + "')")
    public CashAlertView reviewAlert(@PathVariable UUID id,
            @Valid @RequestBody AlertReviewRequest request, Authentication authentication) {
        return alerts.review(id,request.type(),request.comment(),request.expectedVersion(),authentication);
    }

    public record AlertReviewRequest(@NotBlank String type, @NotBlank @Size(max=1000) String comment,
            @NotNull @Min(0) Long expectedVersion) { }

    @GetMapping("/opening-alerts")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public CashOpeningAlertPage openingAlerts(@RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to, @RequestParam(required=false) UUID terminalId,
            @RequestParam(required=false) UUID userId, @RequestParam(required=false) String status,
            @RequestParam(defaultValue="50") int limit, @RequestParam(required=false) String cursor,
            Authentication authentication) {
        return openingAlerts.list(from,to,terminalId,userId,status,limit,cursor,authentication);
    }

    @PostMapping("/opening-alerts/{id}/review")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_CUENTAS + "')")
    public CashOpeningAlertView reviewOpeningAlert(@PathVariable UUID id,
            @Valid @RequestBody OpeningAlertReviewRequest request, Authentication authentication) {
        return openingAlerts.review(id,request.comment(),request.expectedVersion(),authentication);
    }

    public record OpeningAlertReviewRequest(@NotBlank @Size(max=1000) String comment,
            @NotNull @Min(0) Long expectedVersion) { }

    @GetMapping("/timeline")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + VENTA + "','" + CASH_OPERATE + "','" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public CashTimelineView timeline(@RequestParam UUID terminalId, @RequestParam(required=false) LocalDate date,
            Authentication authentication) {
        return timeline.timeline(terminalId,date,authentication);
    }

    @GetMapping("/closures/filter-options")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public CashClosureFilterOptionsView closureFilterOptions(Authentication authentication) {
        return closures.filterOptions(authentication);
    }

    @GetMapping("/current-balances")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public CashCurrentBalancesView currentBalances(Authentication authentication) {
        return currentBalances.current(authentication);
    }

    @GetMapping("/config")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_CUENTAS + "','" + CASH_CONFIGURE + "')")
    public CashStoreConfigView config(Authentication authentication) {
        return reports.config(authentication);
    }

    @PutMapping("/config")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_CUENTAS + "','" + CASH_CONFIGURE + "')")
    public CashStoreConfigView updateConfig(
            @RequestBody CashStoreConfigRequest request,
            Authentication authentication) {
        return reports.updateConfig(request, authentication);
    }

    public record EntryRequest(
            UUID terminalId,
            BigDecimal amount,
            String comment,
            @Size(max = 128) String authorizerUsername,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @Size(max = 128) String authorizerPassword,
            List<CashDenominationCommand> denominations) {

        CashEntryRequest toServiceRequest() {
            return new CashEntryRequest(amount, comment, authorizerUsername, authorizerPassword, denominations);
        }

        @Override
        public String toString() {
            return "EntryRequest[terminalId=" + terminalId
                    + ", amount=" + amount
                    + ", authorizerUsername=" + authorizerUsername
                    + ", authorizerPassword=<redacted>]";
        }
    }

    public record WithdrawalRequest(
            @NotNull UUID terminalId,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @NotBlank @Size(max = 500) String comment,
            List<CashDenominationCommand> denominations,
            boolean withdrawal,
            @Size(max = 128) String authorizerUsername,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @Size(max = 128) String authorizerPassword) {

        CashWithdrawalRequest toServiceRequest() {
            return new CashWithdrawalRequest(
                    amount,
                    comment,
                    denominations,
                    withdrawal,
                    authorizerUsername,
                    authorizerPassword);
        }

        @Override
        public String toString() {
            return "WithdrawalRequest[terminalId=" + terminalId
                    + ", amount=" + amount
                    + ", withdrawal=" + withdrawal
                    + ", authorizerUsername=" + authorizerUsername
                    + ", authorizerPassword=<redacted>]";
        }
    }

    public record BetweenSessionsRequest(
            UUID terminalId,
            BigDecimal amount,
            String comment,
            List<CashDenominationCommand> denominations,
            boolean withdrawal) {

        CashWithdrawalRequest toServiceRequest() {
            return new CashWithdrawalRequest(amount, comment, denominations, withdrawal);
        }
    }

    public record CloseRequest(
            UUID terminalId,
            BigDecimal retainedFund,
            List<CashDenominationCommand> retainedFundDenominations,
            BigDecimal finalWithdrawalAmount,
            String finalWithdrawalComment,
            List<CashDenominationCommand> finalWithdrawalDenominations,
            @NotNull @JsonAlias("finalWithdrawalIdempotencyKey") UUID closeOperationId,
            @NotNull UUID reconciliationAttemptId,
            @Size(max = 128) String authorizerUsername,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @Size(max = 128) String authorizerPassword) {

        public CloseRequest(
                UUID terminalId,
                BigDecimal retainedFund,
                List<CashDenominationCommand> retainedFundDenominations,
                BigDecimal finalWithdrawalAmount,
                String finalWithdrawalComment,
                List<CashDenominationCommand> finalWithdrawalDenominations,
                UUID closeOperationId,
                String authorizerUsername,
                String authorizerPassword) {
            this(
                    terminalId,
                    retainedFund,
                    retainedFundDenominations,
                    finalWithdrawalAmount,
                    finalWithdrawalComment,
                    finalWithdrawalDenominations,
                    closeOperationId,
                    UUID.randomUUID(),
                    authorizerUsername,
                    authorizerPassword);
        }

        CashCloseRequest toServiceRequest() {
            return new CashCloseRequest(
                    retainedFund,
                    retainedFundDenominations,
                    finalWithdrawalAmount,
                    finalWithdrawalComment,
                    finalWithdrawalDenominations,
                    closeOperationId,
                    reconciliationAttemptId,
                    authorizerUsername,
                    authorizerPassword);
        }

        @Override
        public String toString() {
            return "CloseRequest[terminalId=" + terminalId
                    + ", retainedFund=" + retainedFund
                    + ", finalWithdrawalAmount=" + finalWithdrawalAmount
                    + ", closeOperationId=" + closeOperationId
                    + ", reconciliationAttemptId=" + reconciliationAttemptId
                    + ", authorizerUsername=" + authorizerUsername
                    + ", authorizerPassword=<redacted>]";
        }
    }
}
