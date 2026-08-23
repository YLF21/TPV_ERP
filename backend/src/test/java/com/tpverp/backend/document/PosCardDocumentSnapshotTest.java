package com.tpverp.backend.document;
import static org.assertj.core.api.Assertions.*;import com.fasterxml.jackson.databind.ObjectMapper;import java.math.BigDecimal;import java.time.LocalDate;import java.util.*;import org.junit.jupiter.api.Test;
class PosCardDocumentSnapshotTest {
 @Test void roundTripsVersionedPostQuoteFiscalSnapshot(){var snapshots=new PosCardDocumentSnapshot(new ObjectMapper().findAndRegisterModules());var frozen=new ApprovedCardTicketSnapshot(UUID.randomUUID(),UUID.randomUUID(),LocalDate.of(2026,7,11),null,UUID.randomUUID(),BigDecimal.ZERO,new BigDecimal("10.00"),new BigDecimal("2.10"),new BigDecimal("12.10"),List.of(new DocumentLineCommand(UUID.randomUUID(),BigDecimal.ONE,"P1","Producto","SOCIO",new BigDecimal("12.10"),BigDecimal.ZERO,true,"IVA",new BigDecimal("21"))));var json=snapshots.serialize(frozen);assertThat(json).contains("\"schemaVersion\":2","\"baseTotal\":10.00","\"taxTotal\":2.10","\"total\":12.10","\"tarifa\":\"SOCIO\"");assertThat(snapshots.deserialize(json)).isEqualTo(frozen);}
 @Test void rejectsCorruptOrUnknownSnapshotsWithTypedException(){var snapshots=new PosCardDocumentSnapshot(new ObjectMapper().findAndRegisterModules());assertThatThrownBy(()->snapshots.deserialize("{bad")).isInstanceOf(ApprovedCardSnapshotException.class);assertThatThrownBy(()->snapshots.deserialize("{\"schemaVersion\":3,\"ticket\":null}")).isInstanceOf(ApprovedCardSnapshotException.class);}
 @Test void rejectsSemanticFiscalMismatchWithTypedException(){var snapshots=new PosCardDocumentSnapshot(new ObjectMapper().findAndRegisterModules());var frozen=new ApprovedCardTicketSnapshot(UUID.randomUUID(),UUID.randomUUID(),LocalDate.now(),null,UUID.randomUUID(),BigDecimal.ZERO,new BigDecimal("99"),BigDecimal.ZERO,new BigDecimal("99"),List.of(new DocumentLineCommand(UUID.randomUUID(),BigDecimal.ONE,"P","P",null,BigDecimal.ONE,BigDecimal.ZERO,true,"IVA",new BigDecimal("21"))));assertThatThrownBy(()->snapshots.deserialize(snapshots.serialize(frozen))).isInstanceOf(ApprovedCardSnapshotException.class).hasMessageContaining("no cuadran");}

 @Test void readsLegacyVersionOneJsonWithoutHistoricalReplayMetadata(){
  var snapshots=new PosCardDocumentSnapshot(new ObjectMapper().findAndRegisterModules());
  var frozen=new ApprovedCardTicketSnapshot(UUID.randomUUID(),UUID.randomUUID(),LocalDate.of(2026,7,11),null,UUID.randomUUID(),BigDecimal.ZERO,new BigDecimal("10.00"),new BigDecimal("2.10"),new BigDecimal("12.10"),List.of(new DocumentLineCommand(UUID.randomUUID(),BigDecimal.ONE,"P1","Producto","VENTA",new BigDecimal("12.10"),BigDecimal.ZERO,true,"IVA",new BigDecimal("21"))));
  var legacyJson=snapshots.serialize(frozen)
          .replace("\"schemaVersion\":2","\"schemaVersion\":1")
          .replace(",\"historicalReplay\":null","")
          .replace(",\"adjustments\":[]","");

  assertThat(snapshots.deserialize(legacyJson)).isEqualTo(frozen);
 }

 @Test void preservesAndRestoresDocumentAdjustmentLinks(){
  var document=new CommercialDocument(UUID.randomUUID(),UUID.randomUUID(),CommercialDocumentType.TICKET,
          LocalDate.of(2026,8,23),UUID.randomUUID(),BigDecimal.ZERO);
  var product=new DocumentLine(document,UUID.randomUUID(),1,BigDecimal.ONE,"P1","Producto","VENTA",
          new BigDecimal("12.10"),BigDecimal.ZERO,true,"IVA",new BigDecimal("21"));
  document.addLine(product);
  DocumentPercentDiscountAllocator.apply(document,new BigDecimal("10.00"),Set.of(product.getProductoId()));
  var discount=document.getLineas().getLast();
  var adjustment=new DocumentAdjustment(document,"MANUAL_PERCENT",1,new BigDecimal("10.00"),
          new BigDecimal("12.10"),discount.getTotal().abs(),UUID.randomUUID(),
          java.time.Instant.parse("2026-08-23T10:00:00Z"),null,null,null);
  document.addAdjustment(adjustment);
  discount.linkDocumentAdjustment(adjustment.getId(),product.getId());
  var snapshots=new PosCardDocumentSnapshot(new ObjectMapper().findAndRegisterModules());

  var restoredSnapshot=snapshots.deserialize(snapshots.serialize(
          ApprovedCardTicketSnapshot.from(document,UUID.randomUUID())));
  var restoredDocument=new CommercialDocument(document.getTiendaId(),document.getAlmacenId(),
          CommercialDocumentType.TICKET,document.getFecha(),UUID.randomUUID(),BigDecimal.ZERO);
  restoredSnapshot.lines().forEach(line->restoredDocument.addLine(line.toEntity(restoredDocument)));
  restoredSnapshot.restoreAdjustments(restoredDocument);

  assertThat(restoredDocument.getAjustes()).singleElement().satisfies(restored->{
   assertThat(restored.getTipo()).isEqualTo("MANUAL_PERCENT");
   assertThat(restored.getPorcentaje()).isEqualByComparingTo("10.00");
  });
  assertThat(restoredDocument.getLineas().getLast()).satisfies(line->{
   assertThat(line.getDocumentAdjustmentId()).isEqualTo(restoredDocument.getAjustes().getFirst().getId());
   assertThat(line.getSourceLineId()).isEqualTo(restoredDocument.getLineas().getFirst().getId());
  });
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
