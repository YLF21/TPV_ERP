package com.tpverp.backend.terminal;
import static org.assertj.core.api.Assertions.assertThat;import java.util.*;import org.junit.jupiter.api.Test;
class CardTerminalConfigurationTest {
 @Test void isScalarDetachedAndDefensivelyCopiesSafeParameters(){var source=new HashMap<String,String>();source.put("simulatorOutcome","APPROVED");var dto=new CardTerminalConfiguration(UUID.randomUUID(),PaymentCardMode.INTEGRATED,PaymentTerminalProvider.REDSYS_TPV_PC,true,true,"Redsys",source);source.put("simulatorOutcome","DECLINED");assertThat(dto.parameters()).containsEntry("simulatorOutcome","APPROVED");assertThat(Arrays.stream(CardTerminalConfiguration.class.getRecordComponents()).map(c->c.getType().getPackageName())).allMatch(name->name.startsWith("java.")||name.equals("com.tpverp.backend.terminal"));}

 @Test void pairingMetadataDoesNotChangeTheFinancialFingerprintOrOperationalVersion(){
  var terminal=terminal();
  var entity=TerminalPaymentConfiguration.manual(terminal);
  entity.configure(new TerminalPaymentConfigurationCommand(PaymentCardMode.INTEGRATED,
    PaymentTerminalProvider.REDSYS_TPV_PC,"Redsys",true,true,Map.of("simulatorOutcome","APPROVED"),null));
  var before=CardTerminalConfiguration.from(entity);
  var pairingId=UUID.randomUUID();
  entity.recordPairing(pairingId,new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED,"PAIRED","ref",null,"ok"));
  var after=CardTerminalConfiguration.from(entity);
  assertThat(after.configurationHash()).isEqualTo(before.configurationHash());
  assertThat(after.configurationVersion()).isEqualTo(before.configurationVersion());
  assertThat(after.parameters()).doesNotContainKeys("_pairingId","_pairingStatus");
 assertThat(entity.getPairingId()).isEqualTo(pairingId);
 }

 private static Terminal terminal(){
  var company=new com.tpverp.backend.organization.Company("B12345678","Demo SL",Map.of("linea1","A","ciudad","B","codigoPostal","35001","provincia","C","pais","ES"));
  var store=new com.tpverp.backend.organization.Store(company,"Caja",Map.of("linea1","A","ciudad","B","codigoPostal","35001","provincia","C","pais","ES"),"hash","Atlantic/Canary","EUR","es-ES");
  return new Terminal(store,"CAJA 1",TerminalType.TERMINAL_VENTA,"credential");
 }
}
