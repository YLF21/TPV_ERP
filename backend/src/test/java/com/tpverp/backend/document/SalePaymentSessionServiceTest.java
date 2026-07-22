package com.tpverp.backend.document;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.tpverp.backend.organization.*;
import com.tpverp.backend.terminal.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import com.tpverp.backend.security.domain.UserAccount;

class SalePaymentSessionServiceTest {
 @Test void discardsSimulationOnlyFromPersistedTestConfiguration(){
  var f=discardFixture();var config=new CardTerminalConfiguration(f.terminalId,f.storeId,PaymentCardMode.INTEGRATED,PaymentTerminalProvider.PAYTEF,true,true,"sim","ref",1,"hash",Map.of());when(f.configs.required(f.terminalId)).thenReturn(config);
  var result=f.service.discardSimulation(f.sessionId,"payment_method_change",f.auth);
  assertThat(result.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);assertThat(result.getAllocations()).hasSize(1);verify(f.repo).save(f.session);verify(f.configs).required(f.terminalId);
 }

 @Test void simulatorDiscardRejectsLiveConfigurationWithoutSaving(){
  var f=discardFixture();when(f.configs.required(f.terminalId)).thenReturn(new CardTerminalConfiguration(f.terminalId,f.storeId,PaymentCardMode.INTEGRATED,PaymentTerminalProvider.PAYTEF,true,false,"live","ref",1,"hash",Map.of()));
  assertThatThrownBy(()->f.service.discardSimulation(f.sessionId,"application_shutdown",f.auth)).hasMessage("simulator_discard_requires_test_mode");verify(f.repo,never()).save(any());
 }

 @Test void simulatorDiscardRejectsWrongTerminalScopeBeforeReadingConfiguration(){
  var f=discardFixture();when(f.terminal.terminalId(f.auth)).thenReturn(UUID.randomUUID());
  assertThatThrownBy(()->f.service.discardSimulation(f.sessionId,"application_shutdown",f.auth)).isInstanceOf(NoSuchElementException.class);verifyNoInteractions(f.configs);verify(f.repo,never()).save(any());
 }

 @Test void simulatorDiscardRejectsMissingSessionWithoutSaving(){
  var f=discardFixture();when(f.repo.findLocked(f.sessionId)).thenReturn(Optional.empty());
  assertThatThrownBy(()->f.service.discardSimulation(f.sessionId,"application_shutdown",f.auth)).isInstanceOf(NoSuchElementException.class);verifyNoInteractions(f.configs);verify(f.repo,never()).save(any());
 }

 @Test void simulatorDiscardRejectsArbitraryReasonBeforeLoadingOrSavingSession(){
  var f=discardFixture();
  assertThatThrownBy(()->f.service.discardSimulation(f.sessionId,"operator_cleanup",f.auth)).hasMessage("simulator_discard_reason_invalid");
  verify(f.repo,never()).findLocked(any());verify(f.repo,never()).save(any());verifyNoInteractions(f.configs);
  assertThat(f.session.getStatus()).isEqualTo(SalePaymentSessionStatus.COLLECTING);assertThat(f.session.getCompensationNote()).isNull();
 }

 @Test void simulatorDiscardedCoveredSessionCannotCreateOrLinkADocument(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",BigDecimal.TEN);session.addAllocation(UUID.randomUUID(),"cash",SalePaymentAllocationKind.CASH,BigDecimal.TEN,null,null).approve(null,null,null);session.discardSimulation("application_shutdown",userId);when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));
  var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops);

