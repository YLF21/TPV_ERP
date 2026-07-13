package com.tpverp.backend.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.tpverp.backend.organization.*;
import com.tpverp.backend.terminal.*;
import java.math.BigDecimal;import java.time.Instant;import java.util.*;
import org.junit.jupiter.api.Test;import org.springframework.security.core.Authentication;
class PosCardTicketCreatorTest {
 @Test void createsTicketWithIntegratedMetadataAndLinksInSameServiceCall(){
  var docs=mock(DocumentService.class);var repo=mock(PosCardCheckoutRepository.class);var auth=mock(Authentication.class);
  UUID id=UUID.randomUUID(),terminal=UUID.randomUUID(),owner=UUID.randomUUID();var now=Instant.now();var checkout=PosCardCheckout.reserve(id,terminal,"a".repeat(64),"snapshot",new BigDecimal("12.10"),owner,now,now.plusSeconds(30));checkout.recordGatewayResult(owner,new CardTerminalResult(PaymentTerminalOperationStatus.APPROVED,"REF","AUTH","ok"),now);
  var operations=mock(PaymentTerminalOperationService.class);var operation=mock(PaymentTerminalOperation.class);when(operation.getProvider()).thenReturn(PaymentTerminalProvider.PAYTEF);when(operations.find(id)).thenReturn(Optional.of(operation));
  when(repo.findById(id)).thenReturn(Optional.of(checkout));when(repo.save(any())).thenAnswer(i->i.getArgument(0));var ticket=mock(CommercialDocument.class);when(ticket.getId()).thenReturn(UUID.randomUUID());when(ticket.getNumero()).thenReturn("T-1");var documentPayment=mock(DocumentPayment.class);when(documentPayment.getId()).thenReturn(UUID.randomUUID());when(ticket.getPagos()).thenReturn(List.of(documentPayment));when(docs.createApprovedCardTicketFromSnapshot(any(),any(),eq(auth))).thenReturn(ticket);var frozen=mock(ApprovedCardTicketSnapshot.class);when(frozen.paymentMethodId()).thenReturn(UUID.randomUUID());
  new PosCardTicketCreator(docs,repo,operations).create(id,frozen,auth,terminal);
  var cap=org.mockito.ArgumentCaptor.forClass(List.class);verify(docs).createApprovedCardTicketFromSnapshot(any(),cap.capture(),eq(auth));var p=(PaymentCommand)cap.getValue().getFirst();assertThat(p.cardMode()).isEqualTo(PaymentCardMode.INTEGRATED);assertThat(p.paymentTerminalProvider()).isEqualTo(PaymentTerminalProvider.PAYTEF);assertThat(p.reference()).isEqualTo("REF");assertThat(checkout.getDocumentId()).isEqualTo(ticket.getId());verify(operations).linkDocument(id,ticket.getId(),documentPayment.getId());
 }
}
