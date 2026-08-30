package com.tpverp.backend.pdawork;
import java.time.Instant;
import java.util.UUID;
public record PdaWorkEvidenceView(UUID id,String name,String contentType,long size,String storageReference,Instant createdAt,long version){
    static PdaWorkEvidenceView from(PdaWorkEvidence e){return new PdaWorkEvidenceView(e.getId(),e.getName(),e.getContentType(),e.getSize(),e.getStorageReference(),e.getCreatedAt(),e.getVersion());}
}
