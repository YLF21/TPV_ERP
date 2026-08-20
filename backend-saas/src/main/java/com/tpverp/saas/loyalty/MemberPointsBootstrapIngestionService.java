package com.tpverp.saas.loyalty;

import com.tpverp.saas.license.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberPointsBootstrapIngestionService {
    private static final int MAX=500; private static final Pattern HASH=Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final String ACCOUNTS="ACCOUNTS",ABSORBED="ABSORBED_OPERATIONS",REPLAY="REPLAY_OPERATIONS";
    private final SaasInstallationRepository installations; private final InstallationAuthenticator authenticator;
    private final SaasMemberPointsBootstrapRepository bootstraps; private final SaasMemberPointsBootstrapStoreRepository stores;
    private final SaasMemberPointsBootstrapSnapshotRepository snapshots; private final SaasMemberPointsBootstrapChunkRepository chunks;
    private final SaasMemberPointsBootstrapStagingAccountRepository stagingAccounts;
    private final SaasMemberPointsBootstrapStagingOperationRepository stagingOperations;
    private final SaasMemberPointsOfficialAccountRepository official; private final MemberPointsBootstrapStatusService statuses;
    private final MemberPointsBootstrapReconciliationService reconciliation; private final MemberPointsBootstrapConflictRecorder conflicts;
    private final TransactionTemplate transactions; private final Clock clock;
    public MemberPointsBootstrapIngestionService(SaasInstallationRepository installations,InstallationAuthenticator authenticator,
            SaasMemberPointsBootstrapRepository bootstraps,SaasMemberPointsBootstrapStoreRepository stores,
            SaasMemberPointsBootstrapSnapshotRepository snapshots,SaasMemberPointsBootstrapChunkRepository chunks,
            SaasMemberPointsBootstrapStagingAccountRepository stagingAccounts,
            SaasMemberPointsBootstrapStagingOperationRepository stagingOperations,
            SaasMemberPointsOfficialAccountRepository official,MemberPointsBootstrapStatusService statuses,
            MemberPointsBootstrapReconciliationService reconciliation,MemberPointsBootstrapConflictRecorder conflicts,
            PlatformTransactionManager manager,Clock clock){
        this.installations=installations;this.authenticator=authenticator;this.bootstraps=bootstraps;this.stores=stores;
        this.snapshots=snapshots;this.chunks=chunks;this.stagingAccounts=stagingAccounts;this.stagingOperations=stagingOperations;
        this.official=official;this.statuses=statuses;this.reconciliation=reconciliation;this.conflicts=conflicts;
        this.transactions=new TransactionTemplate(manager);this.clock=clock;
    }
    @Transactional(readOnly=true)
    public LoyaltyApiModels.PointsBootstrapStatus discover(LoyaltyApiModels.PointsBootstrapStoreRequest request,String token){
        Context context=authenticate(request==null?null:request.companyId(),request==null?null:request.storeId(),token);
        SaasMemberPointsBootstrap bootstrap=bootstraps.findFirstByCompany_IdOrderByCreatedAtDesc(context.companyId())
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"No existe bootstrap de puntos"));
        requireStore(bootstrap,context.storeId());return statuses.status(bootstrap);
    }
    @Transactional(readOnly=true)
    public LoyaltyApiModels.PointsBootstrapStatus status(UUID id,LoyaltyApiModels.PointsBootstrapStoreRequest request,String token){
        Context context=authenticate(request==null?null:request.companyId(),request==null?null:request.storeId(),token);
        SaasMemberPointsBootstrap bootstrap=requireOwned(id,context.companyId());requireStore(bootstrap,context.storeId());return statuses.status(bootstrap);
    }
    @Transactional(noRollbackFor=MemberPointsBootstrapConflictException.class)
    public LoyaltyApiModels.PointsBootstrapStatus begin(UUID id,LoyaltyApiModels.PointsBootstrapBeginRequest request,String token){
        validateBegin(request);Context context=authenticate(request.companyId(),request.storeId(),token);
        SaasMemberPointsBootstrap bootstrap=lockOwned(id,context.companyId());SaasMemberPointsBootstrapStore store=requireStore(bootstrap,context.storeId());
        String checksum=hash(request.snapshotChecksum(),"snapshotChecksum");
        SaasMemberPointsBootstrapSnapshot same=snapshots.findByBootstrap_IdAndSnapshotId(id,request.snapshotId()).orElse(null);
        if(same!=null){if(same.matches(request,checksum))return statuses.status(bootstrap);throw persistConflict(bootstrap,store,"snapshotId reutilizado");}
        if(!SaasMemberPointsBootstrap.COLLECTING.equals(bootstrap.getStatus())) throw immutableOrConflict(bootstrap,store,"El bootstrap no admite otro snapshot");
        if(snapshots.findByBootstrap_IdAndStoreId(id,context.storeId()).isPresent()) throw persistConflict(bootstrap,store,"La tienda ya inicio otro snapshot");
        if(bootstrap.getCutoffAt()==null)bootstrap.establishCutoff(request.cutoffAt());
        else if(!bootstrap.getCutoffAt().equals(request.cutoffAt()))throw persistConflict(bootstrap,store,"cutoffAt distinto entre tiendas");
        snapshots.save(new SaasMemberPointsBootstrapSnapshot(UUID.randomUUID(),bootstrap,request,checksum,clock.instant()));
        return statuses.status(bootstrap);
    }
    @Transactional(noRollbackFor=MemberPointsBootstrapConflictException.class)
    public LoyaltyApiModels.PointsBootstrapStatus chunk(UUID id,UUID snapshotId,String rawKind,int index,LoyaltyApiModels.PointsBootstrapChunkRequest request,String token){
        String kind=kind(rawKind);Normalized normalized=normalize(kind,request);Context context=authenticate(request.companyId(),request.storeId(),token);
        SaasMemberPointsBootstrap bootstrap=lockOwned(id,context.companyId());SaasMemberPointsBootstrapStore store=requireStore(bootstrap,context.storeId());
        SaasMemberPointsBootstrapSnapshot snapshot=requireSnapshot(id,snapshotId,context.storeId());
        String supplied=hash(request.chunkHash(),"chunkHash");if(!supplied.equals(normalized.hash()))throw immutableOrConflict(bootstrap,store,"chunkHash incorrecto");
        SaasMemberPointsBootstrapChunk existing=chunks.findBySnapshot_IdAndKindAndChunkIndex(snapshot.getId(),kind,index).orElse(null);
        if(existing!=null){if(existing.getChunkHash().equals(supplied)&&existing.getRecordCount()==normalized.count())return statuses.status(bootstrap);throw immutableOrConflict(bootstrap,store,"Chunk reutilizado con contenido distinto");}
        if(snapshot.isCompleted()||!SaasMemberPointsBootstrap.COLLECTING.equals(bootstrap.getStatus()))throw immutableOrConflict(bootstrap,store,"Snapshot cerrado");
        int expected=expectedChunks(snapshot,kind);if(index<0||index>=expected)throw persistConflict(bootstrap,store,"Indice de chunk fuera de rango");
        for(LoyaltyApiModels.PointsSnapshotAccount value:normalized.accounts()){
            if(stagingAccounts.existsBySnapshot_IdAndMemberId(snapshot.getId(),value.memberId()))throw persistConflict(bootstrap,store,"memberId duplicado entre chunks");
            stagingAccounts.save(new SaasMemberPointsBootstrapStagingAccount(UUID.randomUUID(),snapshot,value.memberId(),integer(value.points()),integer(value.pointsDebt())));
        }
        for(LoyaltyApiModels.PointsSnapshotOperation value:normalized.operations()){
            if(stagingOperations.existsBySnapshot_IdAndKindAndOperationId(snapshot.getId(),kind,value.operationId()))throw persistConflict(bootstrap,store,"operationId duplicado entre chunks");
            stagingOperations.save(new SaasMemberPointsBootstrapStagingOperation(UUID.randomUUID(),snapshot,kind,value.operationId(),hash(value.contractHash(),"contractHash"),value.sourceSequence()));
        }
        chunks.save(new SaasMemberPointsBootstrapChunk(UUID.randomUUID(),snapshot,kind,index,supplied,normalized.count()));
        return statuses.status(bootstrap);
    }
    public LoyaltyApiModels.PointsBootstrapStatus complete(UUID id,UUID snapshotId,LoyaltyApiModels.PointsBootstrapCompleteRequest request,String token){
        Context context=authenticate(request==null?null:request.companyId(),request==null?null:request.storeId(),token);
        try{
            Boolean ready=transactions.execute(status->completeSnapshot(id,snapshotId,request,context));
            if(Boolean.TRUE.equals(ready))reconciliation.reconcile(id);
        }catch(MemberPointsBootstrapConflictException exception){conflicts.record(id,exception.getReason(),exception.getStoreIds());throw exception;}
        return statuses.byId(id);
    }
    @Transactional(readOnly=true)
    public LoyaltyApiModels.PointsOfficialStateChunk officialState(UUID id,int index,LoyaltyApiModels.PointsBootstrapStoreRequest request,String token){
        Context context=authenticate(request==null?null:request.companyId(),request==null?null:request.storeId(),token);
        SaasMemberPointsBootstrap bootstrap=requireOwned(id,context.companyId());requireStore(bootstrap,context.storeId());
        if(!bootstrap.isCompleted())throw new ResponseStatusException(HttpStatus.CONFLICT,"El estado oficial aun no esta disponible");
        if(index<0)throw invalid("chunk index debe ser no negativo");
        Page<SaasMemberPointsOfficialAccount> page=official.findCanonicalPage(
            id,context.companyId(),PageRequest.of(index,MAX));
        long count=page.getTotalElements();int total=Math.max(1,(int)((count+MAX-1)/MAX));if(index>=total)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Chunk oficial no encontrado");
        List<LoyaltyApiModels.PointsOfficialAccount> values=page.getContent().stream().map(v->new LoyaltyApiModels.PointsOfficialAccount(v.getMemberId(),v.getPoints(),v.getPointsDebt())).toList();
        String canonical=values.stream().map(this::accountLine).reduce("",String::concat);
        return new LoyaltyApiModels.PointsOfficialStateChunk(id,bootstrap.getOfficialRevision(),bootstrap.getCentralWatermark(),index,total,sha256(canonical),values);
    }
    private Boolean completeSnapshot(UUID id,UUID snapshotId,LoyaltyApiModels.PointsBootstrapCompleteRequest request,Context context){
        if(request==null)throw invalid("Solicitud obligatoria");SaasMemberPointsBootstrap bootstrap=lockOwned(id,context.companyId());
        SaasMemberPointsBootstrapStore store=requireStore(bootstrap,context.storeId());SaasMemberPointsBootstrapSnapshot snapshot=requireSnapshot(id,snapshotId,context.storeId());
        String checksum=hash(request.snapshotChecksum(),"snapshotChecksum");
        if(bootstrap.isCompleted()){if(snapshot.isCompleted()&&snapshot.getSnapshotChecksum().equals(checksum))return false;throw new MemberPointsBootstrapConflictException("Bootstrap COMPLETED inmutable",Set.of());}
        if(!snapshot.getSnapshotChecksum().equals(checksum))throw persistConflict(bootstrap,store,"snapshotChecksum distinto al begin");
        if(!snapshot.isCompleted()){
            List<SaasMemberPointsBootstrapChunk> values=chunks.findBySnapshot_Id(snapshot.getId());verifyComplete(snapshot,values);
            if(!snapshotChecksum(values).equals(checksum))throw persistConflict(bootstrap,store,"snapshotChecksum no coincide con los chunks");
            snapshot.complete(clock.instant());store.complete(clock.instant());
        }
        return stores.findByBootstrap_IdOrderByStoreIdAsc(id).stream().allMatch(v->v.getCompletedAt()!=null);
    }
    private void verifyComplete(SaasMemberPointsBootstrapSnapshot snapshot,List<SaasMemberPointsBootstrapChunk> values){
        verifyKind(values,ACCOUNTS,snapshot.getAccountChunkCount(),snapshot.getAccountCount());
        verifyKind(values,ABSORBED,snapshot.getAbsorbedChunkCount(),snapshot.getAbsorbedCount());
        verifyKind(values,REPLAY,snapshot.getReplayChunkCount(),snapshot.getReplayCount());
    }
    private void verifyKind(List<SaasMemberPointsBootstrapChunk> values,String kind,int expectedChunks,int expectedRecords){
        List<SaasMemberPointsBootstrapChunk> selected=values.stream().filter(v->kind.equals(v.getKind())).sorted(Comparator.comparingInt(SaasMemberPointsBootstrapChunk::getChunkIndex)).toList();
        if(selected.size()!=expectedChunks)throw new MemberPointsBootstrapConflictException("Faltan chunks de "+kind,Set.of());
        for(int i=0;i<selected.size();i++)if(selected.get(i).getChunkIndex()!=i)throw new MemberPointsBootstrapConflictException("Indices no contiguos en "+kind,Set.of());
        if(selected.stream().mapToInt(SaasMemberPointsBootstrapChunk::getRecordCount).sum()!=expectedRecords)throw new MemberPointsBootstrapConflictException("Count incorrecto en "+kind,Set.of());
    }
    private Normalized normalize(String kind,LoyaltyApiModels.PointsBootstrapChunkRequest request){
        if(request==null)throw invalid("Solicitud obligatoria");List<LoyaltyApiModels.PointsSnapshotAccount>a=safe(request.accounts());
        List<LoyaltyApiModels.PointsSnapshotOperation> absorbed=safe(request.absorbedOperations()),replay=safe(request.replayOperations());
        if((ACCOUNTS.equals(kind)&&(!absorbed.isEmpty()||!replay.isEmpty()))||(ABSORBED.equals(kind)&&(!a.isEmpty()||!replay.isEmpty()))||(REPLAY.equals(kind)&&(!a.isEmpty()||!absorbed.isEmpty())))throw invalid("Solo la lista correspondiente al kind puede contener datos");
        if(ACCOUNTS.equals(kind)){
            if(a.isEmpty()||a.size()>MAX)throw invalid("Chunk debe contener entre 1 y 500 cuentas");
            List<LoyaltyApiModels.PointsSnapshotAccount> validated=new ArrayList<>();
            for(var v:a){
                if(v==null||v.memberId()==null)throw invalid("Cuenta invalida: elemento y memberId son obligatorios");
                validated.add(new LoyaltyApiModels.PointsSnapshotAccount(v.memberId(),integer(v.points()),integer(v.pointsDebt())));
            }
            List<LoyaltyApiModels.PointsSnapshotAccount> sorted=validated.stream().sorted(Comparator.comparing(v->v.memberId().toString())).toList();Set<UUID> ids=new HashSet<>();StringBuilder canonical=new StringBuilder();
            for(var v:sorted){if(!ids.add(v.memberId()))throw invalid("Cuenta invalida o duplicada");canonical.append(accountLine(new LoyaltyApiModels.PointsOfficialAccount(v.memberId(),v.points(),v.pointsDebt())));}
            return new Normalized(sorted,List.of(),sha256(canonical.toString()),sorted.size());
        }
        List<LoyaltyApiModels.PointsSnapshotOperation> source=ABSORBED.equals(kind)?absorbed:replay;
        if(source.isEmpty()||source.size()>MAX)throw invalid("Chunk debe contener entre 1 y 500 operaciones");
        List<LoyaltyApiModels.PointsSnapshotOperation> validated=new ArrayList<>();
        for(var v:source){
            if(v==null||v.operationId()==null)throw invalid("Operacion invalida: elemento y operationId son obligatorios");
            String contractHash=hash(v.contractHash(),"contractHash");
            if(v.sourceSequence()!=null&&v.sourceSequence()<=0)throw invalid("sourceSequence debe ser positivo");
            validated.add(new LoyaltyApiModels.PointsSnapshotOperation(v.operationId(),contractHash,v.sourceSequence()));
        }
        List<LoyaltyApiModels.PointsSnapshotOperation> sorted=validated.stream().sorted(Comparator.comparing(v->v.operationId().toString())).toList();Set<UUID> ids=new HashSet<>();StringBuilder canonical=new StringBuilder();
        for(var v:sorted){if(!ids.add(v.operationId()))throw invalid("Operacion invalida o duplicada");canonical.append(ABSORBED.equals(kind)?"I|":"R|").append(v.operationId()).append('|').append(v.contractHash()).append('|').append(v.sourceSequence()==null?"-":v.sourceSequence()).append('\n');}
        return new Normalized(List.of(),sorted,sha256(canonical.toString()),sorted.size());
    }
    private String snapshotChecksum(List<SaasMemberPointsBootstrapChunk> values){StringBuilder value=new StringBuilder();values.stream().sorted(Comparator.comparingInt((SaasMemberPointsBootstrapChunk v)->rank(v.getKind())).thenComparingInt(SaasMemberPointsBootstrapChunk::getChunkIndex)).forEach(v->value.append(v.getKind()).append('|').append(v.getChunkIndex()).append('|').append(v.getChunkHash()).append('\n'));return sha256(value.toString());}
    private int rank(String kind){return ACCOUNTS.equals(kind)?0:ABSORBED.equals(kind)?1:2;}
    private int expectedChunks(SaasMemberPointsBootstrapSnapshot s,String kind){return ACCOUNTS.equals(kind)?s.getAccountChunkCount():ABSORBED.equals(kind)?s.getAbsorbedChunkCount():s.getReplayChunkCount();}
    private String accountLine(LoyaltyApiModels.PointsOfficialAccount a){return "A|"+a.memberId()+"|"+integer(a.points()).toPlainString()+"|"+integer(a.pointsDebt()).toPlainString()+"\n";}
    private void validateBegin(LoyaltyApiModels.PointsBootstrapBeginRequest r){if(r==null||r.companyId()==null||r.storeId()==null||r.snapshotId()==null||r.cutoffAt()==null)throw invalid("Begin incompleto");if(r.cutoffAt().isAfter(clock.instant()))throw invalid("cutoffAt no puede estar en el futuro");if(r.accountChunkCount()<0||r.absorbedOperationChunkCount()<0||r.replayOperationChunkCount()<0||r.accountCount()<0||r.absorbedOperationCount()<0||r.replayOperationCount()<0)throw invalid("Counts no pueden ser negativos");hash(r.snapshotChecksum(),"snapshotChecksum");}
    private Context authenticate(UUID companyId,UUID storeId,String token){if(companyId==null||storeId==null)throw invalid("companyId y storeId son obligatorios");SaasInstallation installation=authenticator.requireLinkedInstallation(companyId,storeId,installations.findByCompany_IdAndStore_Id(companyId,storeId),token);return new Context(installation.getCompany().getId(),installation.getStore().getId());}
    private SaasMemberPointsBootstrap requireOwned(UUID id,UUID companyId){return bootstraps.findById(id).filter(v->v.getCompanyId().equals(companyId)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Bootstrap no encontrado"));}
    private SaasMemberPointsBootstrap lockOwned(UUID id,UUID companyId){return bootstraps.findForUpdate(id).filter(v->v.getCompanyId().equals(companyId)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Bootstrap no encontrado"));}
    private SaasMemberPointsBootstrapStore requireStore(SaasMemberPointsBootstrap b,UUID storeId){return stores.findByBootstrap_IdAndStoreId(b.getId(),storeId).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Tienda fuera de expectedStoreIds"));}
    private SaasMemberPointsBootstrapSnapshot requireSnapshot(UUID id,UUID snapshotId,UUID storeId){return snapshots.findByBootstrap_IdAndSnapshotId(id,snapshotId).filter(v->v.getStoreId().equals(storeId)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Snapshot no encontrado"));}
    private MemberPointsBootstrapConflictException persistConflict(SaasMemberPointsBootstrap b,SaasMemberPointsBootstrapStore s,String reason){if(!b.isCompleted()){b.markConflict(reason);s.markConflict(reason);}return new MemberPointsBootstrapConflictException(b.isCompleted()?"Bootstrap COMPLETED inmutable: "+reason:reason,Set.of(s.getStoreId()));}
    private MemberPointsBootstrapConflictException immutableOrConflict(SaasMemberPointsBootstrap b,SaasMemberPointsBootstrapStore s,String reason){return persistConflict(b,s,reason);}
    private String kind(String raw){String v=raw==null?"":raw.trim().toUpperCase(Locale.ROOT);if(!Set.of(ACCOUNTS,ABSORBED,REPLAY).contains(v))throw invalid("kind no soportado");return v;}
    private String hash(String value,String field){if(value==null||!HASH.matcher(value.trim()).matches())throw invalid(field+" debe ser SHA-256 hexadecimal");return value.trim().toLowerCase(Locale.ROOT);}
    private BigDecimal integer(BigDecimal value){if(value==null)throw invalid("Valor entero obligatorio");try{BigDecimal result=value.setScale(0,RoundingMode.UNNECESSARY);if(result.signum()<0||result.precision()>19)throw invalid("Puntos fuera de rango");return result;}catch(ArithmeticException e){throw invalid("Los puntos deben ser enteros");}}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private <T>List<T> safe(List<T> values){return values==null?List.of():values;}
    private ResponseStatusException invalid(String reason){return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);}
    private record Context(UUID companyId,UUID storeId){}
    private record Normalized(List<LoyaltyApiModels.PointsSnapshotAccount> accounts,List<LoyaltyApiModels.PointsSnapshotOperation> operations,String hash,int count){}
}
