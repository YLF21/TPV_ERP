package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SaasSessionTokenStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void sharesSessionsAcrossInstancesAndRotatesTokenOnRefresh() {
        var state = new InMemorySecurityStateStore();
        var firstNode = store(state, Clock.fixed(NOW, ZoneOffset.UTC));
        var secondNode = store(state, Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));

        var issued = firstNode.issue("admin", "ADMIN");
        assertThat(secondNode.username(issued.token(), "admin")).contains("ADMIN");

        var refreshed = secondNode.refresh(issued.token()).orElseThrow();
        assertThat(firstNode.username(issued.token(), "admin")).isEmpty();
        assertThat(firstNode.username(refreshed.issued().token(), "admin")).contains("ADMIN");
    }

    @Test
    void rejectsExpiredSessionAndGlobalLogoutRevokesEveryTokenForUser() {
        var state = new InMemorySecurityStateStore();
        var issuingNode = store(state, Clock.fixed(NOW, ZoneOffset.UTC));
        var first = issuingNode.issue("tenant", "customer");
        var second = issuingNode.issue("tenant", "customer");
        var other = issuingNode.issue("tenant", "other");

        assertThat(issuingNode.revokeAllForToken(first.token())).isTrue();
        assertThat(issuingNode.username(first.token(), "tenant")).isEmpty();
        assertThat(issuingNode.username(second.token(), "tenant")).isEmpty();
        assertThat(issuingNode.username(other.token(), "tenant")).contains("other");

        var expiredNode = store(state, Clock.fixed(NOW.plus(Duration.ofHours(9)), ZoneOffset.UTC));
        assertThat(expiredNode.username(other.token(), "tenant")).isEmpty();
    }

    private static SaasSessionTokenStore store(SecurityStateStore state, Clock clock) {
        return new SaasSessionTokenStore(clock, state, Duration.ofHours(8));
    }
}
