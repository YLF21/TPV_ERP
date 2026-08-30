package com.tpverp.backend.pdawork;

import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PdaWorkService {
    private final PdaWorkRepository repository;
    private final PdaWorkEvidenceRepository evidences;
    private final PdaWarehouseLocationRepository locations;
    private final CurrentOrganization organization;
    private final Clock clock;
    public PdaWorkService(PdaWorkRepository repository,PdaWorkEvidenceRepository evidences,PdaWarehouseLocationRepository locations,CurrentOrganization organization,Clock clock){this.repository=repository;this.evidences=evidences;this.locations=locations;this.organization=organization;this.clock=clock;}

    @Transactional(readOnly=true)
    public List<PdaWorkView> list(PdaWorkType type,PdaWorkStatus status,UUID assignedTo,Instant createdFrom,Instant createdTo,String reference,String productCode){
        var storeId=organization.currentStore().getId();
        return repository.findByStoreIdOrderByCreatedAtDesc(storeId).stream()
            .filter(v->type==null||v.getType()==type).filter(v->status==null||v.getStatus()==status)
            .filter(v->assignedTo==null||assignedTo.equals(v.getAssignedTo()))
            .filter(v->createdFrom==null||!v.getCreatedAt().isBefore(createdFrom)).filter(v->createdTo==null||v.getCreatedAt().isBefore(createdTo))
            .filter(v->contains(v.getReference(),reference)).filter(v->contains(v.getProductCode(),productCode)).map(this::view).toList();
    }
    @Transactional(readOnly=true) public List<PdaWorkView> list(PdaWorkType type,PdaWorkStatus status){return list(type,status,null,null,null,null,null);}
    @Transactional(readOnly=true) public PdaWorkView get(UUID id){return view(find(id));}

    @Transactional
    public PdaWorkView create(CreateCommand command,Authentication authentication){
        if(command.quantity()!=null&&command.quantity().signum()<0)throw new IllegalArgumentException("La cantidad no puede ser negativa");
        var user=organization.currentUser(authentication);var now=Instant.now(clock);
        var item=new PdaWorkItem(organization.currentStore().getId(),command.type(),command.title(),command.reference(),command.productCode(),command.warehouseId(),command.quantity(),command.lotNumber(),command.expiryDate(),command.location(),command.priority(),command.notes(),command.evidenceName(),command.evidenceType(),null,user.getId(),now);
        item.configure(command.assignedTo(),command.dueAt(),command.sourceLocation(),command.destinationLocation(),command.goodsCheckId(),command.documentId(),command.productId());
        item=repository.save(item);
        if(command.evidenceData()!=null&&!command.evidenceData().isBlank())saveEvidence(item,user.getId(),command.evidenceName(),command.evidenceType(),decode(command.evidenceData()),null,now);
        return view(item);
    }
    @Transactional public PdaWorkView assign(UUID id,UUID assignedTo,Long version){var value=find(id);value.requireVersion(version);value.assign(assignedTo);return view(repository.save(value));}
    @Transactional public PdaWorkView start(UUID id,Long version,Authentication auth){var value=find(id);value.requireVersion(version);value.start(organization.currentUser(auth).getId(),Instant.now(clock));return view(repository.save(value));}
    @Transactional public PdaWorkView validateLocation(UUID id,String code,PdaLocationRole role,Long version,Authentication auth){var value=find(id);value.requireVersion(version);if(value.getWarehouseId()!=null&&locations.findByStoreIdAndWarehouseIdAndCodeIgnoreCaseAndActiveTrue(value.getStoreId(),value.getWarehouseId(),code).isEmpty())throw new IllegalArgumentException("Ubicación inexistente o inactiva");value.validateLocation(code,role,organization.currentUser(auth).getId(),Instant.now(clock));return view(repository.save(value));}
    @Transactional public PdaWorkView finish(UUID id,Authentication auth){return finish(id,null,auth);}
    @Transactional public PdaWorkView finish(UUID id,Long version,Authentication auth){var value=find(id);value.requireVersion(version);value.finish(organization.currentUser(auth).getId(),Instant.now(clock));return view(repository.save(value));}
    @Transactional public PdaWorkView cancel(UUID id,Authentication auth){return cancel(id,null,auth);}
    @Transactional public PdaWorkView cancel(UUID id,Long version,Authentication auth){var value=find(id);value.requireVersion(version);value.cancel(organization.currentUser(auth).getId(),Instant.now(clock));return view(repository.save(value));}
    @Transactional public PdaWorkEvidenceView addEvidence(UUID id,EvidenceCommand command,Authentication auth){var work=find(id);work.requireVersion(command.workVersion());var user=organization.currentUser(auth);var saved=saveEvidence(work,user.getId(),command.name(),command.contentType(),command.data()==null?null:decode(command.data()),command.storageReference(),Instant.now(clock));return PdaWorkEvidenceView.from(saved);}
    @Transactional(readOnly=true) public EvidenceContent evidence(UUID workId,UUID evidenceId){find(workId);var e=evidences.findById(evidenceId).filter(v->v.getWorkId().equals(workId)).orElseThrow(()->new IllegalArgumentException("Evidencia no encontrada"));return new EvidenceContent(e.getName(),e.getContentType(),e.getContent(),e.getStorageReference());}

    private PdaWorkEvidence saveEvidence(PdaWorkItem work,UUID userId,String name,String type,byte[] content,String reference,Instant now){return evidences.save(new PdaWorkEvidence(work.getId(),name==null?"evidencia":name,type==null?"application/octet-stream":type,content,reference,userId,now));}
    private PdaWorkView view(PdaWorkItem value){return PdaWorkView.from(value,evidences.findByWorkIdOrderByCreatedAt(value.getId()).stream().map(PdaWorkEvidenceView::from).toList());}
    private PdaWorkItem find(UUID id){return repository.findByIdAndStoreId(id,organization.currentStore().getId()).orElseThrow(()->new IllegalArgumentException("Operación PDA no encontrada"));}
    private static boolean contains(String source,String filter){return filter==null||filter.isBlank()||source!=null&&source.toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT));}
    private static byte[] decode(String data){var raw=data.trim();var comma=raw.indexOf(',');if(raw.startsWith("data:")&&comma>=0)raw=raw.substring(comma+1);try{return Base64.getDecoder().decode(raw);}catch(IllegalArgumentException e){throw new IllegalArgumentException("Evidencia base64 inválida");}}

    public record CreateCommand(PdaWorkType type,String title,String reference,String productCode,UUID warehouseId,BigDecimal quantity,String lotNumber,LocalDate expiryDate,String location,String priority,String notes,String evidenceName,String evidenceType,String evidenceData,UUID assignedTo,Instant dueAt,String sourceLocation,String destinationLocation,UUID goodsCheckId,UUID documentId,UUID productId){}
    public record EvidenceCommand(String name,String contentType,String data,String storageReference,Long workVersion){}
    public record EvidenceContent(String name,String contentType,byte[] content,String storageReference){}
}