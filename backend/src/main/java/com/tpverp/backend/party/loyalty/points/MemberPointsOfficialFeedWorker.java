package com.tpverp.backend.party.loyalty.points;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralContextResolver;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.OfficialPointsFeedRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MemberPointsOfficialFeedWorker {
    private static final Logger log = LoggerFactory.getLogger(
            MemberPointsOfficialFeedWorker.class);
    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES_PER_RUN = 4;

    private final MemberBalanceCentralContextResolver contexts;
    private final MemberBalanceCentralGateway central;
    private final MemberPointsProjectionStateRepository states;
    private final MemberPointsOfficialProjectionService projection;

    public MemberPointsOfficialFeedWorker(
            MemberBalanceCentralContextResolver contexts,
            MemberBalanceCentralGateway central,
            MemberPointsProjectionStateRepository states,
            MemberPointsOfficialProjectionService projection) {
        this.contexts = contexts;
        this.central = central;
        this.states = states;
        this.projection = projection;
    }

    public void runOnce() {
        for (var context : contexts.resolveBootstrapContexts()) {
            runContext(context);
        }
    }

    private void runContext(
            MemberBalanceCentralContextResolver.BootstrapContext context) {
        try {
            projection.applyPending(context.localCompanyId(), context.localStoreId());
        } catch (RuntimeException exception) {
            log.warn(
                    "No se pudieron aplicar puntos oficiales pendientes de la tienda {}: {}",
                    context.localStoreId(),
                    exception.getMessage());
        }

        for (int page = 0; page < MAX_PAGES_PER_RUN; page++) {
            var state = states.findById(context.localStoreId()).orElse(null);
            if (state == null
                    || state.getStatus() != MemberPointsProjectionStatus.CENTRAL_ACTIVE) {
                return;
            }
            try {
                var response = central.officialPointsFeed(
                        new OfficialPointsFeedRequest(
                                context.companyId(),
                                context.storeId(),
                                state.getOfficialRevision(),
                                PAGE_SIZE));
                projection.accept(
                        context.localCompanyId(), context.localStoreId(), response);
                projection.applyPending(
                        context.localCompanyId(), context.localStoreId());
                if (!response.hasMore()) {
                    return;
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "No se pudo actualizar el estado oficial de puntos de la tienda {}: {}",
                        context.localStoreId(),
                        exception.getMessage());
                return;
            }
        }
    }
}
