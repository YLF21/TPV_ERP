package com.tpverp.backend.servertransfer;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;

class ServerTransferRequestFilterTest {
    @TempDir Path keys;
    @Test void frozenServerRejectsBusinessButKeepsAuthenticatedMaintenanceReachable() throws Exception {
        var journal = new ServerTransferJournal(keys);
        var filter = new ServerTransferRequestFilter(new ServerTransferFence(journal));
        var business = new MockHttpServletRequest("POST", "/api/v1/documents");
        var normal = new MockHttpServletResponse(); var normalChain = new MockFilterChain();
        filter.doFilter(business, normal, normalChain);
        assertThat(normalChain.getRequest()).isSameAs(business);
        journal.freeze(UUID.randomUUID());
        var blocked = new MockHttpServletResponse(); var blockedChain = new MockFilterChain();
        filter.doFilter(business, blocked, blockedChain);
        assertThat(blocked.getStatus()).isEqualTo(423);
        assertThat(blocked.getContentAsString()).contains("SERVER_TRANSFER_FROZEN");
        assertThat(blockedChain.getRequest()).isNull();
        var maintenance = new MockHttpServletRequest("GET", "/api/v1/server-transfer/status");
        var maintenanceChain = new MockFilterChain();
        filter.doFilter(maintenance, new MockHttpServletResponse(), maintenanceChain);
        assertThat(maintenanceChain.getRequest()).isSameAs(maintenance);
    }
}
