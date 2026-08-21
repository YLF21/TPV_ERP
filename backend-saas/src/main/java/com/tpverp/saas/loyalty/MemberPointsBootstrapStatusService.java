package com.tpverp.saas.loyalty;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberPointsBootstrapStatusService {
    private final SaasMemberPointsBootstrapRepository bootstraps;
    private final SaasMemberPointsBootstrapStoreRepository stores;
    public MemberPointsBootstrapStatusService(SaasMemberPointsBootstrapRepository bootstraps,
            SaasMemberPointsBootstrapStoreRepository stores) {
        this.bootstraps=bootstraps; this.stores=stores;
    }
    @Transactional(readOnly=true)
    public LoyaltyApiModels.PointsBootstrapStatus latest(UUID companyId) {
        return status(bootstraps.findFirstByCompany_IdOrderByCreatedAtDesc(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"No existe bootstrap de puntos")));
    }
    @Transactional(readOnly=true)
    public LoyaltyApiModels.PointsBootstrapStatus byId(UUID id) {
        return status(bootstraps.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Bootstrap de puntos no encontrado")));
    }
    @Transactional(readOnly=true)
    public LoyaltyApiModels.PointsBootstrapStatus status(SaasMemberPointsBootstrap bootstrap) {
        List<SaasMemberPointsBootstrapStore> expected=stores.findByBootstrap_IdOrderByStoreIdAsc(bootstrap.getId()).stream()
            .sorted(Comparator.comparing(value->value.getStoreId().toString()))
            .toList();
        List<UUID> expectedIds=expected.stream().map(SaasMemberPointsBootstrapStore::getStoreId).toList();
        List<UUID> completed=expected.stream().filter(v->v.getCompletedAt()!=null).map(SaasMemberPointsBootstrapStore::getStoreId).toList();
        List<UUID> missing=expected.stream().filter(v->v.getCompletedAt()==null).map(SaasMemberPointsBootstrapStore::getStoreId).toList();
        List<UUID> conflicts=expected.stream().filter(v->v.getConflictReason()!=null).map(SaasMemberPointsBootstrapStore::getStoreId).toList();
        return new LoyaltyApiModels.PointsBootstrapStatus(bootstrap.getId(),bootstrap.getCompanyId(),
            bootstrap.getStatus(),bootstrap.getCutoffAt(),expectedIds,completed,missing,conflicts,
            bootstrap.getConflictReason(),bootstrap.getOfficialRevision(),bootstrap.getCentralWatermark(),
            bootstrap.getCreatedAt(),bootstrap.getCompletedAt());
    }
}
