package com.tpverp.backend.document;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.tpverp.backend.organization.*;
import com.tpverp.backend.terminal.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class SalePaymentSessionServiceTest {
 @Test void repeatedIntegratedAllocationKeyChargesGatewayOnceAndReloadKeepsApproval(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var allocationId=UUID.randomUUID();var store=mock(Store.class);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var config=new CardTerminalConfiguration(terminalId,storeId,PaymentCardMode.INTEGRATED,PaymentTerminalProvider.PAYTEF,true,true,"PAYTEF","ref",1,"cfg",Map.of());when(configs.required(terminalId)).thenReturn(config);when(ops.charge(eq(allocationId),anyString(),eq(new BigDecimal("10.00")),eq(config))).thenReturn(new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED,"OK","ref","auth","Aprobado"));
  var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops);
  service.add(sessionId,allocationId,"stable",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("10.00"),"PAYTEF",null,auth);
  service.add(sessionId,UUID.randomUUID(),"stable",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("10.00"),"PAYTEF",null,auth);
  verify(ops,times(1)).charge(eq(allocationId),anyString(),eq(new BigDecimal("10.00")),eq(config));assertThat(session.getAllocations()).singleElement().satisfies(a->assertThat(a.getStatus()).isEqualTo(PaymentTerminalOperationStatus.APPROVED));
 }
}
