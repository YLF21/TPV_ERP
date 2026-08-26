package com.tpverp.saas.fiscal;

import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.license.SaasStoreRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalStatusAdminService {
    private static final Duration STALE_AFTER = Duration.ofHours(24);
    private final SaasFiscalStatusRepository statuses;
    private final SaasInstallationRepository installations;
    private final SaasStoreRepository stores;
    private final Clock clock;

    public FiscalStatusAdminService(
            SaasFiscalStatusRepository statuses,
            SaasInstallationRepository installations,
            SaasStoreRepository stores,
            Clock clock) {
        this.statuses = statuses;
        this.installations = installations;
        this.stores = stores;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<FiscalStatusAdminView> all() {
        return inventory(null).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<FiscalStatusAdminView> company(UUID companyId) {
        return inventory(companyId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<FiscalCompanyStatusAdminView> companies() {
        Map<UUID, List<InventoryRow>> grouped = new LinkedHashMap<>();
        for (InventoryRow row : inventory(null)) {
            grouped.computeIfAbsent(row.store().getCompany().getId(), ignored -> new ArrayList<>()).add(row);
        }
        return grouped.values().stream().map(this::companyView).toList();
    }

    private List<InventoryRow> inventory(UUID companyId) {
        List<SaasStore> knownStores = companyId == null
                ? stores.findAll()
                : stores.findByCompany_IdOrderByCodeAsc(companyId);
        List<SaasInstallation> linked = companyId == null
                ? installations.findAllByOrderByLinkedAtDesc()
                : installations.findByCompany_Id(companyId);
        Map<UUID, SaasInstallation> activeByStore = new HashMap<>();
        linked.stream().filter(SaasInstallation::isActive).forEach(installation ->
                activeByStore.merge(installation.getStore().getId(), installation,
                        (left, right) -> left.getLinkedAt().isAfter(right.getLinkedAt()) ? left : right));
        List<SaasFiscalStatus> reported = companyId == null
                ? statuses.findAllByOrderByCompany_NameAscStore_NameAsc()
                : statuses.findByCompany_IdOrderByStore_NameAsc(companyId);
        Map<UUID, SaasFiscalStatus> byInstallation = new HashMap<>();
        for (SaasFiscalStatus status : reported) {
            byInstallation.put(status.getInstallation().getId(), status);
        }
        return knownStores.stream()
                .sorted(Comparator
                        .comparing((SaasStore value) -> value.getCompany().getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(SaasStore::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(SaasStore::getCode, String.CASE_INSENSITIVE_ORDER))
                .map(store -> {
                    SaasInstallation installation = activeByStore.get(store.getId());
                    return new InventoryRow(store, installation,
                            installation == null ? null : byInstallation.get(installation.getId()));
                })
                .toList();
    }

    private FiscalStatusAdminView view(InventoryRow row) {
        SaasInstallation installation = row.installation();
        SaasStore store = row.store();
        SaasFiscalStatus status = row.status();
        if (status == null) {
            return new FiscalStatusAdminView(
                    store.getCompany().getId(), store.getCompany().getName(),
                    store.getCompany().getTaxId(), store.getId(), store.getName(),
                    installation == null ? null : installation.getInstallationId(),
                    installation == null ? null : installation.getInstallationReference(),
                    "UNKNOWN", "UNKNOWN", 0,
                    null, null, null, null, null, null, null, null, true);
        }
        boolean stale = status.getReportedAt().plus(STALE_AFTER).isBefore(clock.instant());
        return new FiscalStatusAdminView(status.getCompany().getId(), status.getCompany().getName(),
                status.getCompany().getTaxId(), status.getStore().getId(), status.getStore().getName(),
                installation.getInstallationId(), installation.getInstallationReference(), status.getEffectiveMode(),
                status.getActivationState(), status.getModeVersion(), status.getModeSince(), status.getActivationDate(),
                status.getPolicyVersion(), status.getRuntimeClass(), status.getEndpointEnvironment(),
                status.getTransportMode(), status.getReportedAt(), status.getReceivedAt(), stale);
    }

    private FiscalCompanyStatusAdminView companyView(List<InventoryRow> rows) {
        SaasStore first = rows.get(0).store();
        Instant now = clock.instant();
        int installationCount = (int) rows.stream()
                .filter(row -> row.installation() != null).count();
        int unlinkedStores = rows.size() - installationCount;
        int stale = (int) rows.stream().filter(row -> row.installation() != null)
                .filter(row -> row.status() == null
                        || row.status().getReportedAt().plus(STALE_AFTER).isBefore(now)).count();
        java.util.Set<String> modes = rows.stream()
                .map(row -> row.status() == null ? "UNKNOWN" : row.status().getEffectiveMode())
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> states = rows.stream()
                .filter(row -> row.status() != null)
                .filter(row -> !row.status().getReportedAt().plus(STALE_AFTER).isBefore(now))
                .map(row -> row.status().getActivationState())
                .collect(java.util.stream.Collectors.toSet());
        String mode = modes.size() == 1 ? modes.iterator().next() : "MIXED";
        String state = states.isEmpty() ? "UNKNOWN" : states.size() == 1 ? states.iterator().next() : "MIXED";
        Instant latest = rows.stream().map(InventoryRow::status).filter(java.util.Objects::nonNull)
                .map(SaasFiscalStatus::getReportedAt).max(Instant::compareTo).orElse(null);
        return new FiscalCompanyStatusAdminView(first.getCompany().getId(), first.getCompany().getName(),
                first.getCompany().getTaxId(), mode, state, rows.size(), installationCount,
                unlinkedStores, stale, latest);
    }

    private record InventoryRow(
            SaasStore store,
            SaasInstallation installation,
            SaasFiscalStatus status) {
    }
}
