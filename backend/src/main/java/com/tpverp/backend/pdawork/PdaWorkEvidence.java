package com.tpverp.backend.pdawork;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name="pda_trabajo_evidencia")
public class PdaWorkEvidence {
    @Id private UUID id;
    @Column(name="trabajo_id",nullable=false) private UUID workId;
    @Column(name="nombre",nullable=false,length=240) private String name;
    @Column(name="tipo_contenido",nullable=false,length=120) private String contentType;
    @Column(name="contenido") private byte[] content;
    @Column(name="referencia_almacenamiento",length=1000) private String storageReference;
    @Column(name="tamano",nullable=false) private long size;
    @Column(name="creado_por",nullable=false) private UUID createdBy;
    @Column(name="creado_en",nullable=false) private Instant createdAt;
    @Version private long version;
    protected PdaWorkEvidence(){}
    public PdaWorkEvidence(UUID workId,String name,String contentType,byte[] content,String storageReference,UUID createdBy,Instant createdAt){
        this.id=UUID.randomUUID();this.workId=Objects.requireNonNull(workId);this.name=required(name);this.contentType=required(contentType);
        this.content=content==null?null:content.clone();this.storageReference=optional(storageReference);
        if(this.storageReference!=null)throw new IllegalArgumentException("No se admiten referencias externas de evidencia");
        if(this.content==null||this.content.length==0)throw new IllegalArgumentException("La evidencia necesita contenido");
        this.size=this.content.length;if(size>2_097_152)throw new IllegalArgumentException("La evidencia supera 2 MB");
        this.createdBy=Objects.requireNonNull(createdBy);this.createdAt=Objects.requireNonNull(createdAt);
    }
    private static String optional(String v){return v==null||v.isBlank()?null:v.trim();}
    private static String required(String v){var r=optional(v);if(r==null)throw new IllegalArgumentException("Campo de evidencia obligatorio");return r;}
    public UUID getId(){return id;} public UUID getWorkId(){return workId;} public String getName(){return name;} public String getContentType(){return contentType;}
    public byte[] getContent(){return content==null?null:content.clone();} public String getStorageReference(){return storageReference;} public long getSize(){return size;}
    public UUID getCreatedBy(){return createdBy;} public Instant getCreatedAt(){return createdAt;} public long getVersion(){return version;}
}
