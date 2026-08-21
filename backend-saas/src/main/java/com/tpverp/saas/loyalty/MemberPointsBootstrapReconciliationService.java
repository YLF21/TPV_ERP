package com.tpverp.saas.loyalty;

import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.sync.MemberPointsSyncProjector;
import jakarta.persistence.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberPointsBootstrapReconciliationService {
    public enum Outcome { CATCHING_UP, COMPLETED }
    private static final String ABSORBED="ABSORBED_OPERATIONS", REPLAY="REPLAY_OPERATIONS";
    private final SaasMemberPointsBootstrapRepository bootstraps; private final SaasMemberPointsBootstrapStoreRepository stores;
    private final SaasMemberPointsBootstrapSnapshotRepository snapshots;
    private final SaasMemberPointsBootstrapStagingAccountRepository stagingAccounts;
    private final SaasMemberPointsBootstrapStagingOperationRepository stagingOperations;
    private final SaasMemberPointsBootstrapAbsorbedOperationRepository absorbed;
    private final SaasMemberPointsOpeningRepository openings; private final SaasMemberPointsOfficialAccountRepository official;
    private final SaasMemberBalanceAccountRepository accounts; private final SaasMemberPointsOperationRepository operations;
    private final SaasMemberPointsDebtLotRepository debtLots; private final MemberPointsSyncProjector projector;
    private final SaasMemberPointsAuthorityRepository authorities;
    private final EntityManager entityManager; private final Clock clock;
    public MemberPointsBootstrapReconciliationService(SaasMemberPointsBootstrapRepository bootstraps,
            SaasMemberPointsBootstrapStoreRepository stores,SaasMemberPointsBootstrapSnapshotRepository snapshots,
            SaasMemberPointsBootstrapStagingAccountRepository stagingAccounts,
            SaasMemberPointsBootstrapStagingOperationRepository stagingOperations,
            SaasMemberPointsBootstrapAbsorbedOperationRepository absorbed,SaasMemberPointsOpeningRepository openings,
            SaasMemberPointsOfficialAccountRepository official,SaasMemberBalanceAccountRepository accounts,
            SaasMemberPointsOperationRepository operations,SaasMemberPointsDebtLotRepository debtLots,
            SaasMemberPointsAuthorityRepository authorities,MemberPointsSyncProjector projector,
            EntityManager entityManager,Clock clock){
        this.bootstraps=bootstraps;this.stores=stores;this.snapshots=snapshots;this.stagingAccounts=stagingAccounts;
        this.stagingOperations=stagingOperations;this.absorbed=absorbed;this.openings=openings;this.official=official;
        this.accounts=accounts;this.operations=operations;this.debtLots=debtLots;this.projector=projector;
        this.authorities=authorities;
        this.entityManager=entityManager;this.clock=clock;
    }
    @Transactional
    public Outcome reconcile(UUID bootstrapId){
        SaasMemberPointsBootstrap bootstrap=bootstraps.findForUpdate(bootstrapId)
            .orElseThrow(()->conflict("Bootstrap no encontrado",Set.of()));
        if(bootstrap.isCompleted()) return Outcome.COMPLETED;
        UUID companyId=bootstrap.getCompanyId();
        if(entityManager.find(SaasCompany.class,companyId,LockModeType.PESSIMISTIC_WRITE)==null)
            throw conflict("Empresa no encontrada durante reconciliacion",Set.of());
        if(authorities.findForUpdateByCompanyId(companyId).filter(SaasMemberPointsAuthority::isActive).isPresent())
            throw conflict("El bootstrap de puntos solo puede reconciliar antes de autoridad ACTIVE",Set.of());
        List<SaasMemberPointsBootstrapStore> expected=stores.findByBootstrap_IdOrderByStoreIdAsc(bootstrapId);
        if(expected.stream().anyMatch(v->v.getCompletedAt()==null))
            throw conflict("No todas las tiendas esperadas tienen snapshot COMPLETE",Set.of());
        bootstrap.beginReconciliation();
        Map<UUID,AccountMerge> mergedAccounts=mergeAccounts(bootstrapId);
        Map<UUID,ManifestMerge> manifests=mergeManifests(bootstrapId);
        List<ManifestMerge> replay=manifests.values().stream().filter(v->REPLAY.equals(v.kind())).toList();
        Map<UUID,SaasMemberPointsOperation> received=new HashMap<>();
        for(ManifestMerge manifest:manifests.values()){
            operations.findByCompanyIdAndOperationId(companyId,manifest.operationId()).ifPresent(v->received.put(manifest.operationId(),v));
        }
        for(ManifestMerge manifest:replay){
            SaasMemberPointsOperation operation=received.get(manifest.operationId());
            if(operation==null){bootstrap.markCatchingUp();return Outcome.CATCHING_UP;}
            validateManifestOperation(manifest,operation);
        }
        if(operations.countByCompanyIdAndStatus(companyId,MemberPointsOperationStatus.CONFLICT)>0)
            throw conflict("Existen operaciones de puntos en CONFLICT",Set.of());
        if(!debtLots.findCompanyOutstandingForUpdate(companyId).isEmpty())
            throw conflict("Existen lotes de deuda de puntos activos anteriores al bootstrap",Set.of());
        Instant now=clock.instant();
        Map<UUID,SaasMemberBalanceAccount> central=new HashMap<>();
        accounts.resetPointsForBootstrap(companyId).forEach(v->central.put(v.getMemberId(),v));
        for(AccountMerge merge:mergedAccounts.values()){
            SaasMemberBalanceAccount account=central.computeIfAbsent(merge.memberId(),id->accounts.save(new SaasMemberBalanceAccount(companyId,id)));
            account.replacePoints(merge.points(),merge.pointsDebt());
            String sourceStores=joinStores(merge.storeIds());
            openings.save(new SaasMemberPointsOpening(deterministic("opening",bootstrapId,merge.memberId()),bootstrapId,
                companyId,merge.memberId(),merge.points(),merge.pointsDebt(),sourceStores,merge.checksum(),now));
            if(merge.pointsDebt().signum()>0){
                UUID origin=deterministic("opening-origin",bootstrapId,merge.memberId());
                debtLots.save(new SaasMemberPointsDebtLot(origin,companyId,merge.memberId(),origin,null,
                    MemberPointsDebtOrigin.BOOTSTRAP_OPENING,merge.pointsDebt(),0L,now));
            }
        }
        for(ManifestMerge manifest:manifests.values()){
            if(!ABSORBED.equals(manifest.kind())) continue;
            SaasMemberPointsOperation operation=received.get(manifest.operationId());
            if(operation!=null){validateManifestOperation(manifest,operation);operation.markAbsorbedBootstrap(now);}
            absorbed.save(new SaasMemberPointsBootstrapAbsorbedOperation(deterministic("absorbed",bootstrapId,manifest.operationId()),
                bootstrapId,companyId,manifest.operationId(),manifest.contractHash(),joinStores(manifest.storeIds()),now));
        }
        projector.activateAuthorityAndReplayPending(companyId);
        if(operations.countByCompanyIdAndStatus(companyId,MemberPointsOperationStatus.CONFLICT)>0)
            throw conflict("El replay central produjo operaciones CONFLICT",Set.of());
        if(operations.countByCompanyIdAndStatus(companyId,MemberPointsOperationStatus.PENDING_DEPENDENCY)>0)
            throw conflict("Quedan dependencias de puntos sin resolver",Set.of());
        long watermark=operations.maxCentralId(companyId),revision=1L;
        for(SaasMemberBalanceAccount account:accounts.findByCompanyIdOrderByMemberIdAsc(companyId)){
            official.save(new SaasMemberPointsOfficialAccount(UUID.randomUUID(),bootstrapId,companyId,account.getMemberId(),
                integer(account.getPoints()),integer(account.getPointsDebt()),revision,watermark,now));
        }
        bootstrap.complete(now,revision,watermark);
        return Outcome.COMPLETED;
    }
    private Map<UUID,AccountMerge> mergeAccounts(UUID bootstrapId){
        Map<UUID,AccountMerge> result=new TreeMap<>(Comparator.comparing(UUID::toString));
        for(SaasMemberPointsBootstrapStagingAccount value:stagingAccounts.findCompletedByBootstrap(bootstrapId)){
            UUID store=value.getSnapshot().getStoreId(); BigDecimal points=integer(value.getPoints()),debt=integer(value.getPointsDebt());
            AccountMerge current=result.get(value.getMemberId());
            if(current==null){result.put(value.getMemberId(),new AccountMerge(value.getMemberId(),points,debt,new TreeSet<>(Comparator.comparing(UUID::toString)),new ArrayList<>()));current=result.get(value.getMemberId());}
            else if(current.points().compareTo(points)!=0 || current.pointsDebt().compareTo(debt)!=0)
                throw conflict("Socio "+value.getMemberId()+" difiere entre tiendas: "+current.points()+"/"+current.pointsDebt()+" frente a "+points+"/"+debt,
                    union(current.storeIds(),Set.of(store)));
            current.storeIds().add(store); current.checksums().add(store+"|"+value.getSnapshot().getSnapshotChecksum()+"\n");
        }
        result.replaceAll((id,v)->new AccountMerge(v.memberId(),v.points(),v.pointsDebt(),v.storeIds(),List.of(sha256(v.checksums().stream().sorted().reduce("",String::concat)))));
        return result;
    }
    private Map<UUID,ManifestMerge> mergeManifests(UUID bootstrapId){
        Map<UUID,ManifestMerge> result=new TreeMap<>(Comparator.comparing(UUID::toString));
        for(SaasMemberPointsBootstrapStagingOperation value:stagingOperations.findCompletedByBootstrap(bootstrapId)){
            UUID store=value.getSnapshot().getStoreId(); ManifestMerge current=result.get(value.getOperationId());
            if(current==null){current=new ManifestMerge(value.getOperationId(),value.getKind(),value.getContractHash(),
                new TreeSet<>(Comparator.comparing(UUID::toString)),new HashMap<>());result.put(value.getOperationId(),current);}
            else if(!current.kind().equals(value.getKind()) || !current.contractHash().equals(value.getContractHash()))
                throw conflict("operationId con manifiesto diferente: "+value.getOperationId(),union(current.storeIds(),Set.of(store)));
            current.storeIds().add(store); current.sequences().put(store,value.getSourceSequence());
        }
        return result;
    }
    private void validateManifestOperation(ManifestMerge manifest,SaasMemberPointsOperation operation){
        if(!operation.hasPayloadHash(manifest.contractHash()))
            throw conflict("Hash de operacion distinto al manifiesto: "+manifest.operationId(),manifest.storeIds());
        Long expected=manifest.sequences().get(operation.getStoreId());
        if(expected!=null && !Objects.equals(expected,operation.getStoreSequence()))
            throw conflict("Secuencia de operacion distinta al manifiesto: "+manifest.operationId(),Set.of(operation.getStoreId()));
    }
    private BigDecimal integer(BigDecimal value){try{return value.setScale(0,RoundingMode.UNNECESSARY);}catch(Exception e){throw conflict("Puntos no enteros en reconciliacion",Set.of());}}
    private UUID deterministic(String prefix,UUID bootstrapId,UUID memberId){return UUID.nameUUIDFromBytes((prefix+"|"+bootstrapId+"|"+memberId).getBytes(StandardCharsets.UTF_8));}
    private String joinStores(Set<UUID> ids){return ids.stream().map(UUID::toString).sorted().reduce((a,b)->a+","+b).orElse("");}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private Set<UUID> union(Set<UUID>a,Set<UUID>b){Set<UUID> result=new HashSet<>(a);result.addAll(b);return result;}
    private MemberPointsBootstrapConflictException conflict(String reason,Set<UUID> stores){return new MemberPointsBootstrapConflictException(reason,stores);}
    private record AccountMerge(UUID memberId,BigDecimal points,BigDecimal pointsDebt,Set<UUID> storeIds,List<String> checksums){String checksum(){return checksums.get(0);}}
    private record ManifestMerge(UUID operationId,String kind,String contractHash,Set<UUID> storeIds,Map<UUID,Long> sequences){}
}
