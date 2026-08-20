package com.tpverp.saas.loyalty;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class MemberPointsBootstrapConflictRecorder {
    private final SaasMemberPointsBootstrapRepository bootstraps;
    private final SaasMemberPointsBootstrapStoreRepository stores;
    public MemberPointsBootstrapConflictRecorder(SaasMemberPointsBootstrapRepository bootstraps,
            SaasMemberPointsBootstrapStoreRepository stores){this.bootstraps=bootstraps;this.stores=stores;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(UUID bootstrapId,String reason,Set<UUID> storeIds){
        SaasMemberPointsBootstrap bootstrap=bootstraps.findForUpdate(bootstrapId).orElse(null);
        if(bootstrap==null || bootstrap.isCompleted()) return;
        bootstrap.markConflict(reason);
        Set<UUID> affectedStores=new TreeSet<>(Comparator.comparing(UUID::toString));
        if(storeIds==null||storeIds.isEmpty()){
            stores.findByBootstrap_IdOrderByStoreIdAsc(bootstrapId).stream()
                .map(SaasMemberPointsBootstrapStore::getStoreId)
                .forEach(affectedStores::add);
        }else{
            affectedStores.addAll(storeIds);
        }
        for(UUID storeId:affectedStores){stores.findByBootstrap_IdAndStoreId(bootstrapId,storeId).ifPresent(v->v.markConflict(reason));}
    }
}
