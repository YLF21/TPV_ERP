package com.tpverp.backend.document;
import static org.assertj.core.api.Assertions.*;import com.fasterxml.jackson.databind.ObjectMapper;import java.math.BigDecimal;import java.time.LocalDate;import java.util.*;import org.junit.jupiter.api.Test;
class PosCardDocumentSnapshotTest {
 @Test void roundTripsVersionedPostQuoteFiscalSnapshot(){var snapshots=new PosCardDocumentSnapshot(new ObjectMapper().findAndRegisterModules());var frozen=new ApprovedCardTicketSnapshot(UUID.randomUUID(),UUID.randomUUID(),LocalDate.of(2026,7,11),null,UUID.randomUUID(),BigDecimal.ZERO,new BigDecimal("10.00"),new BigDecimal("2.10"),new BigDecimal("12.10"),List.of(new DocumentLineCommand(UUID.randomUUID(),BigDecimal.ONE,"P1","Producto","SOCIO",new BigDecimal("12.10"),BigDecimal.ZERO,true,"IVA",new BigDecimal("21"))));var json=snapshots.serialize(frozen);assertThat(json).contains("\"schemaVersion\":1","\"baseTotal\":10.00","\"taxTotal\":2.10","\"total\":12.10","\"tarifa\":\"SOCIO\"");assertThat(snapshots.deserialize(json)).isEqualTo(frozen);}
 @Test void rejectsCorruptOrUnknownSnapshotsWithTypedException(){var snapshots=new PosCardDocumentSnapshot(new ObjectMapper().findAndRegisterModules());assertThatThrownBy(()->snapshots.deserialize("{bad")).isInstanceOf(ApprovedCardSnapshotException.class);assertThatThrownBy(()->snapshots.deserialize("{\"schemaVersion\":2,\"ticket\":null}")).isInstanceOf(ApprovedCardSnapshotException.class);}
 @Test void rejectsSemanticFiscalMismatchWithTypedException(){var snapshots=new PosCardDocumentSnapshot(new ObjectMapper().findAndRegisterModules());var frozen=new ApprovedCardTicketSnapshot(UUID.randomUUID(),UUID.randomUUID(),LocalDate.now(),null,UUID.randomUUID(),BigDecimal.ZERO,new BigDecimal("99"),BigDecimal.ZERO,new BigDecimal("99"),List.of(new DocumentLineCommand(UUID.randomUUID(),BigDecimal.ONE,"P","P",null,BigDecimal.ONE,BigDecimal.ZERO,true,"IVA",new BigDecimal("21"))));assertThatThrownBy(()->snapshots.deserialize(snapshots.serialize(frozen))).isInstanceOf(ApprovedCardSnapshotException.class).hasMessageContaining("no cuadran");}

 @Test void readsLegacyVersionOneJsonWithoutHistoricalReplayMetadata(){
  var snapshots=new PosCardDocumentSnapshot(new ObjectMapper().findAndRegisterModules());
  var frozen=new ApprovedCardTicketSnapshot(UUID.randomUUID(),UUID.randomUUID(),LocalDate.of(2026,7,11),null,UUID.randomUUID(),BigDecimal.ZERO,new BigDecimal("10.00"),new BigDecimal("2.10"),new BigDecimal("12.10"),List.of(new DocumentLineCommand(UUID.randomUUID(),BigDecimal.ONE,"P1","Producto","VENTA",new BigDecimal("12.10"),BigDecimal.ZERO,true,"IVA",new BigDecimal("21"))));
  var legacyJson=snapshots.serialize(frozen).replace(",\"historicalReplay\":null","");

  assertThat(snapshots.deserialize(legacyJson)).isEqualTo(frozen);
 }

