package com.tpverp.saas.loyalty;

import com.tpverp.saas.admin.AdminAuditService;
import com.tpverp.saas.license.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberPointsBootstrapAdminService {
    private final SaasStoreRepository stores; private final SaasInstallationRepository installations;
    private final SaasMemberPointsBootstrapRepository bootstraps;
    private final SaasMemberPointsBootstrapStoreRepository expectedStores;
    private final SaasMemberPointsAuthorityRepository authorities;
    private final MemberPointsBootstrapStatusService statuses; private final AdminAuditService audit;
    private final EntityManager entityManager; private final Clock clock;
    public MemberPointsBootstrapAdminService(SaasStoreRepository stores,SaasInstallationRepository installations,
            SaasMemberPointsBootstrapRepository bootstraps,SaasMemberPointsBootstrapStoreRepository expectedStores,
            SaasMemberPointsAuthorityRepository authorities,MemberPointsBootstrapStatusService statuses,
            AdminAuditService audit,EntityManager entityManager,Clock clock){
        this.stores=stores;this.installations=installations;this.bootstraps=bootstraps;
        this.expectedStores=expectedStores;this.authorities=authorities;this.statuses=statuses;
        this.audit=audit;this.entityManager=entityManager;this.clock=clock;
    }
    @Transactional
    public LoyaltyApiModels.PointsBootstrapStatus start(UUID companyId){
        SaasCompany company=lockCompany(companyId);
        if(authorities.findById(companyId).filter(SaasMemberPointsAuthority::isActive).isPresent())
            throw conflict("La autoridad central de puntos ya esta ACTIVE");
        SaasMemberPointsBootstrap previous=bootstraps.findFirstByCompany_IdOrderByCreatedAtDesc(companyId).orElse(null);
        if(previous!=null && !previous.isCancelled())
            throw conflict(previous.isCompleted()?"La empresa ya tiene un bootstrap de puntos COMPLETED":"Debe cancelarse el bootstrap anterior antes de reiniciar");
        List<SaasStore> companyStores=stores.findByCompany_IdOrderByCodeAsc(companyId);
        if(companyStores.isEmpty()) throw conflict("La empresa no tiene tiendas para congelar");
        List<UUID> missing=companyStores.stream().map(SaasStore::getId)
            .filter(id->!installations.existsByStore_IdAndActiveTrue(id)).toList();
        if(!missing.isEmpty()) throw conflict("Tiendas sin instalacion vinculada: "+missing);
        SaasMemberPointsBootstrap bootstrap=bootstraps.save(new SaasMemberPointsBootstrap(UUID.randomUUID(),company,clock.instant()));
        companyStores.forEach(store->expectedStores.save(new SaasMemberPointsBootstrapStore(UUID.randomUUID(),bootstrap,store.getId())));
        audit.log("START_MEMBER_POINTS_BOOTSTRAP_V21","COMPANY",companyId.toString(),"bootstrapId="+bootstrap.getId());
        return statuses.status(bootstrap);
    }
    @Transactional(readOnly=true) public LoyaltyApiModels.PointsBootstrapStatus status(UUID companyId){return statuses.latest(companyId);}
    @Transactional
    public LoyaltyApiModels.PointsBootstrapStatus cancel(UUID companyId,UUID bootstrapId){
        lockCompany(companyId);
        SaasMemberPointsBootstrap bootstrap=bootstraps.findForUpdate(bootstrapId)
            .filter(v->v.getCompanyId().equals(companyId)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Bootstrap no encontrado"));
        if(bootstrap.isCompleted()) throw conflict("Un bootstrap COMPLETED es inmutable");
        if(!bootstrap.isCancelled()){
            bootstrap.cancel(clock.instant());
            audit.log("CANCEL_MEMBER_POINTS_BOOTSTRAP_V21","COMPANY",companyId.toString(),"bootstrapId="+bootstrapId);
        }
        return statuses.status(bootstrap);
    }
    private SaasCompany lockCompany(UUID id){
        if(id==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"companyId es obligatorio");
        SaasCompany company=entityManager.find(SaasCompany.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(company==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Empresa no encontrada");
        return company;
    }
    private ResponseStatusException conflict(String reason){return new ResponseStatusException(HttpStatus.CONFLICT,reason);}
}
