package com.tpverp.backend.document;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoucherManagementService {

    private final VoucherRepository vouchers;
    private final StoreVoucherConfigurationRepository configurations;
    private final VoucherManagementEventRepository events;
    private final VoucherPrintService printing;
    private final CurrentOrganization organization;
    private final CurrentTerminal currentTerminal;
    private final AuditService audit;
    private final Clock clock;

    public VoucherManagementService(
            VoucherRepository vouchers,
            StoreVoucherConfigurationRepository configurations,
            VoucherManagementEventRepository events,
            VoucherPrintService printing,
            CurrentOrganization organization,
            CurrentTerminal currentTerminal,
            AuditService audit,
            Clock clock) {
        this.vouchers = vouchers;
        this.configurations = configurations;
        this.events = events;
        this.printing = printing;
        this.organization = organization;
        this.currentTerminal = currentTerminal;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ManagementPage list(
            String query,
            VoucherEffectiveStatus status,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("voucher_page_invalid");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("voucher_date_range_invalid");
        }
        var store = organization.currentStore();
        var zone = ZoneId.of(store.getTimezone());
        var now = clock.instant();
        var today = now.atZone(zone).toLocalDate();
        var normalizedQuery = query == null || query.isBlank() ? null : query.trim();
        var result = vouchers.findManagementPage(
                store.getId(),
                normalizedQuery,
                status == null ? null : status.name(),
                from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(),
                today,
                PageRequest.of(page, size));
        return new ManagementPage(
                result.getContent().stream().map(value -> VoucherView.from(value, today)).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Detail detail(String code) {
        var voucher = requireVoucher(code);
        var today = currentStoreDate();
        return new Detail(
                VoucherView.from(voucher, today),
                events.findAllByVoucher_IdOrderByOccurredAtDesc(voucher.id()).stream()
                        .map(ManagementEventView::from)
                        .toList());
    }

    @Transactional(readOnly = true)
    public ConfigurationView configuration() {
        return ConfigurationView.from(configurationEntity());
    }

    @Transactional
    public ConfigurationView updateConfiguration(
            VoucherExpirationMode expirationMode,
            int validityDays,
            Authentication authentication) {
        var configuration = configurationEntity();
        var previousDays = configuration.validityDays();
        var previousMode = configuration.expirationMode();
        configuration.update(expirationMode, validityDays);
        var saved = configurations.save(configuration);
        if (previousDays != validityDays || previousMode != expirationMode) {
            var operator = organization.currentUser(authentication);
            audit.record("VOUCHER_VALIDITY_CONFIGURATION_CHANGED", AuditResult.EXITO, Map.of(
                    "storeId", saved.storeId().toString(),
                    "previousExpirationMode", previousMode.name(),
                    "expirationMode", expirationMode.name(),
                    "previousValidityDays", previousDays,
                    "validityDays", validityDays,
                    "operatorId", operator.getId().toString(),
                    "operatorUsername", operator.getUserName()));
        }
        return ConfigurationView.from(saved);
    }

    @Transactional
    public Detail reactivate(
            String code,
            LocalDate expiresOn,
            String reason,
            Authentication authentication) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new IllegalArgumentException("voucher_reactivation_reason_invalid");
        }
        var storeId = organization.currentStore().getId();
        var voucher = vouchers.findLockedByTiendaIdAndCode(storeId, normalizedCode(code))
                .orElseThrow(() -> new IllegalArgumentException("vale no encontrado"));
        var now = clock.instant();
        var today = currentStoreDate();
        var previousExpiration = voucher.expiresOn();
        voucher.reactivate(expiresOn, today);
        vouchers.save(voucher);
        var operator = organization.currentUser(authentication);
        var terminalId = optionalTerminalId(authentication);
        events.save(new VoucherManagementEvent(
                voucher,
                VoucherManagementEventType.REACTIVATED,
                operator.getId(),
                terminalId,
                now,
                reason,
                Map.of(
                        "previousExpiration", previousExpiration.toString(),
                        "expiration", expiresOn.toString(),
                        "operatorUsername", operator.getUserName())));
        var auditDetails = new LinkedHashMap<String, Object>();
        auditDetails.put("voucherId", voucher.id().toString());
        auditDetails.put("expiration", expiresOn.toString());
        auditDetails.put("operatorId", operator.getId().toString());
        addTerminalIfPresent(auditDetails, terminalId);
        audit.record("VOUCHER_REACTIVATED", AuditResult.EXITO,
                Map.copyOf(auditDetails));
        return detail(code);
    }

    @Transactional(readOnly = true)
    public VoucherPrintService.PrintedVoucher printDocument(String code) {
        return printing.render(requireVoucher(code));
    }

    @Transactional
    public Detail recordPrintResult(
            String code,
            boolean success,
            Authentication authentication) {
        var voucher = requireVoucher(code);
        var now = clock.instant();
        var operator = organization.currentUser(authentication);
        var terminalId = optionalTerminalId(authentication);
        var type = success
                ? VoucherManagementEventType.REPRINTED
                : VoucherManagementEventType.REPRINT_FAILED;
        events.save(new VoucherManagementEvent(
                voucher,
                type,
                operator.getId(),
                terminalId,
                now,
                null,
                Map.of("operatorUsername", operator.getUserName())));
        var auditDetails = new LinkedHashMap<String, Object>();
        auditDetails.put("voucherId", voucher.id().toString());
        auditDetails.put("operatorId", operator.getId().toString());
        addTerminalIfPresent(auditDetails, terminalId);
        audit.record(success ? "VOUCHER_REPRINTED" : "VOUCHER_REPRINT_FAILED",
                success ? AuditResult.EXITO : AuditResult.FALLO,
                Map.copyOf(auditDetails));
        return detail(code);
    }

    private StoreVoucherConfiguration configurationEntity() {
        var storeId = organization.currentStore().getId();
        return configurations.findById(storeId)
                .orElseGet(() -> new StoreVoucherConfiguration(storeId));
    }

    private Voucher requireVoucher(String code) {
        return vouchers.findByTiendaIdAndCodeIgnoreCase(
                        organization.currentStore().getId(), normalizedCode(code))
                .orElseThrow(() -> new IllegalArgumentException("vale no encontrado"));
    }

    private static String normalizedCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("vale no encontrado");
        }
        return code.trim();
    }

    private UUID optionalTerminalId(Authentication authentication) {
        try {
            return currentTerminal.terminalId(authentication);
        } catch (IllegalStateException noTerminalForManagementSession) {
            return null;
        }
    }

    private static void addTerminalIfPresent(
            Map<String, Object> details,
            UUID terminalId) {
        if (terminalId != null) {
            details.put("terminalId", terminalId.toString());
        }
    }

    private LocalDate currentStoreDate() {
        var store = organization.currentStore();
        return clock.instant().atZone(ZoneId.of(store.getTimezone())).toLocalDate();
    }

    public record ManagementPage(
            List<VoucherView> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    public record Detail(VoucherView voucher, List<ManagementEventView> events) {
    }

    public record ManagementEventView(
            VoucherManagementEventType type,
            UUID userId,
            String operatorUsername,
            UUID terminalId,
            Instant occurredAt,
            String reason) {

        static ManagementEventView from(VoucherManagementEvent event) {
            var username = event.detail().get("operatorUsername");
            return new ManagementEventView(
                    event.type(), event.userId(),
                    username == null ? null : username.toString(),
                    event.terminalId(), event.occurredAt(), event.reason());
        }
    }

    public record ConfigurationView(
            UUID storeId,
            VoucherExpirationMode expirationMode,
            int validityDays) {
        static ConfigurationView from(StoreVoucherConfiguration value) {
            return new ConfigurationView(
                    value.storeId(), value.expirationMode(), value.validityDays());
        }
    }
}