 @Test void preservesFrozenHistoricalProductAmountsAcrossSerialization(){
  var snapshots=new PosCardDocumentSnapshot(new ObjectMapper().findAndRegisterModules());
  var line=new DocumentLineCommand(UUID.randomUUID(),BigDecimal.ONE,"P1","Producto","VENTA",new BigDecimal("0.03"),BigDecimal.ZERO,true,"IVA",new BigDecimal("21"),DocumentLineType.PRODUCT,null,null,null,List.of(),false,false,null,null,null,null,null,new BigDecimal("0.03"),new BigDecimal("0.01"),new BigDecimal("0.03"));
  var frozen=new ApprovedCardTicketSnapshot(UUID.randomUUID(),UUID.randomUUID(),LocalDate.of(2026,8,7),null,UUID.randomUUID(),BigDecimal.ZERO,new BigDecimal("0.03"),new BigDecimal("0.01"),new BigDecimal("0.03"),List.of(line));

  var restored=snapshots.deserialize(snapshots.serialize(frozen));

  assertThat(restored.lines()).singleElement().satisfies(command->{
   assertThat(command.frozenBase()).isEqualByComparingTo("0.03");
   assertThat(command.frozenTax()).isEqualByComparingTo("0.01");
   assertThat(command.frozenTotal()).isEqualByComparingTo("0.03");
  });
 }

 @Test void preservesTheOriginalTicketIdentityForReturnCardSettlement(){
  var storeId=UUID.randomUUID();
  var warehouseId=UUID.randomUUID();
  var userId=UUID.randomUUID();
  var productId=UUID.randomUUID();
  var originalTicketId=UUID.randomUUID();
  var originalLineId=UUID.randomUUID();
  var quoted=new CommercialDocument(storeId,warehouseId,CommercialDocumentType.TICKET,
          LocalDate.of(2026,8,4),userId,BigDecimal.ZERO);
  var quotedLine=new DocumentLine(quoted,productId,1,new BigDecimal("-1.000"),
          "P1","Producto",null,new BigDecimal("10.00"),BigDecimal.ZERO,
          true,"IVA",new BigDecimal("21"));
  quotedLine.identifyRefundOf(originalLineId);
  quoted.addLine(quotedLine);
  var requested=new DocumentLineCommand(productId,new BigDecimal("-1.000"),
          "P1","Producto",null,new BigDecimal("10.00"),BigDecimal.ZERO,
          true,"IVA",new BigDecimal("21"),DocumentLineType.PRODUCT,null,null,
          null,List.of(),false,false,TicketReturnService.ReturnSourceType.TICKET,
          "T-ORIGINAL",originalTicketId,originalLineId,null);

  var frozen=ApprovedCardTicketSnapshot.from(quoted,UUID.randomUUID(),List.of(requested));

  assertThat(frozen.lines()).singleElement().satisfies(line->{
   assertThat(line.returnSourceTicketId()).isEqualTo(originalTicketId);
   assertThat(line.returnSourceCode()).isEqualTo("T-ORIGINAL");
   assertThat(line.originalDocumentLineId()).isEqualTo(originalLineId);
  });
 }

 @Test void normalSaleLinesNeverAcquireARefundSourceTicket(){
  var storeId=UUID.randomUUID();
  var warehouseId=UUID.randomUUID();
  var quoted=new CommercialDocument(storeId,warehouseId,CommercialDocumentType.TICKET,
          LocalDate.of(2026,8,5),UUID.randomUUID(),BigDecimal.ZERO);
  quoted.addLine(new DocumentLine(quoted,UUID.randomUUID(),1,BigDecimal.ONE,
          "P1","Producto",null,new BigDecimal("10.00"),BigDecimal.ZERO,
          true,"IVA",new BigDecimal("21")));

  var frozen=ApprovedCardTicketSnapshot.from(quoted,UUID.randomUUID());

  assertThat(frozen.lines()).singleElement().satisfies(line->{
   assertThat(line.originalDocumentLineId()).isNull();
   assertThat(line.returnSourceTicketId()).isNull();
   assertThat(line.returnSourceType()).isNull();
  });
 }
}
