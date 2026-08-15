package com.tpverp.backend.document;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.organization.*;
import com.tpverp.backend.terminal.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.control.ControlAlertDetectionService;

class SalePaymentSessionServiceTest {
 private static CashPaymentRecorder cashPayments(){return mock(CashPaymentRecorder.class);}
 private static SecurityFixture security(UserAccount operator,Authentication auth){
  when(operator.getUserName()).thenReturn("CAJERO");var operationSecurity=mock(SaleOperationSecurityService.class);var audit=mock(AuditService.class);when(operationSecurity.authorize(any(SaleOperationCode.class),nullable(String.class),nullable(String.class),eq(auth))).thenReturn(new Authorization(operator,operator,false));return new SecurityFixture(operationSecurity,audit);
 }
 private record SecurityFixture(SaleOperationSecurityService operationSecurity,AuditService audit){}

 @Test void reserveRecoversTheActiveSessionWhenTheSameSaleIsRetriedWithAnotherRequestId(){
  var fixture=reservationFixture();

  var first=fixture.service.reserve(UUID.randomUUID(),fixture.sale,fixture.auth);
  var recovered=fixture.service.reserve(UUID.randomUUID(),fixture.sale,fixture.auth);

  assertThat(recovered).isSameAs(first);
  verify(fixture.repo,times(1)).save(any(SalePaymentSession.class));
  verify(fixture.sales,times(1)).authorizeSensitiveOperations(any(),eq(fixture.sale),any(),eq(fixture.auth),eq("PAYMENT_SESSION"),any());
 }

 @Test void reserveRetriesAnExistingSessionBeforeRepricingItsHistoricalSource(){
  var fixture=reservationFixture();var sessionId=UUID.randomUUID();
  var first=fixture.service.reserve(sessionId,fixture.sale,fixture.auth);
  when(fixture.repo.findState(sessionId)).thenReturn(Optional.of(first));
  when(fixture.sales.prepareSale(fixture.sale,fixture.auth))
          .thenThrow(new IllegalStateException("previous_ticket_changed"));

  var retried=fixture.service.reserve(sessionId,fixture.sale,fixture.auth);

  assertThat(retried).isSameAs(first);
  verify(fixture.sales,times(1)).prepareSale(fixture.sale,fixture.auth);
 }

 @Test void reserveRetriesAnExistingRefundWithTheOriginalSignedRequestHash(){
  var fixture=reservationFixture();var sessionId=UUID.randomUUID();
  var refundTotal=new BigDecimal("-100.00");
  var refund=SalePaymentSession.reserve(
          sessionId,fixture.storeId,fixture.terminalId,fixture.userId,
          SalePaymentSessionService.hash(fixture.sale,refundTotal),"{}",refundTotal);
  when(fixture.repo.findState(sessionId)).thenReturn(Optional.of(refund));

  var retried=fixture.service.reserve(sessionId,fixture.sale,fixture.auth);

  assertThat(retried).isSameAs(refund);
  verify(fixture.sales,never()).prepareSale(fixture.sale,fixture.auth);
 }

 @Test void reserveDoesNotAttachAnActiveSessionFromADifferentSale(){
  var fixture=reservationFixture();
  fixture.service.reserve(UUID.randomUUID(),fixture.sale,fixture.auth);
  var otherSale=new PosCashController.SaleRequest(null,List.of(
          new PosCashController.LineRequest(UUID.randomUUID(),BigDecimal.ONE,BigDecimal.ZERO)));
  when(fixture.sales.prepareSale(otherSale,fixture.auth)).thenReturn(
          new PosCashService.PreparedSale(fixture.command,Set.of()));

  assertThatThrownBy(()->fixture.service.reserve(UUID.randomUUID(),otherSale,fixture.auth))
          .hasMessage("payment_session_active_conflict");
  verify(fixture.repo,times(1)).save(any(SalePaymentSession.class));
 }

