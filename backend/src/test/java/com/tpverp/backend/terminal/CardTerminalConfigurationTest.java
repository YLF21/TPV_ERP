package com.tpverp.backend.terminal;
import static org.assertj.core.api.Assertions.assertThat;import java.util.*;import org.junit.jupiter.api.Test;
class CardTerminalConfigurationTest {
 @Test void isScalarDetachedAndDefensivelyCopiesSafeParameters(){var source=new HashMap<String,String>();source.put("simulatorOutcome","APPROVED");var dto=new CardTerminalConfiguration(UUID.randomUUID(),PaymentCardMode.INTEGRATED,PaymentTerminalProvider.REDSYS_TPV_PC,true,true,"Redsys",source);source.put("simulatorOutcome","DECLINED");assertThat(dto.parameters()).containsEntry("simulatorOutcome","APPROVED");assertThat(Arrays.stream(CardTerminalConfiguration.class.getRecordComponents()).map(c->c.getType().getPackageName())).allMatch(name->name.startsWith("java.")||name.equals("com.tpverp.backend.terminal"));}
}