  assertThatThrownBy(()->service.finalizeSession(sessionId,auth)).hasMessage("payment_session_not_finalizable");
  assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);assertThat(session.getTicketId()).isNull();
  verifyNoInteractions(docs,snapshots,methods,ops);verify(repo,never()).save(any());
 }

 private static DiscardFixture discardFixture(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",BigDecimal.TEN);var allocation=session.addAllocation(UUID.randomUUID(),"card",SalePaymentAllocationKind.INTEGRATED_CARD,BigDecimal.TEN,"PAYTEF","INTEGRATED");allocation.result(PaymentTerminalOperationStatus.TIMEOUT,allocation.getId(),null,null,"uncertain");when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops);return new DiscardFixture(repo,org,terminal,configs,auth,session,service,storeId,terminalId,sessionId);
 }

 private record DiscardFixture(SalePaymentSessionRepository repo,CurrentOrganization org,CurrentTerminal terminal,CardTerminalConfigurationReader configs,Authentication auth,SalePaymentSession session,SalePaymentSessionService service,UUID storeId,UUID terminalId,UUID sessionId){}

 @Test void compensationAcknowledgementUsesDurableOperationTruthAfterTimeout(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var operationId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));var allocation=session.addAllocation(operationId,"card",SalePaymentAllocationKind.INTEGRATED_CARD,BigDecimal.TEN,"PAYTEF","INTEGRATED");allocation.result(PaymentTerminalOperationStatus.TIMEOUT,operationId,null,null,"incierto");session.cancel();when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var durable=mock(PaymentTerminalOperation.class);when(ops.find(operationId)).thenReturn(Optional.of(durable));var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops);
  when(durable.getStatus()).thenReturn(PaymentTerminalOperationStatus.APPROVED);when(ops.recover(eq(operationId),any(UUID.class))).thenReturn(durable);
  service.query(sessionId,operationId,auth);
  assertThat(allocation.getStatus()).isEqualTo(PaymentTerminalOperationStatus.TIMEOUT);
  assertThatThrownBy(()->service.acknowledgeCompensation(sessionId,"revisado",auth)).hasMessage("integrated_compensation_unresolved");
  assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.COMPENSATION_REQUIRED);
  when(durable.getStatus()).thenReturn(PaymentTerminalOperationStatus.CANCELLED);
  service.acknowledgeCompensation(sessionId,"anulado en terminal",auth);
  assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);
 }

 @Test void refundedDurableOperationAlsoAllowsCompensationAcknowledgement(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var operationId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",BigDecimal.TEN);var allocation=session.addAllocation(operationId,"card",SalePaymentAllocationKind.INTEGRATED_CARD,BigDecimal.TEN,"PAYTEF","INTEGRATED");allocation.result(PaymentTerminalOperationStatus.TIMEOUT,operationId,null,null,"incierto");session.cancel();when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var durable=mock(PaymentTerminalOperation.class);when(durable.getStatus()).thenReturn(PaymentTerminalOperationStatus.REFUNDED);when(ops.find(operationId)).thenReturn(Optional.of(durable));var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops);
  service.acknowledgeCompensation(sessionId,"reembolsado",auth);
  assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);
 }

 @Test void manualReplayMustMatchReference(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops);
  service.add(sessionId,UUID.randomUUID(),"manual",SalePaymentAllocationKind.MANUAL_CARD,new BigDecimal("10.00"),null,"REF-1",auth);
  assertThatThrownBy(()->service.add(sessionId,UUID.randomUUID(),"manual",SalePaymentAllocationKind.MANUAL_CARD,new BigDecimal("10.00"),null,"REF-2",auth)).hasMessage("allocation_idempotency_conflict");
 }

 @Test void integratedAllocationRejectsDisabledWrongModeNoneProviderAndStoreRule(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var rules=mock(StorePaymentConfigurationRepository.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));when(repo.findState(sessionId)).thenReturn(Optional.of(session));var storeRules=mock(StorePaymentConfiguration.class);when(storeRules.isIntegratedCardEnabled()).thenReturn(true);when(storeRules.getAllowedPaymentTerminalProviders()).thenReturn("PAYTEF");when(rules.findByStoreId(storeId)).thenReturn(Optional.of(storeRules));var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,rules);
  when(configs.required(terminalId)).thenReturn(new CardTerminalConfiguration(terminalId,storeId,PaymentCardMode.INTEGRATED,PaymentTerminalProvider.PAYTEF,false,true,"x","r",1,"h",Map.of()));
  assertThatThrownBy(()->service.add(sessionId,UUID.randomUUID(),"a",SalePaymentAllocationKind.INTEGRATED_CARD,BigDecimal.TEN,"PAYTEF",null,auth)).hasMessage("payment_terminal_configuration_not_enabled");
  when(configs.required(terminalId)).thenReturn(new CardTerminalConfiguration(terminalId,storeId,PaymentCardMode.MANUAL,PaymentTerminalProvider.PAYTEF,true,true,"x","r",1,"h",Map.of()));
  assertThatThrownBy(()->service.add(sessionId,UUID.randomUUID(),"b",SalePaymentAllocationKind.INTEGRATED_CARD,BigDecimal.TEN,"PAYTEF",null,auth)).hasMessage("payment_terminal_configuration_not_integrated");
  when(configs.required(terminalId)).thenReturn(new CardTerminalConfiguration(terminalId,storeId,PaymentCardMode.INTEGRATED,PaymentTerminalProvider.NONE,true,true,"x","r",1,"h",Map.of()));
  assertThatThrownBy(()->service.add(sessionId,UUID.randomUUID(),"c",SalePaymentAllocationKind.INTEGRATED_CARD,BigDecimal.TEN,"NONE",null,auth)).hasMessage("payment_terminal_provider_required");
  when(configs.required(terminalId)).thenReturn(new CardTerminalConfiguration(terminalId,storeId,PaymentCardMode.INTEGRATED,PaymentTerminalProvider.GLOBAL_PAYMENTS,true,true,"x","r",1,"h",Map.of()));
  assertThatThrownBy(()->service.add(sessionId,UUID.randomUUID(),"d",SalePaymentAllocationKind.INTEGRATED_CARD,BigDecimal.TEN,"GLOBAL_PAYMENTS",null,auth)).hasMessage("payment_terminal_provider_not_allowed");
 }
 @Test void repeatedIntegratedAllocationKeyChargesGatewayOnceAndReloadKeepsApproval(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var allocationId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.findState(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var config=new CardTerminalConfiguration(terminalId,storeId,PaymentCardMode.INTEGRATED,PaymentTerminalProvider.PAYTEF,true,true,"PAYTEF","ref",1,"cfg",Map.of());when(configs.required(terminalId)).thenReturn(config);when(ops.charge(eq(allocationId),anyString(),eq(new BigDecimal("10.00")),eq(config))).thenAnswer(invocation->{assertThat(invocation.getArgument(1,String.class)).matches("[a-f0-9]{64}");assertThat(session.getAllocations()).singleElement().satisfies(a->assertThat(a.getOperationId()).isNull());return new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED,"OK","ref","auth","Aprobado");});
  var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops);
  service.add(sessionId,allocationId,"stable",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("10.00"),"PAYTEF",null,auth);
  service.add(sessionId,UUID.randomUUID(),"stable",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("10.00"),"PAYTEF",null,auth);
  verify(ops,times(1)).charge(eq(allocationId),anyString(),eq(new BigDecimal("10.00")),eq(config));assertThat(session.getAllocations()).singleElement().satisfies(a->assertThat(a.getStatus()).isEqualTo(PaymentTerminalOperationStatus.APPROVED));session.cancel();var unresolved=mock(PaymentTerminalOperation.class);when(unresolved.getStatus()).thenReturn(PaymentTerminalOperationStatus.APPROVED);when(ops.find(allocationId)).thenReturn(Optional.of(unresolved));assertThatThrownBy(()->service.acknowledgeCompensation(sessionId,"resuelto",auth)).hasMessage("integrated_compensation_unresolved");
  var other=mock(UserAccount.class);when(other.getId()).thenReturn(UUID.randomUUID());when(auth.getPrincipal()).thenReturn(other);assertThatThrownBy(()->service.get(sessionId,auth)).isInstanceOf(NoSuchElementException.class);
 }
 @Test void failureAfterPendingCommitKeepsStableAllocationAndRetryDoesNotCreateAnother(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var allocationId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var config=new CardTerminalConfiguration(terminalId,storeId,PaymentCardMode.INTEGRATED,PaymentTerminalProvider.PAYTEF,true,true,"PAYTEF","ref",1,"cfg",Map.of());when(configs.required(terminalId)).thenThrow(new IllegalStateException("offline")).thenReturn(config);when(ops.charge(eq(allocationId),anyString(),eq(new BigDecimal("10.00")),eq(config))).thenReturn(new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED,"OK","ref","auth","Aprobado"));var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops);
  when(repo.findState(sessionId)).thenReturn(Optional.of(session));assertThatThrownBy(()->service.add(sessionId,UUID.randomUUID(),"manual",SalePaymentAllocationKind.MANUAL_CARD,new BigDecimal("1.00"),null," ",auth)).hasMessage("manual_card_reference_required");assertThat(session.getAllocations()).isEmpty();assertThatThrownBy(()->service.add(sessionId,allocationId,"stable",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("10.00"),"PAYTEF",null,auth)).hasMessage("offline");assertThat(session.getAllocations()).isEmpty();service.add(sessionId,allocationId,"stable",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("10.00"),"PAYTEF",null,auth);assertThat(session.getAllocations()).singleElement().satisfies(a->{assertThat(a.getId()).isEqualTo(allocationId);assertThat(a.getOperationId()).isEqualTo(allocationId);});verify(ops,times(1)).charge(eq(allocationId),anyString(),any(),eq(config));
 }
 @Test void finalizeRejectsAnIntegratedAllocationWhoseChargeWasAdjusted(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var allocationId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));session.addAllocation(allocationId,"card",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("10.00"),"PAYTEF","INTEGRATED").approve(allocationId,"ref","auth");when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));doThrow(new IllegalStateException("payment_operation_not_finalizable")).when(ops).requireFinalizableApprovedCharge(allocationId);
  var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops);
  assertThatThrownBy(()->service.finalizeSession(sessionId,auth)).hasMessage("payment_operation_not_finalizable");
  verify(docs,never()).createApprovedCardTicketFromSnapshot(any(),any(),eq(auth));
 }

 @Test void voucherAllocationChecksBalanceAndCannotReuseTheSameCodeInOneSession(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var vouchers=mock(VoucherService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));when(vouchers.availableBalance("V-100")).thenReturn(new BigDecimal("20.00"));var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops);service.setVoucherService(vouchers);

  service.add(sessionId,UUID.randomUUID(),"voucher-1",SalePaymentAllocationKind.VOUCHER,new BigDecimal("5.00"),null,"V-100",auth);

  assertThat(session.getAllocations()).singleElement().satisfies(allocation->{assertThat(allocation.getKind()).isEqualTo(SalePaymentAllocationKind.VOUCHER);assertThat(allocation.getReference()).isEqualTo("V-100");assertThat(allocation.getStatus()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);});
  assertThatThrownBy(()->service.add(sessionId,UUID.randomUUID(),"voucher-2",SalePaymentAllocationKind.VOUCHER,new BigDecimal("5.00"),null,"V-100",auth)).hasMessage("voucher_already_allocated");
  verify(vouchers,times(2)).availableBalance("V-100");
 }

 @Test void blocksAnotherAllocationWhileIntegratedResultIsUncertain(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var operationId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",BigDecimal.TEN);var allocation=session.addAllocation(operationId,"card",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("5.00"),"PAYTEF","INTEGRATED");allocation.result(PaymentTerminalOperationStatus.TIMEOUT,operationId,null,null,"resultado incierto");when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));var durable=mock(PaymentTerminalOperation.class);when(durable.getStatus()).thenReturn(PaymentTerminalOperationStatus.TIMEOUT);when(ops.find(operationId)).thenReturn(Optional.of(durable));var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops);

  assertThatThrownBy(()->service.add(sessionId,UUID.randomUUID(),"cash",SalePaymentAllocationKind.CASH,new BigDecimal("5.00"),null,null,auth)).hasMessage("integrated_payment_result_uncertain");
  assertThat(session.getAllocations()).hasSize(1);verify(repo,never()).save(any());
 }
}