 private static ReservationFixture reservationFixture(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var companyId=UUID.randomUUID();var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var warehouseId=UUID.randomUUID();var productId=UUID.randomUUID();var company=mock(Company.class);var store=mock(Store.class);var user=mock(UserAccount.class);when(company.getId()).thenReturn(companyId);when(store.getId()).thenReturn(storeId);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(org.currentCompany()).thenReturn(company);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var sale=new PosCashController.SaleRequest(null,List.of(new PosCashController.LineRequest(productId,BigDecimal.ONE,BigDecimal.ZERO)));var command=mock(DocumentCommand.class);when(command.lineas()).thenReturn(List.of());when(sales.prepareSale(sale,auth)).thenReturn(new PosCashService.PreparedSale(command,Set.of()));var quote=mock(CommercialDocument.class);when(quote.getTiendaId()).thenReturn(storeId);when(quote.getAlmacenId()).thenReturn(warehouseId);when(quote.getFecha()).thenReturn(java.time.LocalDate.of(2026,8,4));when(quote.getDescuentoGlobal()).thenReturn(BigDecimal.ZERO);when(quote.getBaseTotal()).thenReturn(new BigDecimal("100.00"));when(quote.getImpuestoTotal()).thenReturn(BigDecimal.ZERO);when(quote.getTotal()).thenReturn(new BigDecimal("100.00"));when(quote.getLineas()).thenReturn(List.of());when(sales.quotePreparedSale(any(),any(),eq(auth))).thenReturn(quote);
  var method=new PaymentMethod(companyId,"EFECTIVO",true);when(methods.findAllByEmpresaIdOrderByNombre(companyId)).thenReturn(List.of(method));when(snapshots.serialize(any())).thenReturn("{}");var active=new java.util.concurrent.atomic.AtomicReference<SalePaymentSession>();when(repo.findState(any())).thenReturn(Optional.empty());when(repo.findActive(storeId,terminalId,userId)).thenAnswer(invocation->Optional.ofNullable(active.get()));when(repo.save(any(SalePaymentSession.class))).thenAnswer(invocation->{var session=invocation.getArgument(0,SalePaymentSession.class);active.set(session);return session;});var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments());
  return new ReservationFixture(
          repo,sales,auth,sale,command,service,storeId,terminalId,userId);
 }

 private record ReservationFixture(
         SalePaymentSessionRepository repo,
         PosCashService sales,
         Authentication auth,
         PosCashController.SaleRequest sale,
         DocumentCommand command,
         SalePaymentSessionService service,
         UUID storeId,
         UUID terminalId,
         UUID userId){}

 @Test void zeroAndNegativeTotalsKeepTheirDocumentDirectionWithoutNegativePaymentAllocations(){
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();
  var zero=SalePaymentSession.reserve(UUID.randomUUID(),storeId,terminalId,userId,"hash","{}",BigDecimal.ZERO);
  assertThat(zero.getDirection()).isEqualTo(SalePaymentSessionDirection.ZERO);
  assertThat(zero.getDocumentTotal()).isEqualByComparingTo("0.00");
  assertThat(zero.getStatus()).isEqualTo(SalePaymentSessionStatus.COVERED);
  assertThat(zero.isCovered()).isTrue();

  var refund=SalePaymentSession.reserve(UUID.randomUUID(),storeId,terminalId,userId,"hash","{}",new BigDecimal("-10.00"));
  assertThat(refund.getDirection()).isEqualTo(SalePaymentSessionDirection.REFUND);
  assertThat(refund.getTotal()).isEqualByComparingTo("10.00");
  assertThat(refund.getDocumentTotal()).isEqualByComparingTo("-10.00");
  assertThat(refund.getStatus()).isEqualTo(SalePaymentSessionStatus.COLLECTING);
 }

 @Test void monetaryRefundAgainstStorePolicyRequiresAuthorizationAndEmitsControlAlert(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var cash=mock(CashPaymentRecorder.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var operatorId=UUID.randomUUID();var managerId=UUID.randomUUID();var sessionId=UUID.randomUUID();var allocationId=UUID.randomUUID();var sourceTicketId=UUID.randomUUID();
  var store=mock(Store.class);var operator=mock(UserAccount.class);var manager=mock(UserAccount.class);when(operator.getId()).thenReturn(operatorId);when(operator.getUserName()).thenReturn("CAJERO");when(manager.getId()).thenReturn(managerId);when(manager.getUserName()).thenReturn("ENCARGADO");when(auth.getPrincipal()).thenReturn(operator);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(org.currentCompany()).thenReturn(null);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,operatorId,"hash","{}",new BigDecimal("-10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));
  var line=mock(DocumentLineCommand.class);when(line.originalDocumentLineId()).thenReturn(UUID.randomUUID());when(line.cantidad()).thenReturn(BigDecimal.ONE.negate());when(line.returnSourceTicketId()).thenReturn(sourceTicketId);var snapshot=mock(ApprovedCardTicketSnapshot.class);when(snapshot.lines()).thenReturn(List.of(line));when(snapshots.deserialize("{}")).thenReturn(snapshot);
  var source=mock(CommercialDocument.class);var originalPayment=mock(DocumentPayment.class);var cashMethod=mock(PaymentMethod.class);when(cashMethod.getNombre()).thenReturn("EFECTIVO");when(originalPayment.getId()).thenReturn(UUID.randomUUID());when(originalPayment.getImporte()).thenReturn(new BigDecimal("10.00"));when(originalPayment.getMetodoPago()).thenReturn(cashMethod);when(source.getPagos()).thenReturn(List.of(originalPayment));when(docs.find(sourceTicketId)).thenReturn(source);
  var security=mock(SaleOperationSecurityService.class);var audit=mock(AuditService.class);when(security.authorize(SaleOperationCode.REFUND_POLICY_OVERRIDE,"ENCARGADO","secret",auth)).thenReturn(new Authorization(operator,manager,true));
  var policy=mock(StoreReturnPolicyService.class);when(policy.policy()).thenReturn(StoreReturnPolicy.EXCHANGE_OR_VOUCHER_ONLY);var alerts=mock(ControlAlertDetectionService.class);
  var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cash,security,audit);service.setStoreReturnPolicyService(policy);service.setControlAlerts(alerts);

  service.add(sessionId,allocationId,"refund-cash",SalePaymentAllocationKind.CASH,new BigDecimal("10.00"),null,null,null,new BigDecimal("10.00"),BigDecimal.ZERO,null,null,new OperationAuthorizationRequest("ENCARGADO","secret"),auth);

  assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.COVERED);
  verify(security).authorize(SaleOperationCode.REFUND_POLICY_OVERRIDE,"ENCARGADO","secret",auth);
  verify(audit).record(eq("REFUND_POLICY_OVERRIDE_AUTHORIZED"),eq(AuditResult.EXITO),argThat(details->details.get("authorizerUsername").equals("ENCARGADO")&&!details.containsKey("authorizerPassword")));
  verify(alerts).detectRefundPolicyOverride(sessionId,terminalId,new BigDecimal("10.00"),"CASH",managerId,"ENCARGADO",true,auth);
 }

 @Test void cashRefundOfACardPaymentRequiresExplicitTenderOverride(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var cash=mock(CashPaymentRecorder.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var sourceTicketId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(user.getUserName()).thenReturn("CAJERO");when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(org.currentCompany()).thenReturn(null);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","snapshot",new BigDecimal("-10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));
  var line=mock(DocumentLineCommand.class);when(line.originalDocumentLineId()).thenReturn(UUID.randomUUID());when(line.cantidad()).thenReturn(BigDecimal.ONE.negate());when(line.returnSourceType()).thenReturn(TicketReturnService.ReturnSourceType.TICKET);when(line.returnSourceTicketId()).thenReturn(sourceTicketId);var snapshot=mock(ApprovedCardTicketSnapshot.class);when(snapshot.lines()).thenReturn(List.of(line));when(snapshots.deserialize("snapshot")).thenReturn(snapshot);
  var source=mock(CommercialDocument.class);var originalPayment=mock(DocumentPayment.class);var cardMethod=mock(PaymentMethod.class);var originalPaymentId=UUID.randomUUID();when(cardMethod.getNombre()).thenReturn("TARJETA");when(originalPayment.getId()).thenReturn(originalPaymentId);when(originalPayment.getImporte()).thenReturn(new BigDecimal("10.00"));when(originalPayment.getMetodoPago()).thenReturn(cardMethod);when(source.getPagos()).thenReturn(List.of(originalPayment));when(docs.find(sourceTicketId)).thenReturn(source);
  var security=mock(SaleOperationSecurityService.class);var audit=mock(AuditService.class);when(security.authorize(SaleOperationCode.REFUND_TENDER_OVERRIDE,"ENCARGADO","secret",auth)).thenReturn(new Authorization(user,user,false));
  var policy=mock(StoreReturnPolicyService.class);when(policy.policy()).thenReturn(StoreReturnPolicy.REFUND_ALLOWED);var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cash,security,audit);service.setStoreReturnPolicyService(policy);

  assertThatThrownBy(()->service.add(sessionId,UUID.randomUUID(),"cash-refund-of-card-sale",SalePaymentAllocationKind.CASH,new BigDecimal("10.00"),null,null,null,new BigDecimal("10.00"),BigDecimal.ZERO,null,null,null,auth))
          .isInstanceOf(RefundTenderOverrideRequiredException.class);
  service.add(sessionId,UUID.randomUUID(),"authorized-cash-refund",SalePaymentAllocationKind.CASH,new BigDecimal("10.00"),null,null,null,new BigDecimal("10.00"),BigDecimal.ZERO,null,null,null,new OperationAuthorizationRequest("ENCARGADO","secret"),auth);

  assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.COVERED);
  assertThat(session.getAllocations()).singleElement().satisfies(allocation->{assertThat(allocation.getKind()).isEqualTo(SalePaymentAllocationKind.CASH);assertThat(allocation.getOriginalPaymentId()).isEqualTo(originalPaymentId);});
  verify(cash,times(2)).requireOpenSession(terminalId);
  verify(security).authorize(SaleOperationCode.REFUND_TENDER_OVERRIDE,"ENCARGADO","secret",auth);
  verify(audit).record(eq("REFUND_TENDER_OVERRIDE_AUTHORIZED"),eq(AuditResult.EXITO),anyMap());
 }

 @Test void voucherRefundRemainsAvailableWhenTheStoreDoesNotReturnMoney(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("-10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var security=mock(SaleOperationSecurityService.class);var audit=mock(AuditService.class);var policy=mock(StoreReturnPolicyService.class);when(policy.policy()).thenReturn(StoreReturnPolicy.EXCHANGE_OR_VOUCHER_ONLY);
  var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments(),security,audit);service.setStoreReturnPolicyService(policy);

  service.add(sessionId,UUID.randomUUID(),"refund-voucher",SalePaymentAllocationKind.VOUCHER,new BigDecimal("10.00"),null,null,null,null,null,null,null,null,auth);

  assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.COVERED);
  assertThat(session.getAllocations()).singleElement().satisfies(allocation->{assertThat(allocation.getKind()).isEqualTo(SalePaymentAllocationKind.VOUCHER);assertThat(allocation.getVoucherCode()).isNull();});
  verifyNoInteractions(security,audit);
 }

 @Test void giftReceiptRefundRejectsMoneyAndOnlyAllowsANewVoucher(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var cash=mock(CashPaymentRecorder.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(org.currentCompany()).thenReturn(null);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","gift-snapshot",new BigDecimal("-10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));
  var line=mock(DocumentLineCommand.class);when(line.originalDocumentLineId()).thenReturn(UUID.randomUUID());when(line.cantidad()).thenReturn(BigDecimal.ONE.negate());when(line.returnSourceType()).thenReturn(TicketReturnService.ReturnSourceType.GIFT_RECEIPT);var snapshot=mock(ApprovedCardTicketSnapshot.class);when(snapshot.lines()).thenReturn(List.of(line));when(snapshots.deserialize("gift-snapshot")).thenReturn(snapshot);
  var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cash);

  assertThat(service.requiresVoucherOnlyRefund(session)).isTrue();
  assertThatThrownBy(()->service.add(sessionId,UUID.randomUUID(),"gift-cash",SalePaymentAllocationKind.CASH,new BigDecimal("10.00"),null,null,null,new BigDecimal("10.00"),BigDecimal.ZERO,null,null,null,auth))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("gift_receipt_money_refund_not_allowed");

  service.add(sessionId,UUID.randomUUID(),"gift-voucher",SalePaymentAllocationKind.VOUCHER,new BigDecimal("10.00"),null,null,null,null,null,null,null,null,auth);

  assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.COVERED);
  assertThat(session.getAllocations()).singleElement().satisfies(allocation ->
          assertThat(allocation.getKind()).isEqualTo(SalePaymentAllocationKind.VOUCHER));
  verifyNoInteractions(cash);
 }

 @Test void finalizingANegativeCheckoutCreatesAFiscalRectificationInsteadOfAGenericTicket(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var cash=mock(CashPaymentRecorder.class);var settlements=mock(RefundSettlementRecorder.class);var valuations=mock(TicketReturnValuationService.class);var auth=mock(Authentication.class);
  var companyId=UUID.randomUUID();var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var sourceTicketId=UUID.randomUUID();var sourceLineId=UUID.randomUUID();var productId=UUID.randomUUID();var originalPaymentId=UUID.randomUUID();
  var company=mock(Company.class);var store=mock(Store.class);var user=mock(UserAccount.class);when(company.getId()).thenReturn(companyId);when(store.getId()).thenReturn(storeId);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(org.currentCompany()).thenReturn(company);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","snapshot",new BigDecimal("-10.00"));var allocation=session.addAllocation(UUID.randomUUID(),"cash",SalePaymentAllocationKind.CASH,new BigDecimal("10.00"),null,null);allocation.assignOriginalPaymentId(originalPaymentId);allocation.approve(null,null,null);when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));
  var line=new DocumentLineCommand(productId,new BigDecimal("-1"),"P-1","Producto","DEVOLUCION",new BigDecimal("10.00"),BigDecimal.ZERO,true,"GENERAL",new BigDecimal("21"),DocumentLineType.PRODUCT,null,null,null,List.of(),false,false,TicketReturnService.ReturnSourceType.TICKET,"T-1",sourceTicketId,sourceLineId,null);var snapshot=new ApprovedCardTicketSnapshot(storeId,UUID.randomUUID(),java.time.LocalDate.of(2026,8,4),null,UUID.randomUUID(),BigDecimal.ZERO,new BigDecimal("-8.26"),new BigDecimal("-1.74"),new BigDecimal("-10.00"),List.of(line));when(snapshots.deserialize("snapshot")).thenReturn(snapshot);
  var original=mock(CommercialDocument.class);when(docs.find(sourceTicketId)).thenReturn(original);var valuation=new TicketReturnValuationService.Valuation(new BigDecimal("10.00"),BigDecimal.ZERO,new BigDecimal("10.00"),new BigDecimal("10.00"),new BigDecimal("10.00"),new BigDecimal("10.00"),BigDecimal.ZERO,BigDecimal.ZERO,List.of());when(valuations.value(eq(original),anyMap())).thenReturn(valuation);
  var cashMethod=new PaymentMethod(companyId,"EFECTIVO",true);when(methods.findByEmpresaIdAndNombreAndActivoTrue(companyId,"EFECTIVO")).thenReturn(Optional.of(cashMethod));var ticket=mock(CommercialDocument.class);var ticketId=UUID.randomUUID();when(ticket.getId()).thenReturn(ticketId);when(ticket.getNumero()).thenReturn("R-1");when(ticket.getEstado()).thenReturn(DocumentStatus.CONFIRMADO);when(ticket.getConfirmadoEn()).thenReturn(java.time.Instant.parse("2026-08-04T10:00:00Z"));when(ticket.getLineas()).thenReturn(List.of());when(ticket.getPagos()).thenReturn(List.of());when(ticket.getTotal()).thenReturn(new BigDecimal("-10.00"));when(ticket.getBaseTotal()).thenReturn(new BigDecimal("-8.26"));when(ticket.getImpuestoTotal()).thenReturn(new BigDecimal("-1.74"));when(docs.createApprovedReturn(eq(sessionId),eq(sourceTicketId),eq(new BigDecimal("10.00")),anyList(),isNull(),eq(valuation),eq(auth))).thenReturn(ticket);when(settlements.recordExistingNegativeTicket(eq(ticket),anyList(),eq(auth))).thenReturn(ticket);
  var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cash);service.setRefundSettlementRecorder(settlements);service.setTicketReturnValuationService(valuations);

  var result=service.finalizeSession(sessionId,auth);

  assertThat(result.session().getTicketId()).isEqualTo(ticketId);
  verify(docs).createApprovedReturn(eq(sessionId),eq(sourceTicketId),eq(new BigDecimal("10.00")),argThat(values->values.size()==1&&values.getFirst().lineId().equals(sourceLineId)&&values.getFirst().quantity().compareTo(BigDecimal.ONE)==0),isNull(),eq(valuation),eq(auth));
  verify(docs,never()).createApprovedCardTicketFromSnapshot(any(),anyList(),any());
  verify(settlements).recordExistingNegativeTicket(eq(ticket),argThat(values->values.size()==1&&values.getFirst().type()==RefundTenderType.CASH&&values.getFirst().originalPaymentId().equals(originalPaymentId)),eq(auth));
 }

 @Test void finalizingAVoucherRefundReturnsTheExactIssuedVoucher(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var cash=mock(CashPaymentRecorder.class);var settlements=mock(RefundSettlementRecorder.class);var valuations=mock(TicketReturnValuationService.class);var vouchers=mock(VoucherService.class);var auth=mock(Authentication.class);
  var companyId=UUID.randomUUID();var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var sourceTicketId=UUID.randomUUID();var sourceLineId=UUID.randomUUID();var productId=UUID.randomUUID();
  var company=mock(Company.class);var store=mock(Store.class);var user=mock(UserAccount.class);when(company.getId()).thenReturn(companyId);when(store.getId()).thenReturn(storeId);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(org.currentCompany()).thenReturn(company);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","snapshot",new BigDecimal("-10.00"));var allocation=session.addAllocation(UUID.randomUUID(),"voucher",SalePaymentAllocationKind.VOUCHER,new BigDecimal("10.00"),null,null);allocation.approve(null,null,null);when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));
  var line=new DocumentLineCommand(productId,new BigDecimal("-1"),"P-1","Producto","DEVOLUCION",new BigDecimal("10.00"),BigDecimal.ZERO,true,"GENERAL",new BigDecimal("21"),DocumentLineType.PRODUCT,null,null,null,List.of(),false,false,TicketReturnService.ReturnSourceType.TICKET,"T-1",sourceTicketId,sourceLineId,null);var snapshot=new ApprovedCardTicketSnapshot(storeId,UUID.randomUUID(),java.time.LocalDate.of(2026,8,4),null,UUID.randomUUID(),BigDecimal.ZERO,new BigDecimal("-8.26"),new BigDecimal("-1.74"),new BigDecimal("-10.00"),List.of(line));when(snapshots.deserialize("snapshot")).thenReturn(snapshot);
  var original=mock(CommercialDocument.class);when(docs.find(sourceTicketId)).thenReturn(original);var valuation=new TicketReturnValuationService.Valuation(new BigDecimal("10.00"),BigDecimal.ZERO,new BigDecimal("10.00"),new BigDecimal("10.00"),new BigDecimal("10.00"),new BigDecimal("10.00"),BigDecimal.ZERO,BigDecimal.ZERO,List.of());when(valuations.value(eq(original),anyMap())).thenReturn(valuation);
  var voucherMethod=new PaymentMethod(companyId,"VALE",true);when(methods.findByEmpresaIdAndNombreAndActivoTrue(companyId,"VALE")).thenReturn(Optional.of(voucherMethod));var ticket=mock(CommercialDocument.class);var ticketId=UUID.randomUUID();when(ticket.getId()).thenReturn(ticketId);when(ticket.getNumero()).thenReturn("R-VALE-1");when(ticket.getEstado()).thenReturn(DocumentStatus.CONFIRMADO);when(ticket.getConfirmadoEn()).thenReturn(java.time.Instant.parse("2026-08-04T10:00:00Z"));when(ticket.getLineas()).thenReturn(List.of());when(ticket.getPagos()).thenReturn(List.of());when(ticket.getTotal()).thenReturn(new BigDecimal("-10.00"));when(ticket.getBaseTotal()).thenReturn(new BigDecimal("-8.26"));when(ticket.getImpuestoTotal()).thenReturn(new BigDecimal("-1.74"));when(docs.createApprovedReturn(eq(sessionId),eq(sourceTicketId),eq(new BigDecimal("10.00")),anyList(),isNull(),eq(valuation),eq(auth))).thenReturn(ticket);when(settlements.recordExistingNegativeTicket(eq(ticket),anyList(),eq(auth))).thenReturn(ticket);
  var issued=new Voucher(storeId,"V-EXACTO",new BigDecimal("10.00"),List.of("R-VALE-1"),java.time.Instant.parse("2026-08-04T10:00:01Z"));when(vouchers.issueOrFindFromNegativeTicket(ticket,new BigDecimal("10.00"))).thenReturn(issued);var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cash);service.setRefundSettlementRecorder(settlements);service.setTicketReturnValuationService(valuations);service.setVoucherService(vouchers);

  var result=service.finalizeSession(sessionId,auth);

  assertThat(result.issuedVoucher()).isEqualTo(new SalePaymentSessionService.IssuedVoucher("V-EXACTO",new BigDecimal("10.00"),java.time.Instant.parse("2026-08-04T10:00:01Z"),"R-VALE-1"));
  verify(vouchers).issueOrFindFromNegativeTicket(ticket,new BigDecimal("10.00"));
 }

 @Test void retryingFinalizedVoucherPaymentReturnsAndPrintsTheReplacementVoucher(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var vouchers=mock(VoucherService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var ticketId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("20.00"));session.addAllocation(UUID.randomUUID(),"voucher",SalePaymentAllocationKind.VOUCHER,new BigDecimal("20.00"),null,null).approve(null,null,null);session.finalizeWith(ticketId,"T-REPLACEMENT");when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));
  var print=new TicketPrintView(ticketId,"T-REPLACEMENT",java.time.Instant.parse("2026-08-09T12:00:00Z"),List.of(),List.of(),new BigDecimal("20.00"));var ticket=mock(CommercialDocument.class);when(ticket.getNumero()).thenReturn("T-REPLACEMENT");when(docs.loadTicketPrintView(ticketId)).thenReturn(print);when(docs.loadForPrint(ticketId)).thenReturn(ticket);
  var replacement=new Voucher(storeId,"V-REMAINDER",new BigDecimal("80.00"),List.of("SOURCE","T-REPLACEMENT"),java.time.Instant.parse("2026-08-09T12:00:01Z"));when(vouchers.issuedFromTicket(ticket)).thenReturn(Optional.of(replacement));var voucherPrint=mock(VoucherPrintService.class);var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments());service.setVoucherService(vouchers);service.setVoucherPrintService(voucherPrint);

  var result=service.finalizeSession(sessionId,auth);

 assertThat(result.issuedVoucher()).isEqualTo(new SalePaymentSessionService.IssuedVoucher("V-REMAINDER",new BigDecimal("80.00"),java.time.Instant.parse("2026-08-09T12:00:01Z"),"T-REPLACEMENT"));
 verify(vouchers).issuedFromTicket(ticket);
 verifyNoInteractions(voucherPrint);
 }

 @Test void rendersTheTicketOnlyAfterTheFinalizationTransactionCompletes(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var ticketId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("20.00"));session.addAllocation(UUID.randomUUID(),"cash",SalePaymentAllocationKind.CASH,new BigDecimal("20.00"),null,null).approve(null,null,null);session.finalizeWith(ticketId,"T-COMMITTED");when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));
  var ticket=mock(CommercialDocument.class);when(ticket.getNumero()).thenReturn("T-COMMITTED");when(docs.loadForPrint(ticketId)).thenReturn(ticket);var raw=new TicketPrintView(ticketId,"T-COMMITTED",java.time.Instant.parse("2026-08-15T12:00:00Z"),List.of(),List.of(),new BigDecimal("20.00"));var rendered=raw.withRenderedDocument("pdf".getBytes(),"png".getBytes());when(docs.loadTicketPrintView(ticketId)).thenReturn(raw);
  var committed=new java.util.concurrent.atomic.AtomicBoolean();org.springframework.transaction.support.TransactionOperations transactions=new org.springframework.transaction.support.TransactionOperations(){public <T>T execute(org.springframework.transaction.support.TransactionCallback<T> action){T result=action.doInTransaction(null);committed.set(true);return result;}};when(docs.renderTicketPrintView(ticket,raw)).thenAnswer(invocation->{assertThat(committed.get()).isTrue();return rendered;});
  var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments(),null,transactions,null,null);

  var result=service.finalizeSession(sessionId,auth);

  assertThat(result.printTicket()).isSameAs(rendered);verify(docs).loadTicketPrintView(ticketId);verify(docs).renderTicketPrintView(ticket,raw);
 }

 @Test void voucherJasperFailureDoesNotBlockAnAlreadyFinalizedSale(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var vouchers=mock(VoucherService.class);var voucherPrint=mock(VoucherPrintService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var ticketId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("20.00"));session.addAllocation(UUID.randomUUID(),"voucher",SalePaymentAllocationKind.VOUCHER,new BigDecimal("20.00"),null,null).approve(null,null,null);session.finalizeWith(ticketId,"T-REPLACEMENT");when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));
  var print=new TicketPrintView(ticketId,"T-REPLACEMENT",java.time.Instant.parse("2026-08-09T12:00:00Z"),List.of(),List.of(),new BigDecimal("20.00"));var ticket=mock(CommercialDocument.class);when(ticket.getNumero()).thenReturn("T-REPLACEMENT");when(docs.loadTicketPrintView(ticketId)).thenReturn(print);when(docs.loadForPrint(ticketId)).thenReturn(ticket);when(docs.renderTicketPrintView(ticket,print)).thenReturn(print);
  var replacement=new Voucher(storeId,"V-REMAINDER",new BigDecimal("80.00"),List.of("SOURCE","T-REPLACEMENT"),java.time.Instant.parse("2026-08-09T12:00:01Z"));replacement.capturePrintSnapshot("{}");when(vouchers.issuedFromTicket(ticket)).thenReturn(Optional.of(replacement));when(voucherPrint.render(replacement)).thenThrow(new IllegalStateException("jasper_render_failed"));
  var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments());service.setVoucherService(vouchers);service.setVoucherPrintService(voucherPrint);

  var result=service.finalizeSession(sessionId,auth);

  assertThat(result.session().getTicketId()).isEqualTo(ticketId);
  assertThat(result.issuedVoucher()).isEqualTo(new SalePaymentSessionService.IssuedVoucher("V-REMAINDER",new BigDecimal("80.00"),java.time.Instant.parse("2026-08-09T12:00:01Z"),"T-REPLACEMENT"));
  verify(voucherPrint).render(replacement);
 }

 @Test void discardsSimulationOnlyFromPersistedTestConfiguration(){
  var f=discardFixture();var config=new CardTerminalConfiguration(f.terminalId,f.storeId,PaymentCardMode.INTEGRATED,PaymentTerminalProvider.PAYTEF,true,true,"sim","ref",1,"hash",Map.of());when(f.configs.required(f.terminalId)).thenReturn(config);
  var result=f.service.discardSimulation(f.sessionId,"payment_method_change",f.auth);
  assertThat(result.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);assertThat(result.getAllocations()).hasSize(1);verify(f.repo).save(f.session);verify(f.configs).required(f.terminalId);verify(f.sales).releaseTemporaryPriceAuthorizations("PAYMENT_SESSION",f.sessionId);
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
  var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments());

  assertThatThrownBy(()->service.finalizeSession(sessionId,auth)).hasMessage("payment_session_not_finalizable");
  assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);assertThat(session.getTicketId()).isNull();
  verifyNoInteractions(docs,snapshots,methods,ops);verify(repo,never()).save(any());
 }

 private static DiscardFixture discardFixture(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",BigDecimal.TEN);var allocation=session.addAllocation(UUID.randomUUID(),"card",SalePaymentAllocationKind.INTEGRATED_CARD,BigDecimal.TEN,"PAYTEF","INTEGRATED");allocation.result(PaymentTerminalOperationStatus.TIMEOUT,allocation.getId(),null,null,"uncertain");when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments());return new DiscardFixture(repo,sales,org,terminal,configs,auth,session,service,storeId,terminalId,sessionId);
 }

 private record DiscardFixture(SalePaymentSessionRepository repo,PosCashService sales,CurrentOrganization org,CurrentTerminal terminal,CardTerminalConfigurationReader configs,Authentication auth,SalePaymentSession session,SalePaymentSessionService service,UUID storeId,UUID terminalId,UUID sessionId){}

 @Test void compensationAcknowledgementUsesDurableOperationTruthAfterTimeout(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
   var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var operationId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));var allocation=session.addAllocation(operationId,"card",SalePaymentAllocationKind.INTEGRATED_CARD,BigDecimal.TEN,"PAYTEF","INTEGRATED");allocation.result(PaymentTerminalOperationStatus.TIMEOUT,operationId,null,null,"incierto");session.cancel();when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var durable=mock(PaymentTerminalOperation.class);when(ops.find(operationId)).thenReturn(Optional.of(durable));var security=security(user,auth);var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments(),security.operationSecurity(),security.audit());
  when(durable.getStatus()).thenReturn(PaymentTerminalOperationStatus.APPROVED);when(ops.recover(eq(operationId),any(UUID.class))).thenReturn(durable);
  service.query(sessionId,operationId,auth);
  assertThat(allocation.getStatus()).isEqualTo(PaymentTerminalOperationStatus.TIMEOUT);
   assertThatThrownBy(()->service.acknowledgeCompensation(sessionId,"revisado",null,null,auth)).hasMessage("integrated_compensation_unresolved");
  assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.COMPENSATION_REQUIRED);
  when(durable.getStatus()).thenReturn(PaymentTerminalOperationStatus.CANCELLED);
   service.acknowledgeCompensation(sessionId,"anulado en terminal",null,"1234",auth);
   assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);
   verify(security.operationSecurity()).authorize(SaleOperationCode.PAYMENT_COMPENSATION_ACK,null,"1234",auth);verify(security.audit()).record(eq("PAYMENT_COMPENSATION_ACKNOWLEDGED"),any(),argThat(details->!details.containsKey("authorizerPassword")));
 }

 @Test void refundedDurableOperationAlsoAllowsCompensationAcknowledgement(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
   var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var operationId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",BigDecimal.TEN);var allocation=session.addAllocation(operationId,"card",SalePaymentAllocationKind.INTEGRATED_CARD,BigDecimal.TEN,"PAYTEF","INTEGRATED");allocation.result(PaymentTerminalOperationStatus.TIMEOUT,operationId,null,null,"incierto");session.cancel();when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var durable=mock(PaymentTerminalOperation.class);when(durable.getStatus()).thenReturn(PaymentTerminalOperationStatus.REFUNDED);when(ops.find(operationId)).thenReturn(Optional.of(durable));var security=security(user,auth);var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments(),security.operationSecurity(),security.audit());
   service.acknowledgeCompensation(sessionId,"reembolsado",null,"1234",auth);
  assertThat(session.getStatus()).isEqualTo(SalePaymentSessionStatus.CANCELLED);
 }

 @Test void manualReplayMustMatchReference(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var security=security(user,auth);var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments(),security.operationSecurity(),security.audit());
  service.add(sessionId,UUID.randomUUID(),"manual",SalePaymentAllocationKind.MANUAL_CARD,new BigDecimal("10.00"),null,null,"REF-1",null,null,null,new OperationAuthorizationRequest("ENCARGADO","secret"),auth);
  assertThatThrownBy(()->service.add(sessionId,UUID.randomUUID(),"manual",SalePaymentAllocationKind.MANUAL_CARD,new BigDecimal("10.00"),null,"REF-2",auth)).hasMessage("allocation_idempotency_conflict");
  verify(security.operationSecurity(),times(1)).authorize(SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT,"ENCARGADO","secret",auth);
  verify(security.audit(),times(1)).record(eq("SALE_STANDARD_PAYMENT_AUTHORIZED"),any(),argThat(details->details.get("operationCode").equals("CONFIRM_MANUAL_CARD_PAYMENT")&&!details.containsKey("authorizerPassword")));
 }

 @Test void transferIsAuthorizedAtTheLockedMutationBoundaryAndIdempotentReplayDoesNotReauthorize(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var allocationId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var security=security(user,auth);var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments(),security.operationSecurity(),security.audit());

  service.add(sessionId,allocationId,"transfer",SalePaymentAllocationKind.TRANSFER,new BigDecimal("10.00"),null,null,"TR-1",null,null,null,new OperationAuthorizationRequest("ENCARGADO","secret"),auth);
  service.add(sessionId,allocationId,"transfer",SalePaymentAllocationKind.TRANSFER,new BigDecimal("10.00"),null,null,"TR-1",null,null,null,null,auth);

  verify(security.operationSecurity(),times(1)).authorize(SaleOperationCode.CONFIRM_TRANSFER_PAYMENT,"ENCARGADO","secret",auth);
  verify(security.audit(),times(1)).record(eq("SALE_STANDARD_PAYMENT_AUTHORIZED"),any(),argThat(details->details.get("operationCode").equals("CONFIRM_TRANSFER_PAYMENT")&&!details.containsKey("authorizerPassword")));
  assertThat(session.getAllocations()).hasSize(1);
 }

 @Test void integratedAllocationRejectsDisabledWrongModeNoneProviderAndStoreRule(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var rules=mock(StorePaymentConfigurationRepository.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));when(repo.findState(sessionId)).thenReturn(Optional.of(session));var storeRules=mock(StorePaymentConfiguration.class);when(storeRules.isIntegratedCardEnabled()).thenReturn(true);when(storeRules.getAllowedPaymentTerminalProviders()).thenReturn("PAYTEF");when(rules.findByStoreId(storeId)).thenReturn(Optional.of(storeRules));var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments(),rules);
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
  var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments());
  service.add(sessionId,allocationId,"stable",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("10.00"),"PAYTEF",null,auth);
  service.add(sessionId,UUID.randomUUID(),"stable",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("10.00"),"PAYTEF",null,auth);
   verify(ops,times(1)).charge(eq(allocationId),anyString(),eq(new BigDecimal("10.00")),eq(config));assertThat(session.getAllocations()).singleElement().satisfies(a->assertThat(a.getStatus()).isEqualTo(PaymentTerminalOperationStatus.APPROVED));session.cancel();var unresolved=mock(PaymentTerminalOperation.class);when(unresolved.getStatus()).thenReturn(PaymentTerminalOperationStatus.APPROVED);when(ops.find(allocationId)).thenReturn(Optional.of(unresolved));assertThatThrownBy(()->service.acknowledgeCompensation(sessionId,"resuelto",null,null,auth)).hasMessage("integrated_compensation_unresolved");
  var other=mock(UserAccount.class);when(other.getId()).thenReturn(UUID.randomUUID());when(auth.getPrincipal()).thenReturn(other);assertThatThrownBy(()->service.get(sessionId,auth)).isInstanceOf(NoSuchElementException.class);
 }
 @Test void failureAfterPendingCommitKeepsStableAllocationAndRetryDoesNotCreateAnother(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var allocationId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var config=new CardTerminalConfiguration(terminalId,storeId,PaymentCardMode.INTEGRATED,PaymentTerminalProvider.PAYTEF,true,true,"PAYTEF","ref",1,"cfg",Map.of());when(configs.required(terminalId)).thenThrow(new IllegalStateException("offline")).thenReturn(config);when(ops.charge(eq(allocationId),anyString(),eq(new BigDecimal("10.00")),eq(config))).thenReturn(new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED,"OK","ref","auth","Aprobado"));var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments());
  when(repo.findState(sessionId)).thenReturn(Optional.of(session));assertThatThrownBy(()->service.add(sessionId,allocationId,"stable",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("10.00"),"PAYTEF",null,auth)).hasMessage("offline");assertThat(session.getAllocations()).isEmpty();service.add(sessionId,allocationId,"stable",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("10.00"),"PAYTEF",null,auth);assertThat(session.getAllocations()).singleElement().satisfies(a->{assertThat(a.getId()).isEqualTo(allocationId);assertThat(a.getOperationId()).isEqualTo(allocationId);});verify(ops,times(1)).charge(eq(allocationId),anyString(),any(),eq(config));
 }
 @Test void finalizeRejectsAnIntegratedAllocationWhoseChargeWasAdjusted(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var allocationId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));session.addAllocation(allocationId,"card",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("10.00"),"PAYTEF","INTEGRATED").approve(allocationId,"ref","auth");when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));doThrow(new IllegalStateException("payment_operation_not_finalizable")).when(ops).requireFinalizableApprovedCharge(allocationId);
  var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments());
  assertThatThrownBy(()->service.finalizeSession(sessionId,auth)).hasMessage("payment_operation_not_finalizable");
  verify(docs,never()).createApprovedCardTicketFromSnapshot(any(),any(),eq(auth));
 }

 @Test void pendingFinalizationAuthorizesLockedCreditAtTheMutationBoundaryAndReplaysWithoutReauthentication(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var pendingSales=mock(CustomerPendingSaleService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var customerId=UUID.randomUUID();var sessionId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));session.addAllocation(UUID.randomUUID(),"pending",SalePaymentAllocationKind.PENDING,new BigDecimal("10.00"),null,"PENDING").approve(null,null,null);when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(invocation->invocation.getArgument(0));
  var snapshot=new ApprovedCardTicketSnapshot(storeId,UUID.randomUUID(),java.time.LocalDate.of(2026,7,30),customerId,UUID.randomUUID(),BigDecimal.ZERO,BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.TEN,List.of());when(snapshots.deserialize("{}")).thenReturn(snapshot);
  var credit=mock(CustomerPendingSaleService.CreditAssessment.class);var operatorAuthorization=new Authorization(user,user,false);var decision=new CustomerPendingSaleService.PendingCreditAuthorization(credit,operatorAuthorization,operatorAuthorization);when(pendingSales.authorizePendingTicket(eq(customerId),eq(snapshot.date()),eq(new BigDecimal("10.00")),eq("excepcion"),eq("ENCARGADO"),eq("secret"),isNull(),isNull(),eq(auth))).thenReturn(decision);
  var ticket=mock(CommercialDocument.class);var ticketId=UUID.randomUUID();when(ticket.getId()).thenReturn(ticketId);when(ticket.getNumero()).thenReturn("T-1");when(ticket.getEstado()).thenReturn(DocumentStatus.PENDIENTE);when(ticket.getConfirmadoEn()).thenReturn(java.time.Instant.parse("2026-07-30T10:00:00Z"));when(ticket.getLineas()).thenReturn(List.of());when(ticket.getPagos()).thenReturn(List.of());when(ticket.getTotal()).thenReturn(BigDecimal.TEN);when(ticket.getBaseTotal()).thenReturn(BigDecimal.TEN);when(ticket.getImpuestoTotal()).thenReturn(BigDecimal.ZERO);when(docs.createPendingTicketFromSnapshot(eq(snapshot),eq(List.of()),eq(auth))).thenReturn(ticket);when(docs.loadForPrint(ticketId)).thenReturn(ticket);
  var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments());service.setCustomerPendingSaleService(pendingSales);

  service.finalizeSession(sessionId," excepcion "," ENCARGADO ","secret",auth);
  service.finalizeSession(sessionId,null,null,null,auth);

  verify(pendingSales,times(1)).authorizePendingTicket(customerId,snapshot.date(),new BigDecimal("10.00"),"excepcion","ENCARGADO","secret",null,null,auth);
  verify(pendingSales,times(1)).recordPendingTicketAuthorization(sessionId,ticket,customerId,"excepcion",decision);
  verify(docs,times(1)).createPendingTicketFromSnapshot(snapshot,List.of(),auth);
  verify(sales,times(1)).completeTemporaryPriceAuthorizations("PAYMENT_SESSION",sessionId);
 }

 @Test void voucherAllocationChecksBalanceAndCannotReuseTheSameCodeInOneSession(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var vouchers=mock(VoucherService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));when(vouchers.availableBalance("V-100")).thenReturn(new BigDecimal("20.00"));var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments());service.setVoucherService(vouchers);

  service.add(sessionId,UUID.randomUUID(),"voucher-1",SalePaymentAllocationKind.VOUCHER,new BigDecimal("5.00"),null,"V-100",auth);

  assertThat(session.getAllocations()).singleElement().satisfies(allocation->{assertThat(allocation.getKind()).isEqualTo(SalePaymentAllocationKind.VOUCHER);assertThat(allocation.getVoucherCode()).isEqualTo("V-100");assertThat(allocation.getReference()).isNull();assertThat(allocation.getStatus()).isEqualTo(PaymentTerminalOperationStatus.APPROVED);});
  assertThatThrownBy(()->service.add(sessionId,UUID.randomUUID(),"voucher-2",SalePaymentAllocationKind.VOUCHER,new BigDecimal("5.00"),null,"V-100",auth)).hasMessage("voucher_already_allocated");
  verify(vouchers,times(2)).availableBalance("V-100");
 }

 @Test void voucherKeepsItsCodeWithoutAcceptingAnExternalReferenceRequirement(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var vouchers=mock(VoucherService.class);var auth=mock(Authentication.class);
  var companyId=UUID.randomUUID();var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var company=mock(Company.class);var store=mock(Store.class);var user=mock(UserAccount.class);when(company.getId()).thenReturn(companyId);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentCompany()).thenReturn(company);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var method=new PaymentMethod(companyId,"VALE",true,true,false);when(methods.findByEmpresaIdAndNombreAndActivoTrue(companyId,"VALE")).thenReturn(Optional.of(method));var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));when(repo.save(any())).thenAnswer(i->i.getArgument(0));when(vouchers.availableBalance("V-200")).thenReturn(new BigDecimal("20.00"));var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments());service.setVoucherService(vouchers);

  service.add(sessionId,UUID.randomUUID(),"voucher-without-reference",SalePaymentAllocationKind.VOUCHER,new BigDecimal("5.00"),null,"V-200",null,null,null,null,auth);

  assertThat(session.getAllocations()).singleElement().satisfies(allocation->{assertThat(allocation.getVoucherCode()).isEqualTo("V-200");assertThat(allocation.getReference()).isNull();});
 }

 @Test void cashAllocationRequiresAnOpenCashSessionBeforeItIsAccepted(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var cash=mock(CashPaymentRecorder.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",new BigDecimal("10.00"));when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));doThrow(new IllegalStateException("No hay una sesion de caja abierta")).when(cash).requireOpenSession(terminalId);var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cash);

  assertThatThrownBy(()->service.add(sessionId,UUID.randomUUID(),"cash",SalePaymentAllocationKind.CASH,new BigDecimal("10.00"),null,null,auth)).hasMessage("No hay una sesion de caja abierta");

  assertThat(session.getAllocations()).isEmpty();verify(cash).requireOpenSession(terminalId);verify(repo,never()).save(any());
 }

 @Test void blocksAnotherAllocationWhileIntegratedResultIsUncertain(){
  var repo=mock(SalePaymentSessionRepository.class);var sales=mock(PosCashService.class);var docs=mock(DocumentService.class);var snapshots=mock(PosCardDocumentSnapshot.class);var methods=mock(PaymentMethodRepository.class);var org=mock(CurrentOrganization.class);var terminal=mock(CurrentTerminal.class);var configs=mock(CardTerminalConfigurationReader.class);var ops=mock(PaymentTerminalOperationService.class);var auth=mock(Authentication.class);
  var storeId=UUID.randomUUID();var terminalId=UUID.randomUUID();var userId=UUID.randomUUID();var sessionId=UUID.randomUUID();var operationId=UUID.randomUUID();var store=mock(Store.class);var user=mock(UserAccount.class);when(user.getId()).thenReturn(userId);when(auth.getPrincipal()).thenReturn(user);when(store.getId()).thenReturn(storeId);when(org.currentStore()).thenReturn(store);when(terminal.terminalId(auth)).thenReturn(terminalId);
  var session=SalePaymentSession.reserve(sessionId,storeId,terminalId,userId,"hash","{}",BigDecimal.TEN);var allocation=session.addAllocation(operationId,"card",SalePaymentAllocationKind.INTEGRATED_CARD,new BigDecimal("5.00"),"PAYTEF","INTEGRATED");allocation.result(PaymentTerminalOperationStatus.TIMEOUT,operationId,null,null,"resultado incierto");when(repo.findLocked(sessionId)).thenReturn(Optional.of(session));var durable=mock(PaymentTerminalOperation.class);when(durable.getStatus()).thenReturn(PaymentTerminalOperationStatus.TIMEOUT);when(ops.find(operationId)).thenReturn(Optional.of(durable));var service=new SalePaymentSessionService(repo,sales,docs,snapshots,methods,org,terminal,configs,ops,cashPayments());

  assertThatThrownBy(()->service.add(sessionId,UUID.randomUUID(),"cash",SalePaymentAllocationKind.CASH,new BigDecimal("5.00"),null,null,auth)).hasMessage("integrated_payment_result_uncertain");
  assertThat(session.getAllocations()).hasSize(1);verify(repo,never()).save(any());
 }
}
