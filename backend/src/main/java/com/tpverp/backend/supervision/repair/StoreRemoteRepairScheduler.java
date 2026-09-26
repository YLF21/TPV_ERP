package com.tpverp.backend.supervision.repair;

import com.tpverp.backend.licensing.SaasLicenseIdentityResolver;
import com.tpverp.backend.supervision.StoreFailurePublisher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StoreRemoteRepairScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(StoreRemoteRepairScheduler.class);
    private final StoreFailurePublisher sites;
    private final SaasLicenseIdentityResolver identities;
    private final StoreRemoteRepairService repairs;
    private final ObjectProvider<RemoteRepairClient> clients;
    private final Environment environment;

    public StoreRemoteRepairScheduler(StoreFailurePublisher sites, SaasLicenseIdentityResolver identities,
            StoreRemoteRepairService repairs, ObjectProvider<RemoteRepairClient> clients, Environment environment) {
        this.sites = sites; this.identities = identities; this.repairs = repairs; this.clients = clients; this.environment = environment;
    }

    @Scheduled(fixedDelayString = "${tpv.sync.remote-repair-delay-ms:15000}",
            initialDelayString = "${tpv.sync.remote-repair-initial-delay-ms:30000}")
    public void tick() {
        if (!environment.getProperty("tpv.sync.worker-enabled", Boolean.class, false)
                || !environment.getProperty("tpv.sync.remote-repair-enabled", Boolean.class, false)) return;
        RemoteRepairClient client = clients.getIfAvailable();
        if (client == null) return;
        Map<UUID, List<StoreRemoteRepairService.AuthorizedSite>> installations = new LinkedHashMap<>();
        try {
            for (var site : sites.sites()) {
                try {
                    var identity = identities.resolve(site.companyId(), site.storeId());
                    installations.computeIfAbsent(site.installationId(), ignored -> new ArrayList<>())
                            .add(new StoreRemoteRepairService.AuthorizedSite(site, identity.companyId(), identity.storeId()));
                } catch (RuntimeException failure) { LOG.warn("REMOTE_REPAIR_SCOPE_UNAVAILABLE"); }
            }
        } catch (RuntimeException failure) { LOG.warn("REMOTE_REPAIR_SITES_UNAVAILABLE"); return; }
        installationLoop: for (var entry : installations.entrySet()) {
            UUID installation = entry.getKey();
            List<StoreRemoteRepairService.AuthorizedSite> allowed = List.copyOf(entry.getValue());
            // First drain durable receipts, including commands no longer returned by central claim.
            try {
                for (UUID commandId : repairs.pending(installation)) {
                    RemoteRepairResult result;
                    try { result = repairs.refresh(installation, commandId, allowed); }
                    catch (RuntimeException failure) { LOG.warn("REMOTE_REPAIR_RESULT_PENDING"); continue; }
                    try { report(client, result); }
                    catch (RuntimeException failure) {
                        // One unavailable endpoint must not consume 50 timeouts before another installation is polled.
                        LOG.warn("REMOTE_REPAIR_RESULT_PENDING");
                        continue installationLoop;
                    }
                }
            } catch (RuntimeException failure) { LOG.warn("REMOTE_REPAIR_LEDGER_UNAVAILABLE"); }
            try {
                for (RemoteRepairCommand command : client.claim(installation)) {
                    RemoteRepairResult result;
                    try { result = repairs.accept(installation, allowed, command); }
                    catch (RuntimeException failure) { LOG.warn("REMOTE_REPAIR_COMMAND_PENDING"); continue; }
                    try { report(client, result); }
                    catch (RuntimeException failure) {
                        LOG.warn("REMOTE_REPAIR_RESULT_PENDING");
                        continue installationLoop;
                    }
                }
            } catch (RuntimeException failure) { LOG.warn("REMOTE_REPAIR_CLAIM_UNAVAILABLE"); }
        }
    }

    private void report(RemoteRepairClient client, RemoteRepairResult result) {
        if (result == null) return;
        client.report(result);
        repairs.acknowledge(result);
    }
}
