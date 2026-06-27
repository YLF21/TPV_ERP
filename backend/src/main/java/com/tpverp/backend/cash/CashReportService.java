package com.tpverp.backend.cash;

import com.tpverp.backend.document.Money;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashReportService {

    private final CashMovementRepository movements;
    private final CashSessionRepository sessions;
    private final CashStoreConfigRepository configs;
    private final CurrentOrganization organization;
    private final CashPermissionService permissions;

    public CashReportService(
            CashMovementRepository movements,
            CashSessionRepository sessions,
            CashStoreConfigRepository configs,
            CurrentOrganization organization,
            CashPermissionService permissions) {
        this.movements = movements;
        this.sessions = sessions;
        this.configs = configs;
        this.organization = organization;
        this.permissions = permissions;
    }

    // Aggregates cash activity for the current store; optional storeId must match that store.
    @Transactional(readOnly = true)
    public CashReportView report(
            UUID terminalId,
            UUID storeId,
            Instant from,
            Instant to,
            Authentication authentication) {
        permissions.requireReportPermission(authentication);
        var currentStore = organization.currentStore();
        var reportStoreId = storeId == null ? currentStore.getId() : storeId;
        if (!reportStoreId.equals(currentStore.getId())) {
            throw new IllegalArgumentException("Store no encontrada");
        }
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("Rango de fechas de caja no valido");
        }
        var reportMovements = terminalId == null
                ? movements.findAllByTiendaIdAndCreadoEnBetweenOrderByCreadoEnAsc(reportStoreId, from, to)
                : movements.findAllByTiendaIdAndTerminalIdAndCreadoEnBetweenOrderByCreadoEnAsc(
                        reportStoreId, terminalId, from, to);
        var closedSessions = terminalId == null
                ? sessions.findAllByTiendaIdAndClosedAtBetweenOrderByClosedAtDesc(reportStoreId, from, to)
                : sessions.findAllByTiendaIdAndTerminalIdAndClosedAtBetweenOrderByClosedAtDesc(
                        reportStoreId, terminalId, from, to);
        return new CashReportView(
                terminalId,
                reportStoreId,
                from,
                to,
                totalsByType(reportMovements),
                totalRetainedFunds(closedSessions),
                totalDiscrepancies(closedSessions));
    }

    @Transactional(readOnly = true)
    public CashStoreConfigView config(Authentication authentication) {
        permissions.requireConfigPermission(authentication);
        return CashStoreConfigView.from(configForCurrentStore());
    }

    @Transactional
    public CashStoreConfigView updateConfig(CashStoreConfigRequest request, Authentication authentication) {
        permissions.requireConfigPermission(authentication);
        request.validateComplete();
        var config = configForCurrentStore();
        config.update(
                request.discrepancyTolerance(),
                request.requireEntryBreakdown(),
                request.requireWithdrawalBreakdown(),
                request.requireClosingBreakdown());
        return CashStoreConfigView.from(configs.save(config));
    }

    private CashStoreConfig configForCurrentStore() {
        var storeId = organization.currentStore().getId();
        return configs.findById(storeId).orElseGet(() -> new CashStoreConfig(storeId));
    }

    private EnumMap<CashMovementType, BigDecimal> totalsByType(List<CashMovement> reportMovements) {
        var totals = new EnumMap<CashMovementType, BigDecimal>(CashMovementType.class);
        for (var movement : reportMovements) {
            totals.merge(movement.getType(), movement.getAmount(), BigDecimal::add);
        }
        totals.replaceAll((type, amount) -> Money.euros(amount));
        return totals;
    }

    private BigDecimal totalRetainedFunds(List<CashSession> closedSessions) {
        return closedSessions.stream()
                .map(CashSession::getRetainedFund)
                .filter(amount -> amount != null)
                .reduce(Money.euros("0"), BigDecimal::add);
    }

    private BigDecimal totalDiscrepancies(List<CashSession> closedSessions) {
        return closedSessions.stream()
                .map(CashSession::getDiscrepancy)
                .filter(amount -> amount != null)
                .reduce(Money.euros("0"), BigDecimal::add);
    }
}
