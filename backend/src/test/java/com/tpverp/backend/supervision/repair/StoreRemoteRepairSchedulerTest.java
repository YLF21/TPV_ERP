package com.tpverp.backend.supervision.repair;

import static org.mockito.Mockito.*;

import com.tpverp.backend.licensing.SaasLicenseIdentityResolver;
import com.tpverp.backend.supervision.StoreFailurePublisher;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

class StoreRemoteRepairSchedulerTest {
    private final StoreFailurePublisher sites = mock(StoreFailurePublisher.class);
    private final SaasLicenseIdentityResolver identities = mock(SaasLicenseIdentityResolver.class);
    private final StoreRemoteRepairService repairs = mock(StoreRemoteRepairService.class);
    private final RemoteRepairClient client = mock(RemoteRepairClient.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<RemoteRepairClient> clients = mock(ObjectProvider.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final StoreRemoteRepairScheduler scheduler = new StoreRemoteRepairScheduler(sites, identities, repairs, clients, environment);

    @Test void bothExplicitSwitchesAreRequiredAndAbsentHttpClientIsSafe() {
        scheduler.tick();
        environment.withProperty("tpv.sync.worker-enabled", "true"); scheduler.tick();
        environment.withProperty("tpv.sync.worker-enabled", "false").withProperty("tpv.sync.remote-repair-enabled", "true"); scheduler.tick();
        verifyNoInteractions(sites, identities, repairs, clients);
        environment.withProperty("tpv.sync.worker-enabled", "true"); scheduler.tick();
        verify(clients).getIfAvailable(); verifyNoInteractions(sites);
    }

    @Test void groupsClaimByInstallationAndAnOfflineReceiptRemainsPendingWithoutStoppingOtherSites() {
        environment.withProperty("tpv.sync.worker-enabled", "true").withProperty("tpv.sync.remote-repair-enabled", "true");
        when(clients.getIfAvailable()).thenReturn(client);
        UUID installation = UUID.randomUUID();
        var first = new StoreFailurePublisher.Site(UUID.randomUUID(), UUID.randomUUID(), installation);
        var second = new StoreFailurePublisher.Site(first.companyId(), UUID.randomUUID(), installation);
        var next = new StoreFailurePublisher.Site(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(sites.sites()).thenReturn(List.of(first, second, next));
        for (var site : List.of(first, second, next)) {
            when(identities.resolve(site.companyId(), site.storeId())).thenReturn(new SaasLicenseIdentityResolver.SaasIdentity(UUID.randomUUID(), UUID.randomUUID()));
        }
        UUID prior = UUID.randomUUID();
        var result = new RemoteRepairResult(prior, installation, "SUCCEEDED", "SYNC_DELIVERED");
        UUID later = UUID.randomUUID();
        when(repairs.pending(installation)).thenReturn(List.of(prior, later));
        when(repairs.refresh(eq(installation), eq(prior), any())).thenReturn(result);
        doThrow(new IllegalStateException("offline private token")).when(client).report(result);
        var command = new RemoteRepairCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "RETRY_SYNC_OUTBOX", UUID.randomUUID(), 2, Instant.now().plusSeconds(60));
        when(client.claim(installation)).thenReturn(List.of(command));
        scheduler.tick();
        verify(repairs, never()).acknowledge(result);
        verify(client, never()).claim(installation);
        verify(repairs, never()).refresh(eq(installation), eq(later), any());
        verify(client).claim(next.installationId());
        doNothing().when(client).report(result);
        scheduler.tick();
        verify(client, times(2)).report(result);
        verify(repairs).acknowledge(result);
        verify(client).claim(installation);
        verify(repairs).accept(eq(installation), argThat(allowed -> allowed.size() == 2), eq(command));
    }

    @Test void conflictingPayloadIsNeverReportedAndOtherCommandsContinueInTheSameInstallation() {
        environment.withProperty("tpv.sync.worker-enabled", "true").withProperty("tpv.sync.remote-repair-enabled", "true");
        when(clients.getIfAvailable()).thenReturn(client);
        var first = new StoreFailurePublisher.Site(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var second = new StoreFailurePublisher.Site(first.companyId(), UUID.randomUUID(), first.installationId());
        when(sites.sites()).thenReturn(List.of(first, second));
        for (var site : List.of(first, second)) {
            when(identities.resolve(site.companyId(), site.storeId())).thenReturn(new SaasLicenseIdentityResolver.SaasIdentity(UUID.randomUUID(), UUID.randomUUID()));
        }
        var bad = new RemoteRepairCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "RETRY_SYNC_OUTBOX", UUID.randomUUID(), 7, Instant.now().plusSeconds(60));
        var good = new RemoteRepairCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "RETRY_SYNC_OUTBOX", UUID.randomUUID(), 7, Instant.now().plusSeconds(60));
        when(client.claim(first.installationId())).thenReturn(List.of(bad, good));
        when(repairs.accept(eq(first.installationId()), any(), eq(bad))).thenThrow(new IllegalStateException("REMOTE_REPAIR_IMMUTABLE_COMMAND_CONFLICT"));
        var queued = new RemoteRepairResult(good.commandId(), first.installationId(), "RUNNING", "RETRY_QUEUED");
        when(repairs.accept(eq(first.installationId()), any(), eq(good))).thenReturn(queued);
        scheduler.tick();
        verify(client, never()).report(argThat(result -> result.commandId().equals(bad.commandId())));
        verify(client).report(queued);
        verify(repairs).acknowledge(queued);
        verify(repairs).accept(eq(first.installationId()), argThat(allowed -> allowed.size() == 2), eq(good));
        verify(client, times(1)).claim(first.installationId());
    }

    @Test void oneBrokenLicenseDoesNotPreventOtherInstallationPolling() {
        environment.withProperty("tpv.sync.worker-enabled", "true").withProperty("tpv.sync.remote-repair-enabled", "true");
        when(clients.getIfAvailable()).thenReturn(client);
        var broken = new StoreFailurePublisher.Site(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var healthy = new StoreFailurePublisher.Site(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(sites.sites()).thenReturn(List.of(broken, healthy));
        when(identities.resolve(broken.companyId(), broken.storeId())).thenThrow(new IllegalStateException("license failed"));
        when(identities.resolve(healthy.companyId(), healthy.storeId())).thenReturn(new SaasLicenseIdentityResolver.SaasIdentity(UUID.randomUUID(), UUID.randomUUID()));
        scheduler.tick();
        verify(client, never()).claim(broken.installationId());
        verify(client).claim(healthy.installationId());
    }
}
