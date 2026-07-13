package com.tpverp.backend.terminal;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.tpverp.backend.document.*;
import com.tpverp.backend.organization.*;
import com.tpverp.backend.security.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;

class PaymentTerminalRecoveryWorkerTest {
 @Test void durableApprovedQueueResumesFrozenTicketWithExactUserId(){var fixture=new Fixture();fixture.validIdentity();fixture.worker().recover();
  var authentication=ArgumentCaptor.forClass(Authentication.class);verify(fixture.tickets).create(eq(fixture.id),eq(fixture.frozen),authentication.capture(),eq(fixture.terminal));
  assertThat(authentication.getValue().getPrincipal()).isSameAs(fixture.user);assertThat(authentication.getValue().getAuthorities()).extracting("authority").contains("ROLE_SYSTEM");
  verify(fixture.users).findById(fixture.userId);verify(fixture.users,never()).findByTiendaIdAndNombre(any(),any());}

 @Test void ticketCreationFailureIsDurablyRetriedAndSecondWorkerRunSucceeds(){var fixture=new Fixture();fixture.validIdentity();
  doThrow(new IllegalStateException("db unavailable")).doReturn(null).when(fixture.tickets).create(any(),any(),any(),any());
  fixture.worker().recover();verify(fixture.operations).documentFailure(eq(fixture.id),anyString());
  fixture.worker().recover();verify(fixture.tickets,times(2)).create(eq(fixture.id),eq(fixture.frozen),any(),eq(fixture.terminal));
  verifyNoInteractions(fixture.gateway);}

 @Test void inactiveOrStoreMismatchRequiresReviewWithoutCreatingTicket(){var fixture=new Fixture();fixture.validIdentity();when(fixture.user.isActivo()).thenReturn(false);
  fixture.worker().recover();verify(fixture.operations).documentReview(eq(fixture.id),contains("inactivo"));verifyNoInteractions(fixture.tickets);}

 @Test void checkoutAbsentIsNeverSilentAndSchedulesDurableBackoff(){var fixture=new Fixture();when(fixture.checkouts.findById(fixture.id)).thenReturn(Optional.empty());
  fixture.worker().recover();verify(fixture.operations).documentFailure(eq(fixture.id),contains("No se pudo"));verifyNoInteractions(fixture.tickets);}

 @Test void alreadyLinkedCheckoutReconcilesExactProviderOperationPayment(){var fixture=new Fixture();UUID documentId=UUID.randomUUID(),paymentId=UUID.randomUUID();when(fixture.checkout.getDocumentId()).thenReturn(documentId);
  var payment=mock(DocumentPayment.class);when(payment.getId()).thenReturn(paymentId);when(payment.getPaymentTerminalProvider()).thenReturn(PaymentTerminalProvider.PAYTEF);
  when(payment.getPaymentTerminalStatus()).thenReturn(PaymentTerminalOperationStatus.APPROVED);when(payment.getPaymentTerminalId()).thenReturn(fixture.terminal);
  when(payment.getReferencia()).thenReturn("REF");when(payment.getCardAuthorizationCode()).thenReturn("AUTH");when(payment.getImporte()).thenReturn(new BigDecimal("10.00"));
  when(fixture.documentPayments.findAllByDocumentoId(documentId)).thenReturn(List.of(payment));fixture.worker().recover();
  verify(fixture.operations).linkDocument(fixture.id,documentId,paymentId);verifyNoInteractions(fixture.tickets);}

 @Test void existingDocumentPaymentMismatchRequiresReview(){var fixture=new Fixture();UUID documentId=UUID.randomUUID();when(fixture.checkout.getDocumentId()).thenReturn(documentId);
  when(fixture.documentPayments.findAllByDocumentoId(documentId)).thenReturn(List.of());fixture.worker().recover();
  verify(fixture.operations).documentReview(eq(fixture.id),contains("verificable"));verify(fixture.operations,never()).linkDocument(any(),any(),any());}

 static class Fixture{
  final PaymentTerminalOperationService operations=mock(PaymentTerminalOperationService.class);final PosCardCheckoutRepository checkouts=mock(PosCardCheckoutRepository.class);
  final PosCardCheckoutCoordinator coordinator=mock(PosCardCheckoutCoordinator.class);final PosCardDocumentSnapshot snapshots=mock(PosCardDocumentSnapshot.class);
  final PosCardTicketCreator tickets=mock(PosCardTicketCreator.class);final UserAccountRepository users=mock(UserAccountRepository.class);final StoreRepository stores=mock(StoreRepository.class);
  final DocumentPaymentRepository documentPayments=mock(DocumentPaymentRepository.class);
  final CardTerminalGateway gateway=mock(CardTerminalGateway.class);final UUID id=UUID.randomUUID(),terminal=UUID.randomUUID(),storeId=UUID.randomUUID(),companyId=UUID.randomUUID(),userId=UUID.randomUUID();
  final PaymentTerminalOperation op;final PosCardCheckout checkout=mock(PosCardCheckout.class);final UserAccount user=mock(UserAccount.class);final Store store=mock(Store.class);final Company company=mock(Company.class);final ApprovedCardTicketSnapshot frozen=mock(ApprovedCardTicketSnapshot.class);
  Fixture(){op=PaymentTerminalOperation.reserve(id,terminal,storeId,PaymentTerminalProvider.PAYTEF,PaymentTerminalMode.SIMULATED,PaymentTerminalOperationType.CHARGE,null,id.toString(),"a".repeat(64),new BigDecimal("10.00"),"b".repeat(64),1,Instant.now());op.markSent("SEND",Instant.now());op.approveFromQuery("REF","AUTH",Instant.now());
   when(operations.approvedWithoutDocument(20)).thenReturn(List.of(op));when(operations.claimApprovedDocument(eq(id),any())).thenReturn(op);
   when(checkouts.findById(id)).thenReturn(Optional.of(checkout));when(checkout.getId()).thenReturn(id);when(checkout.getTerminalId()).thenReturn(terminal);when(checkout.getDocumentSnapshot()).thenReturn("snapshot");
   when(checkout.getRequestedUserId()).thenReturn(userId);when(checkout.getRequestedStoreId()).thenReturn(storeId);when(checkout.getRequestedCompanyId()).thenReturn(companyId);
   when(coordinator.recoverApproved(eq(id),eq("REF"),eq("AUTH"),anyString())).thenReturn(checkout);when(coordinator.claimTicket(eq(id),any())).thenReturn(true);when(snapshots.deserialize("snapshot")).thenReturn(frozen);
  }
  void validIdentity(){when(stores.findById(storeId)).thenReturn(Optional.of(store));when(store.getEmpresa()).thenReturn(company);when(company.getId()).thenReturn(companyId);
   when(users.findById(userId)).thenReturn(Optional.of(user));when(user.isActivo()).thenReturn(true);when(user.getTienda()).thenReturn(store);when(store.getId()).thenReturn(storeId);}
  PaymentTerminalRecoveryWorker worker(){return new PaymentTerminalRecoveryWorker(operations,checkouts,coordinator,snapshots,tickets,users,stores,documentPayments);}
 }
}
