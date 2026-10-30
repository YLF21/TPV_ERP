package com.tpverp.backend.pdawork;

import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.goodscheck.GoodsCheckRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PdaWorkService {
    private static final long MAX_EVIDENCE_BYTES=2_097_152;
    private static final Set<String> SAFE_EVIDENCE_TYPES=Set.of("image/jpeg","image/png","image/webp","application/pdf");
    private final PdaWorkRepository repository;
    private final PdaWorkEvidenceRepository evidences;
    private final PdaWarehouseLocationRepository locations;
    private final UserAccountRepository users;
    private final WarehouseRepository warehouses;
    private final ProductRepository products;
    private final CommercialDocumentRepository documents;
    private final GoodsCheckRepository goodsChecks;
    private final CurrentOrganization organization;
    private final Clock clock;

    public PdaWorkService(PdaWorkRepository repository,PdaWorkEvidenceRepository evidences,
            PdaWarehouseLocationRepository locations,UserAccountRepository users,WarehouseRepository warehouses,
            ProductRepository products,CommercialDocumentRepository documents,GoodsCheckRepository goodsChecks,
            CurrentOrganization organization,Clock clock){
        this.repository=repository;this.evidences=evidences;this.locations=locations;this.users=users;
        this.warehouses=warehouses;this.products=products;this.documents=documents;this.goodsChecks=goodsChecks;
        this.organization=organization;this.clock=clock;
    }

    @Transactional(readOnly=true)
    public List<PdaWorkView> list(PdaWorkType type,PdaWorkStatus status,UUID assignedTo,Instant createdFrom,
            Instant createdTo,String reference,String productCode,int page,int size,Authentication authentication){
        if(page<0||size<1||size>200)throw new IllegalArgumentException("Paginación no válida");
        var storeId=organization.currentStore().getId();
        var user=organization.currentUser(authentication);
        boolean supervisor=isSupervisor(authentication);
        if(!supervisor&&assignedTo!=null&&!assignedTo.equals(user.getId()))
            throw new AccessDeniedException("No puede consultar operaciones de otro usuario");
        Specification<PdaWorkItem> specification=(root,query,cb)->{
            var predicates=new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("storeId"),storeId));
            if(!supervisor)predicates.add(cb.or(cb.equal(root.get("assignedTo"),user.getId()),
                    cb.and(cb.isNull(root.get("assignedTo")),cb.equal(root.get("createdBy"),user.getId()))));
            if(type!=null)predicates.add(cb.equal(root.get("type"),type));
            if(status!=null)predicates.add(cb.equal(root.get("status"),status));
            if(assignedTo!=null)predicates.add(cb.equal(root.get("assignedTo"),assignedTo));
            if(createdFrom!=null)predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"),createdFrom));
            if(createdTo!=null)predicates.add(cb.lessThan(root.get("createdAt"),createdTo));
            if(hasText(reference))predicates.add(cb.like(cb.lower(root.get("reference")),containsPattern(reference),'\\'));
            if(hasText(productCode))predicates.add(cb.like(cb.lower(root.get("productCode")),containsPattern(productCode),'\\'));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result=repository.findAll(specification,PageRequest.of(page,size,
                Sort.by(Sort.Order.desc("createdAt"),Sort.Order.desc("id")))).getContent();
        return views(result);
    }

    @Transactional(readOnly=true)
    public PdaWorkView get(UUID id,Authentication authentication){var work=find(id);requireOwnerOrSupervisor(work,authentication);return view(work);}

    @Transactional
    public PdaWorkView create(CreateCommand command,Authentication authentication){
        if(command.quantity()!=null&&command.quantity().signum()<0)throw new IllegalArgumentException("La cantidad no puede ser negativa");
        var storeId=organization.currentStore().getId();
        var user=organization.currentUser(authentication);
        validateReferences(command,storeId);
        if(command.assignedTo()!=null){
            validateAssignee(command.assignedTo(),storeId);
            if(!isSupervisor(authentication)&&!command.assignedTo().equals(user.getId()))
                throw new AccessDeniedException("No puede asignar operaciones a otro usuario");
        }
        String fingerprint=fingerprint(command);
        if(command.clientRequestId()!=null){
            var existing=repository.findByStoreIdAndClientRequestId(storeId,command.clientRequestId());
            if(existing.isPresent())return view(requireSameFingerprint(existing.get(),fingerprint));
        }
        var now=Instant.now(clock);
        var item=new PdaWorkItem(storeId,command.type(),command.title(),command.reference(),command.productCode(),
                command.warehouseId(),command.quantity(),command.lotNumber(),command.expiryDate(),command.location(),
                command.priority(),command.notes(),command.evidenceName(),command.evidenceType(),null,user.getId(),now,
                command.clientRequestId());
        item.configure(command.assignedTo(),command.dueAt(),command.sourceLocation(),command.destinationLocation(),
                command.goodsCheckId(),command.documentId(),command.productId());
        item.setClientRequestFingerprint(fingerprint);
        if(command.clientRequestId()==null)item=repository.saveAndFlush(item);
        else{
            int inserted=repository.insertIdempotent(item.getId(),item.getStoreId(),item.getType().name(),
                    item.getStatus().name(),item.getTitle(),item.getReference(),item.getProductCode(),item.getWarehouseId(),
                    item.getQuantity(),item.getLotNumber(),item.getExpiryDate(),item.getLocation(),item.getPriority(),
                    item.getNotes(),item.getEvidenceName(),item.getEvidenceType(),item.getCreatedBy(),item.getCreatedAt(),
                    item.getClientRequestId(),fingerprint,item.getAssignedTo(),item.getDueAt(),item.getSourceLocation(),
                    item.getDestinationLocation(),item.getGoodsCheckId(),item.getDocumentId(),item.getProductId());
            item=repository.findByStoreIdAndClientRequestId(storeId,command.clientRequestId())
                    .map(value->requireSameFingerprint(value,fingerprint))
                    .orElseThrow(()->new IllegalStateException("PDA_WORK_IDEMPOTENCY_LOOKUP_FAILED"));
            if(inserted==0)return view(item);
        }
        if(command.evidenceData()!=null&&!command.evidenceData().isBlank())saveEvidence(item,user.getId(),
                command.evidenceName(),command.evidenceType(),decode(command.evidenceData()),null,now);
        return view(item);
    }

    @Transactional public PdaWorkView assign(UUID id,UUID assignedTo,Long version,Authentication authentication){var value=find(id);value.requireVersion(version);validateAssignee(assignedTo,value.getStoreId());value.assign(assignedTo);return view(repository.saveAndFlush(value));}
    @Transactional public PdaWorkView start(UUID id,Long version,Authentication auth){var value=find(id);value.requireVersion(version);value.start(requireOwnerOrSupervisor(value,auth),Instant.now(clock));return view(repository.saveAndFlush(value));}
    @Transactional public PdaWorkView validateLocation(UUID id,String code,PdaLocationRole role,Long version,Authentication auth){var value=find(id);value.requireVersion(version);var userId=requireOwnerOrSupervisor(value,auth);if(value.getWarehouseId()!=null&&locations.findByStoreIdAndWarehouseIdAndCodeIgnoreCaseAndActiveTrue(value.getStoreId(),value.getWarehouseId(),code).isEmpty())throw new IllegalArgumentException("Ubicación inexistente o inactiva");value.validateLocation(code,role,userId,Instant.now(clock));return view(repository.saveAndFlush(value));}
    @Transactional public PdaWorkView finish(UUID id,Authentication auth){return finish(id,null,auth);}
    @Transactional public PdaWorkView finish(UUID id,Long version,Authentication auth){var value=find(id);value.requireVersion(version);value.finish(requireOwnerOrSupervisor(value,auth),Instant.now(clock));return view(repository.saveAndFlush(value));}
    @Transactional public PdaWorkView cancel(UUID id,Authentication auth){return cancel(id,null,auth);}
    @Transactional public PdaWorkView cancel(UUID id,Long version,Authentication auth){var value=find(id);value.requireVersion(version);value.cancel(requireOwnerOrSupervisor(value,auth),Instant.now(clock));return view(repository.saveAndFlush(value));}

    @Transactional
    public PdaWorkEvidenceView addEvidence(UUID id,EvidenceCommand command,Authentication auth){
        var work=findForUpdate(id);work.requireVersion(command.workVersion());
        var userId=requireOwnerOrSupervisor(work,auth);
        return PdaWorkEvidenceView.from(saveEvidence(work,userId,command.name(),command.contentType(),
                command.data()==null?null:decode(command.data()),command.storageReference(),Instant.now(clock)));
    }

    @Transactional(readOnly=true)
    public EvidenceContent evidence(UUID workId,UUID evidenceId,Authentication auth){var work=find(workId);requireOwnerOrSupervisor(work,auth);var evidence=evidences.findById(evidenceId).filter(value->value.getWorkId().equals(workId)).orElseThrow(()->new IllegalArgumentException("Evidencia no encontrada"));return new EvidenceContent(evidence.getName(),evidence.getContentType(),evidence.getContent(),evidence.getStorageReference());}

    private PdaWorkEvidence saveEvidence(PdaWorkItem work,UUID userId,String name,String type,byte[] content,String reference,Instant now){
        if(evidences.countByWorkId(work.getId())>=10)throw new IllegalArgumentException("La operación ya alcanzó el máximo de 10 evidencias");
        if(reference!=null&&!reference.isBlank())throw new IllegalArgumentException("No se admiten referencias externas de evidencia");
        var safeType=type==null?"":type.trim().toLowerCase(Locale.ROOT);
        if(!SAFE_EVIDENCE_TYPES.contains(safeType))throw new IllegalArgumentException("Tipo de evidencia no permitido");
        if(content==null||content.length==0)throw new IllegalArgumentException("La evidencia necesita contenido");
        if(content.length>MAX_EVIDENCE_BYTES)throw new IllegalArgumentException("La evidencia supera 2 MB");
        return evidences.save(new PdaWorkEvidence(work.getId(),name==null?"evidencia":name,safeType,content,null,userId,now));
    }

    private void validateReferences(CreateCommand command,UUID storeId){
        if(command.warehouseId()!=null&&warehouses.findById(command.warehouseId()).filter(value->value.getStoreId().equals(storeId)).isEmpty())throw new IllegalArgumentException("Almacén no encontrado");
        if(command.productId()!=null&&products.findById(command.productId()).filter(value->value.getStoreId().equals(storeId)).isEmpty())throw new IllegalArgumentException("Producto no encontrado");
        if(command.documentId()!=null&&documents.findByIdAndTiendaId(command.documentId(),storeId).isEmpty())throw new IllegalArgumentException("Documento no encontrado");
        if(command.goodsCheckId()!=null&&goodsChecks.findByIdAndTiendaId(command.goodsCheckId(),storeId).isEmpty())throw new IllegalArgumentException("Comprobación no encontrada");
    }
    private void validateAssignee(UUID userId,UUID storeId){var assignee=users.findByIdAndTiendaId(userId,storeId).orElseThrow(()->new IllegalArgumentException("Usuario asignado no encontrado en la tienda"));if(!assignee.isActivo())throw new IllegalArgumentException("El usuario asignado está desactivado");}
    private UUID requireOwnerOrSupervisor(PdaWorkItem work,Authentication auth){var user=organization.currentUser(auth);var owner=work.getAssignedTo()!=null?work.getAssignedTo():work.getCreatedBy();if(!isSupervisor(auth)&&!Objects.equals(owner,user.getId()))throw new AccessDeniedException("La operación está asignada a otro usuario");return user.getId();}
    private boolean isSupervisor(Authentication auth){return auth!=null&&auth.getAuthorities().stream().anyMatch(authority->"ROLE_ADMIN".equals(authority.getAuthority())||"GESTION_ALMACEN".equals(authority.getAuthority()));}

    private List<PdaWorkView> views(List<PdaWorkItem> work){
        if(work.isEmpty())return List.of();
        var metadata=evidences.findMetadataByWorkIdIn(work.stream().map(PdaWorkItem::getId).toList()).stream()
                .collect(Collectors.groupingBy(PdaWorkEvidenceRepository.Metadata::getWorkId,LinkedHashMap::new,
                        Collectors.mapping(PdaWorkEvidenceView::from,Collectors.toList())));
        return work.stream().map(value->PdaWorkView.from(value,metadata.getOrDefault(value.getId(),List.of()))).toList();
    }
    private PdaWorkView view(PdaWorkItem value){return views(List.of(value)).getFirst();}
    private PdaWorkItem find(UUID id){return repository.findByIdAndStoreId(id,organization.currentStore().getId()).orElseThrow(()->new IllegalArgumentException("Operación PDA no encontrada"));}
    private PdaWorkItem findForUpdate(UUID id){return repository.findByIdAndStoreIdForUpdate(id,organization.currentStore().getId()).orElseThrow(()->new IllegalArgumentException("Operación PDA no encontrada"));}
    private static PdaWorkItem requireSameFingerprint(PdaWorkItem value,String fingerprint){if(value.getClientRequestFingerprint()!=null&&!value.getClientRequestFingerprint().equals(fingerprint))throw new PdaWorkConflictException("La clave de idempotencia ya se utilizó con otros datos");return value;}

    private static String fingerprint(CreateCommand command){try{var digest=MessageDigest.getInstance("SHA-256");return HexFormat.of().formatHex(digest.digest(canonical(command).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String canonical(CreateCommand command){return Arrays.asList(command.type(),command.title(),command.reference(),command.productCode(),command.warehouseId(),command.quantity(),command.lotNumber(),command.expiryDate(),command.location(),command.priority(),command.notes(),command.evidenceName(),command.evidenceType(),command.evidenceData(),command.assignedTo(),command.dueAt(),command.sourceLocation(),command.destinationLocation(),command.goodsCheckId(),command.documentId(),command.productId()).stream().map(value->value==null?"<null>":value.toString()).collect(Collectors.joining("\u001f"));}
    private static boolean hasText(String value){return value!=null&&!value.isBlank();}
    private static String containsPattern(String value){return "%"+value.trim().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";}
    private static byte[] decode(String data){var raw=data.trim();var comma=raw.indexOf(',');if(raw.startsWith("data:")&&comma>=0)raw=raw.substring(comma+1);try{return Base64.getDecoder().decode(raw);}catch(IllegalArgumentException error){throw new IllegalArgumentException("Evidencia base64 inválida");}}

    public record CreateCommand(PdaWorkType type,String title,String reference,String productCode,UUID warehouseId,BigDecimal quantity,String lotNumber,LocalDate expiryDate,String location,String priority,String notes,String evidenceName,String evidenceType,String evidenceData,UUID assignedTo,Instant dueAt,String sourceLocation,String destinationLocation,UUID goodsCheckId,UUID documentId,UUID productId,UUID clientRequestId){}
    public record EvidenceCommand(String name,String contentType,String data,String storageReference,Long workVersion){}
    public record EvidenceContent(String name,String contentType,byte[] content,String storageReference){}
}
