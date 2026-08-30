package com.tpverp.backend.pdawork;
import jakarta.persistence.*;
import java.util.*;
@Entity @Table(name="pda_ubicacion_almacen")
public class PdaWarehouseLocation{
 @Id private UUID id; @Column(name="tienda_id",nullable=false) private UUID storeId; @Column(name="almacen_id",nullable=false) private UUID warehouseId;
 @Column(name="codigo",nullable=false,length=120) private String code; @Column(name="descripcion",length=240) private String description;
 @Column(name="activa",nullable=false) private boolean active=true; @Version private long version;
 protected PdaWarehouseLocation(){} public PdaWarehouseLocation(UUID storeId,UUID warehouseId,String code,String description){id=UUID.randomUUID();this.storeId=Objects.requireNonNull(storeId);this.warehouseId=Objects.requireNonNull(warehouseId);this.code=normalize(code);this.description=description==null?null:description.trim();}
 private static String normalize(String v){if(v==null||v.isBlank())throw new IllegalArgumentException("Código de ubicación obligatorio");return v.trim().toUpperCase();}
 public void deactivate(long expected){if(version!=expected)throw new PdaWorkConflictException("La ubicación fue modificada por otro dispositivo");active=false;}
 public UUID getId(){return id;}public UUID getStoreId(){return storeId;}public UUID getWarehouseId(){return warehouseId;}public String getCode(){return code;}public String getDescription(){return description;}public boolean isActive(){return active;}public long getVersion(){return version;}
}
