package com.tpverp.backend.cash;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.terminal.TerminalRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashTimelineService {
    private final CashTimelineRepository repository;
    private final CurrentOrganization organization;
    private final TerminalRepository terminals;
    private final CashPermissionService permissions;
    private final Clock clock;
    public CashTimelineService(CashTimelineRepository repository, CurrentOrganization organization,
            TerminalRepository terminals,CashPermissionService permissions,Clock clock) {
        this.repository=repository;this.organization=organization;this.terminals=terminals;this.permissions=permissions;this.clock=clock;
    }
    @Transactional(readOnly=true)
    public CashTimelineView timeline(UUID terminalId,LocalDate date,Authentication authentication) {
        permissions.requireCashStatusPermission(authentication);
        var store=organization.currentStore();
        terminals.findByIdAndTiendaId(terminalId,store.getId())
                .orElseThrow(()->new IllegalArgumentException("Terminal no encontrada"));
        var zone=ZoneId.of(store.getTimezone());
        var day=date==null ? LocalDate.now(clock.withZone(zone)) : date;
        var includeAmounts = permissions.canSeeExpectedTotals(authentication);
        var items = repository.find(store.getId(), terminalId,
                day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant());
        if (!includeAmounts) {
            // Opening plus signed movements would reveal the theoretical amount even without the balance column.
            items = items.stream().map(item -> new CashTimelineView.Item(
                    item.id(), item.occurredAt(), item.userId(), item.username(), item.userName(),
                    item.action(), item.concept(), null, null, item.reference(), item.sessionId(), item.cashState(),
                    item.sourceReference())).toList();
        }
        return new CashTimelineView(day, zone.getId(), items);
    }
}
