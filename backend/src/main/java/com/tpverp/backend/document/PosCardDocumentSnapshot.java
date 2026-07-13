package com.tpverp.backend.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

@Component
public class PosCardDocumentSnapshot {
    private final ObjectMapper mapper;
    public PosCardDocumentSnapshot(){
        this(JsonMapper.builder().findAndAddModules().build());
    }

    PosCardDocumentSnapshot(ObjectMapper mapper){this.mapper=mapper;}
    public String serialize(ApprovedCardTicketSnapshot ticket){
        try{return mapper.writeValueAsString(new Snapshot(1,ticket));}
        catch(JsonProcessingException e){throw new ApprovedCardSnapshotException("No se pudo guardar la instantanea de venta",e);}
    }
    public ApprovedCardTicketSnapshot deserialize(String json){
        try{var snapshot=mapper.readValue(json,Snapshot.class);if(snapshot.schemaVersion()!=1)throw new ApprovedCardSnapshotException("Version de instantanea no soportada");validate(snapshot.ticket());return snapshot.ticket();}
        catch(ApprovedCardSnapshotException e){throw e;}
        catch(JsonProcessingException|IllegalArgumentException e){throw new ApprovedCardSnapshotException("Instantanea de venta corrupta",e);}
    }
    private static void validate(ApprovedCardTicketSnapshot value){
        if(value==null||value.storeId()==null||value.warehouseId()==null||value.date()==null||value.paymentMethodId()==null
                ||value.lines()==null||value.lines().isEmpty()||value.baseTotal()==null||value.taxTotal()==null||value.total()==null)
            throw new ApprovedCardSnapshotException("Instantanea de venta incompleta");
        try{
            var ticket=new CommercialDocument(value.storeId(),value.warehouseId(),CommercialDocumentType.TICKET,value.date(),
                    java.util.UUID.randomUUID(),value.globalDiscount());
            ticket.setParties(value.customerId(),null,null); value.lines().forEach(line->ticket.addLine(line.toEntity(ticket)));
            if(ticket.getBaseTotal().compareTo(Money.euros(value.baseTotal()))!=0
                    ||ticket.getImpuestoTotal().compareTo(Money.euros(value.taxTotal()))!=0
                    ||ticket.getTotal().compareTo(Money.euros(value.total()))!=0)
                throw new ApprovedCardSnapshotException("Los totales fiscales de la instantanea no cuadran");
        }catch(ApprovedCardSnapshotException e){throw e;}
        catch(RuntimeException e){throw new ApprovedCardSnapshotException("Lineas fiscales invalidas",e);}
    }
    public record Snapshot(int schemaVersion,ApprovedCardTicketSnapshot ticket){}
}
