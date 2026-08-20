package com.tpverp.backend.party.loyalty.category;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralContextResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty("tpv.sync.central-url")
public class MemberCategoryOfficialFeedWorker {
    private static final int PAGE_SIZE = 500;
    private final MemberBalanceCentralContextResolver contexts;
    private final MemberCategoryProjectionStateRepository states;
    private final MemberCategoryBootstrapGateway gateway;
    private final MemberCategoryOfficialSnapshotApplicationService application;

    public MemberCategoryOfficialFeedWorker(
            MemberBalanceCentralContextResolver contexts,
            MemberCategoryProjectionStateRepository states,
            MemberCategoryBootstrapGateway gateway,
            MemberCategoryOfficialSnapshotApplicationService application) {
        this.contexts = contexts;
        this.states = states;
        this.gateway = gateway;
        this.application = application;
    }

    public void runOnce() {
        for (var context : contexts.resolveBootstrapContexts()) {
            try {
                synchronize(context);
            } catch (RuntimeException ignored) {
                // El feed central nunca bloquea la operativa local.
            }
        }
    }

    private void synchronize(MemberBalanceCentralContextResolver.BootstrapContext context) {
        var state = states.findById(context.localStoreId()).orElse(null);
        if (state == null || state.getStatus() != MemberCategoryProjectionStatus.CENTRAL_ACTIVE) {
            return;
        }
        var feed = gateway.officialFeed(
                context.companyId(),
                context.storeId(),
                state.getConfigRevision(),
                state.getConfigCursorId(),
                state.getAssignmentRevision(),
                state.getAssignmentCursorId(),
                PAGE_SIZE);
        if (!feed.isEmpty()) {
            application.applyFeed(context.localCompanyId(), context.localStoreId(), feed);
        }
    }
}
