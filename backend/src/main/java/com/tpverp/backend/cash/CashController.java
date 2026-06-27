package com.tpverp.backend.cash;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.CASH_CONFIGURE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.CASH_OPERATE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.CASH_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_CUENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;

import java.math.BigDecimal;
import java.time.Instant;
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

@RestController
@RequestMapping("/api/v1/cash")
public class CashController {

    private final CashSessionService sessions;
    private final CashReceiptService receipts;
    private final CashReportService reports;

    public CashController(
            CashSessionService sessions,
            CashReceiptService receipts,
            CashReportService reports) {
        this.sessions = sessions;
        this.receipts = receipts;
        this.reports = reports;
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('"
            + GESTION_VENTAS + "','" + CASH_OPERATE + "','" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public CashSessionView status(
            @RequestParam UUID terminalId,
            Authentication authentication) {
        return sessions.status(terminalId, authentication);
    }

    @PostMapping("/sessions/open")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_VENTAS + "','" + CASH_OPERATE + "')")
    public CashSessionView open(
            @RequestBody CashOpenRequest request,
            Authentication authentication) {
        return sessions.open(request.terminalId(), authentication);
    }

    @PostMapping("/sessions/close")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_VENTAS + "','" + CASH_OPERATE + "')")
    public CashSessionView close(
            @RequestBody CloseRequest request,
            Authentication authentication) {
        return sessions.close(request.terminalId(), request.toServiceRequest(), authentication);
    }

    @PostMapping("/movements/entry")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_VENTAS + "','" + CASH_OPERATE + "')")
    public CashMovementView entry(
            @RequestBody EntryRequest request,
            Authentication authentication) {
        return sessions.entry(request.terminalId(), request.toServiceRequest(), authentication);
    }

    @PostMapping("/movements/withdrawal")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_VENTAS + "','" + CASH_OPERATE + "')")
    public CashMovementView withdrawal(
            @RequestBody WithdrawalRequest request,
            Authentication authentication) {
        return sessions.withdrawal(request.terminalId(), request.toServiceRequest(), authentication);
    }

    @PostMapping("/movements/between-sessions")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + GESTION_CUENTAS + "','" + CASH_CONFIGURE + "')")
    public CashMovementView betweenSessions(
            @RequestBody WithdrawalRequest request,
            Authentication authentication) {
        return sessions.betweenSessions(request.terminalId(), request.toServiceRequest(), authentication);
    }

    @GetMapping("/receipts/withdrawals/{movementId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('"
            + GESTION_VENTAS + "','" + CASH_OPERATE + "','" + GESTION_CUENTAS + "','" + CASH_READ + "')")
    public CashReceiptView withdrawalReceipt(
            @PathVariable UUID movementId,
            Authentication authentication) {
        return receipts.withdrawalReceipt(movementId, authentication);
    }

    @GetMapping("/receipts/sessions/{sessionId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('"
            + GESTION_VENTAS + "','" + CASH_OPERATE + "','" + GESTION_CUENTAS + "','" + CASH_READ + "')")
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
            String authorizerUsername,
            String authorizerPassword,
            List<CashDenominationCommand> denominations) {

        CashEntryRequest toServiceRequest() {
            return new CashEntryRequest(amount, comment, authorizerUsername, authorizerPassword, denominations);
        }
    }

    public record WithdrawalRequest(
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
            List<CashDenominationCommand> finalWithdrawalDenominations) {

        CashCloseRequest toServiceRequest() {
            return new CashCloseRequest(
                    retainedFund,
                    retainedFundDenominations,
                    finalWithdrawalAmount,
                    finalWithdrawalComment,
                    finalWithdrawalDenominations);
        }
    }
}
