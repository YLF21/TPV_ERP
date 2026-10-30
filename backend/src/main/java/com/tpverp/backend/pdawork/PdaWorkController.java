package com.tpverp.backend.pdawork;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pda-work")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_ALMACEN', 'STOCK_ADJUST', 'STOCK_TRANSFER')")
public class PdaWorkController {
    private final PdaWorkService service;
    public PdaWorkController(PdaWorkService service) { this.service = service; }

    @GetMapping
    public List<PdaWorkView> list(@RequestParam(required=false) PdaWorkType type,
            @RequestParam(required=false) PdaWorkStatus status,
            @RequestParam(required=false) UUID assignedTo,
            @RequestParam(required=false) Instant createdFrom,
            @RequestParam(required=false) Instant createdTo,
            @RequestParam(required=false) String reference,
            @RequestParam(required=false) String productCode,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="100") @Min(1) @Max(200) int size,
            Authentication authentication) {
        return service.list(type,status,assignedTo,createdFrom,createdTo,reference,productCode,page,size,authentication);
    }

    @GetMapping("/{id}") public PdaWorkView get(@PathVariable UUID id,Authentication authentication){return service.get(id,authentication);}
    @PostMapping public PdaWorkView create(@Valid @RequestBody CreateRequest request,Authentication authentication){return service.create(request.command(),authentication);}
    @PostMapping("/{id}/assign") @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_ALMACEN')")
    public PdaWorkView assign(@PathVariable UUID id,@Valid @RequestBody AssignRequest request,Authentication authentication){return service.assign(id,request.assignedTo(),request.version(),authentication);}
    @PostMapping("/{id}/start") public PdaWorkView start(@PathVariable UUID id,@RequestBody(required=false)VersionRequest request,Authentication authentication){return service.start(id,request==null?null:request.version(),authentication);}
    @PostMapping("/{id}/validate-location") public PdaWorkView validateLocation(@PathVariable UUID id,@Valid @RequestBody LocationRequest request,Authentication authentication){return service.validateLocation(id,request.code(),request.role(),request.version(),authentication);}
    @PostMapping("/{id}/finish") public PdaWorkView finish(@PathVariable UUID id,@RequestBody(required=false)VersionRequest request,Authentication authentication){return service.finish(id,request==null?null:request.version(),authentication);}
    @PostMapping("/{id}/cancel") public PdaWorkView cancel(@PathVariable UUID id,@RequestBody(required=false)VersionRequest request,Authentication authentication){return service.cancel(id,request==null?null:request.version(),authentication);}
    @PostMapping("/{id}/evidences") public ResponseEntity<PdaWorkEvidenceView> addEvidence(@PathVariable UUID id,@Valid @RequestBody EvidenceRequest request,Authentication authentication){return ResponseEntity.status(HttpStatus.CREATED).body(service.addEvidence(id,request.command(),authentication));}
    @GetMapping("/{workId}/evidences/{evidenceId}") public ResponseEntity<byte[]> evidence(@PathVariable UUID workId,@PathVariable UUID evidenceId,Authentication authentication){var value=service.evidence(workId,evidenceId,authentication);return ResponseEntity.ok().contentType(MediaType.parseMediaType(value.contentType())).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+safe(value.name())+"\"").body(value.content());}
    private static String safe(String name){return name==null?"evidencia":name.replaceAll("[\\r\\n\\\"]","_");}

    public record CreateRequest(@NotNull PdaWorkType type,@NotBlank @Size(max=180)String title,@Size(max=120)String reference,@Size(max=120)String productCode,UUID warehouseId,@PositiveOrZero BigDecimal quantity,@Size(max=120)String lotNumber,LocalDate expiryDate,@Size(max=120)String location,@Size(max=16)String priority,@Size(max=4000)String notes,@Size(max=240)String evidenceName,@Size(max=120)String evidenceType,@Size(max=2800000)String evidenceData,UUID assignedTo,Instant dueAt,@Size(max=120)String sourceLocation,@Size(max=120)String destinationLocation,UUID goodsCheckId,UUID documentId,UUID productId,UUID clientRequestId){PdaWorkService.CreateCommand command(){return new PdaWorkService.CreateCommand(type,title,reference,productCode,warehouseId,quantity,lotNumber,expiryDate,location,priority,notes,evidenceName,evidenceType,evidenceData,assignedTo,dueAt,sourceLocation,destinationLocation,goodsCheckId,documentId,productId,clientRequestId);}}
    public record AssignRequest(@NotNull UUID assignedTo,Long version){}
    public record VersionRequest(Long version){}
    public record LocationRequest(@NotBlank @Size(max=120)String code,@NotNull PdaLocationRole role,Long version){}
    public record EvidenceRequest(@Size(max=240)String name,@NotBlank @Size(max=120)String contentType,@Size(max=2800000)String data,@Size(max=1000)String storageReference,Long version){PdaWorkService.EvidenceCommand command(){return new PdaWorkService.EvidenceCommand(name,contentType,data,storageReference,version);}}
}
