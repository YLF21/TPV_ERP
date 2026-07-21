package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.tpverp.backend.organization.*;
import com.tpverp.backend.terminal.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class PosCardServiceTest {
 @Mock PosCashService sales; @Mock DocumentService documents; @Mock PaymentMethodRepository methods;
 @Mock PosCardCheckoutCoordinator coordinator; @Mock PosCardTicketCreator ticketCreator; @Mock PosCardDocumentSnapshot snapshots; @Mock CardTerminalConfigurationReader configurations;
 @Mock CardTerminalGateway gateway; @Mock CurrentTerminal currentTerminal; @Mock CurrentOrganization organization;
 @Mock PaymentTerminalOperationService terminalOperations;
 @Mock Authentication authentication;
 UUID terminal=UUID.randomUUID(),checkoutId=UUID.randomUUID(); DocumentCommand command=mock(DocumentCommand.class);
 PosCashController.SaleRequest sale=new PosCashController.SaleRequest(null,List.of(new PosCashController.LineRequest(UUID.randomUUID(),BigDecimal.ONE,BigDecimal.ZERO)));

 @BeforeEach void base(){lenient().when(authentication.getName()).thenReturn("ADMIN");var user=mock(com.tpverp.backend.security.domain.UserAccount.class);lenient().when(user.getId()).thenReturn(UUID.randomUUID());lenient().when(user.getUserName()).thenReturn("ADMIN");lenient().when(authentication.getPrincipal()).thenReturn(user);lenient().when(currentTerminal.terminalId(authentication)).thenReturn(terminal);lenient().when(sales.authoritativeCommand(sale,authentication)).thenReturn(command);
  var quote=mock(CommercialDocument.class);lenient().when(quote.getTotal()).thenReturn(new BigDecimal("12.10"));lenient().when(documents.quoteTicket(command,authentication)).thenReturn(quote);var company=mock(Company.class);lenient().when(company.getId()).thenReturn(UUID.randomUUID());lenient().when(organization.currentCompany()).thenReturn(company);var store=mock(Store.class);lenient().when(store.getId()).thenReturn(terminal);lenient().when(organization.currentStore()).thenReturn(store);var method=mock(PaymentMethod.class);lenient().when(method.getId()).thenReturn(UUID.randomUUID());lenient().when(methods.findByEmpresaIdAndNombreAndActivoTrue(any(),eq("TARJETA"))).thenReturn(Optional.of(method));}

 @Test void declinedNeverCreatesTicket(){var c=config();var pending=pending(UUID.randomUUID());
  when(snapshots.serialize(any(ApprovedCardTicketSnapshot.class))).thenReturn("snapshot");when(coordinator.reserve(eq(checkoutId),eq(terminal),anyString(),eq("snapshot"),eq(new BigDecimal("12.10")),any(),any(PosCardCheckoutCoordinator.RecoveryIdentity.class))).thenReturn(new PosCardCheckoutCoordinator.Reservation(pending,true));
  when(gateway.charge(any(CardTerminalRequest.class),eq(c))).thenReturn(new CardTerminalResult(PaymentTerminalOperationStatus.DECLINED,"R",null,"no"));
  var declined=pending(UUID.randomUUID());declined.recordGatewayResult(declined.getGatewayOwner(),new CardTerminalResult(PaymentTerminalOperationStatus.DECLINED,"R",null,"no"),Instant.now());
  when(coordinator.recordResult(eq(checkoutId),any(),any())).thenReturn(declined);
  assertThat(service().charge(request(),authentication).status()).isEqualTo(PaymentTerminalOperationStatus.DECLINED);
  verify(documents,never()).createTicket(any(),any(),any());}

 @Test void approvedCreatesIntegratedTicketAndLinksOnce(){var c=config();UUID owner=UUID.randomUUID();var pending=pending(owner);
  when(snapshots.serialize(any(ApprovedCardTicketSnapshot.class))).thenReturn("snapshot");when(coordinator.reserve(eq(checkoutId),eq(terminal),anyString(),eq("snapshot"),any(),any(),any(PosCardCheckoutCoordinator.RecoveryIdentity.class))).thenReturn(new PosCardCheckoutCoordinator.Reservation(pending,true));
  when(gateway.charge(any(CardTerminalRequest.class),eq(c))).thenReturn(new CardTerminalResult(PaymentTerminalOperationStatus.APPROVED,"REF","AUTH","ok"));
  var approved=pending;approved.recordGatewayResult(owner,new CardTerminalResult(PaymentTerminalOperationStatus.APPROVED,"REF","AUTH","ok"),Instant.now());
  when(coordinator.recordResult(eq(checkoutId),any(),any())).thenReturn(approved);when(coordinator.claimTicket(eq(checkoutId),any())).thenReturn(true);
  var ticket=mock(CommercialDocument.class);when(ticket.getId()).thenReturn(UUID.randomUUID());
  var linked=pending(UUID.randomUUID());linked.recordGatewayResult(linked.getGatewayOwner(),new CardTerminalResult(PaymentTerminalOperationStatus.APPROVED,"REF","AUTH","ok"),Instant.now());linked.linkDocument(ticket.getId(),"T-1",Instant.now());
  when(ticketCreator.create(eq(checkoutId),any(ApprovedCardTicketSnapshot.class),eq(authentication),eq(terminal))).thenReturn(linked);
  assertThat(service().charge(request(),authentication).ticketId()).isEqualTo(ticket.getId());
  verify(ticketCreator).create(eq(checkoutId),any(ApprovedCardTicketSnapshot.class),eq(authentication),eq(terminal));}

 @Test void concurrentLoserReturnsPendingWithoutGateway(){config();var pending=pending(UUID.randomUUID());
  when(snapshots.serialize(any(ApprovedCardTicketSnapshot.class))).thenReturn("snapshot");when(coordinator.reserve(eq(checkoutId),eq(terminal),anyString(),eq("snapshot"),any(),any(),any(PosCardCheckoutCoordinator.RecoveryIdentity.class))).thenReturn(new PosCardCheckoutCoordinator.Reservation(pending,false));
  assertThat(service().charge(request(),authentication).status()).isEqualTo(PaymentTerminalOperationStatus.PENDING);verify(gateway,never()).charge(any(CardTerminalRequest.class),any(CardTerminalConfiguration.class));}

 @Test void approvedWithoutDocumentAfterTicketFailureRetriesFromSnapshotWithoutGatewayOrCatalog(){UUID owner=UUID.randomUUID();var approved=pending(owner);approved.recordGatewayResult(owner,new CardTerminalResult(PaymentTerminalOperationStatus.APPROVED,"R","A","ok"),Instant.now());
  var frozen=mock(ApprovedCardTicketSnapshot.class);when(coordinator.existing(eq(checkoutId),eq(terminal),anyString())).thenReturn(Optional.of(approved));when(snapshots.deserialize(approved.getDocumentSnapshot())).thenReturn(frozen);when(coordinator.claimTicket(eq(checkoutId),any())).thenReturn(true);
  var linked=pending(UUID.randomUUID());linked.recordGatewayResult(linked.getGatewayOwner(),new CardTerminalResult(PaymentTerminalOperationStatus.APPROVED,"R","A","ok"),Instant.now());linked.linkDocument(UUID.randomUUID(),"T-REC",Instant.now());when(ticketCreator.create(eq(checkoutId),eq(frozen),eq(authentication),eq(terminal))).thenReturn(linked);
  assertThat(service().charge(request(),authentication).ticketNumber()).isEqualTo("T-REC");verify(gateway,never()).charge(any(CardTerminalRequest.class),any(CardTerminalConfiguration.class));verify(sales,never()).authoritativeCommand(any());verify(documents,never()).quoteTicket(any(),any());verify(configurations,never()).required(any());}

 @Test void completedReplayIgnoresMissingConfigGatewayAndChangedCatalog(){var declined=pending(UUID.randomUUID());declined.recordGatewayResult(declined.getGatewayOwner(),new CardTerminalResult(PaymentTerminalOperationStatus.DECLINED,"REF",null,"stored"),Instant.now());when(coordinator.existing(eq(checkoutId),eq(terminal),anyString())).thenReturn(Optional.of(declined));
  var result=service().charge(request(),authentication);assertThat(result.status()).isEqualTo(PaymentTerminalOperationStatus.DECLINED);assertThat(result.message()).isEqualTo("stored");verify(sales,never()).authoritativeCommand(any());verify(documents,never()).quoteTicket(any(),any());verify(configurations,never()).required(any());verify(gateway,never()).charge(any(CardTerminalRequest.class),any(CardTerminalConfiguration.class));}

 @Test void corruptApprovedSnapshotReturnsSafeErrorWithoutGateway(){var approved=pending(UUID.randomUUID());approved.recordGatewayResult(approved.getGatewayOwner(),new CardTerminalResult(PaymentTerminalOperationStatus.APPROVED,"R","A","ok"),Instant.now());when(coordinator.existing(eq(checkoutId),eq(terminal),anyString())).thenReturn(Optional.of(approved));when(snapshots.deserialize(anyString())).thenThrow(new ApprovedCardSnapshotException("Instantanea corrupta"));
  assertThat(service().charge(request(),authentication).status()).isEqualTo(PaymentTerminalOperationStatus.ERROR);verify(coordinator).diagnostic(eq(checkoutId),contains("revision manual"));verify(gateway,never()).charge(any(CardTerminalRequest.class),any(CardTerminalConfiguration.class));verify(ticketCreator,never()).create(any(),any(),any(),any());}

 @Test void hashConflictStopsBeforeCatalogAndGateway(){when(coordinator.existing(eq(checkoutId),eq(terminal),anyString())).thenThrow(new IllegalStateException("El checkout ya pertenece a otra venta"));assertThatThrownBy(()->service().charge(request(),authentication)).hasMessageContaining("otra venta");verify(sales,never()).authoritativeCommand(any());verify(gateway,never()).charge(any(CardTerminalRequest.class),any(CardTerminalConfiguration.class));}

 @Test void unknownGatewayFailureIsPersistedAsUncertainTimeoutAndReplayNeverChargesAgain(){var c=config();var pending=pending(UUID.randomUUID());when(snapshots.serialize(any())).thenReturn("snapshot");when(coordinator.reserve(eq(checkoutId),eq(terminal),anyString(),eq("snapshot"),any(),any(),any(PosCardCheckoutCoordinator.RecoveryIdentity.class))).thenReturn(new PosCardCheckoutCoordinator.Reservation(pending,true));when(gateway.charge(any(CardTerminalRequest.class),eq(c))).thenThrow(new RuntimeException("socket closed after send"));var timeout=pending(UUID.randomUUID());timeout.recordGatewayResult(timeout.getGatewayOwner(),new CardTerminalResult(PaymentTerminalOperationStatus.TIMEOUT,null,null,"Resultado incierto del datafono; revise el terminal"),Instant.now());when(coordinator.recordResult(eq(checkoutId),any(),argThat(r->r.status()==PaymentTerminalOperationStatus.TIMEOUT))).thenReturn(timeout);
  assertThat(service().charge(request(),authentication).status()).isEqualTo(PaymentTerminalOperationStatus.TIMEOUT);when(coordinator.existing(eq(checkoutId),eq(terminal),anyString())).thenReturn(Optional.of(timeout));assertThat(service().charge(request(),authentication).status()).isEqualTo(PaymentTerminalOperationStatus.TIMEOUT);verify(gateway,times(1)).charge(any(CardTerminalRequest.class),any(CardTerminalConfiguration.class));}

 @Test void rejectsChangedAuthoritativeTotalBeforeReservation(){var quote=mock(CommercialDocument.class);when(quote.getTotal()).thenReturn(new BigDecimal("12.11"));when(documents.quoteTicket(command,authentication)).thenReturn(quote);
  assertThatThrownBy(()->service().charge(request(),authentication)).isInstanceOf(IllegalStateException.class).hasMessageContaining("cambiado");verify(coordinator,never()).reserve(any(),any(),any(),any(),any(),any());}

 @Test void rejectsMissingConfigurationBeforeExternalCharge(){when(configurations.required(terminal)).thenThrow(new IllegalStateException("El datafono no esta configurado"));
  assertThatThrownBy(()->service().charge(request(),authentication)).isInstanceOf(IllegalStateException.class).hasMessageContaining("no esta configurado");verify(gateway,never()).charge(any(CardTerminalRequest.class),any(CardTerminalConfiguration.class));}

 @Test void quoteDelegatesToAuthoritativeCashQuote(){var expected=new PosCashService.Quote(new BigDecimal("12.10"));when(sales.quote(sale,authentication)).thenReturn(expected);assertThat(service().quote(sale,authentication)).isSameAs(expected);}

 @Test void newOrchestratorUsesLedgerBeforeTicketAndRoutesConfiguredProvider(){var c=new CardTerminalConfiguration(terminal,PaymentCardMode.INTEGRATED,PaymentTerminalProvider.PAYTEF,true,true,"PAYTEF",Map.of("simulatorOutcome","APPROVED"));
  when(configurations.required(terminal)).thenReturn(c);when(gateway.supports(PaymentTerminalProvider.PAYTEF,true)).thenReturn(true);var owner=UUID.randomUUID();var pending=pending(owner);
  when(snapshots.serialize(any())).thenReturn("snapshot");when(coordinator.reserve(eq(checkoutId),eq(terminal),anyString(),eq("snapshot"),any(),any(),any(PosCardCheckoutCoordinator.RecoveryIdentity.class))).thenReturn(new PosCardCheckoutCoordinator.Reservation(pending,true));
  when(terminalOperations.charge(eq(checkoutId),anyString(),eq(new BigDecimal("12.10")),eq(c))).thenReturn(new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED,"APPROVED","REF","AUTH","ok"));
  pending.recordGatewayResult(owner,new CardTerminalResult(PaymentTerminalOperationStatus.APPROVED,"REF","AUTH","ok"),Instant.now());when(coordinator.recordResult(eq(checkoutId),any(),any())).thenReturn(pending);when(coordinator.claimTicket(eq(checkoutId),any())).thenReturn(true);
  var linked=pending(UUID.randomUUID());linked.recordGatewayResult(linked.getGatewayOwner(),new CardTerminalResult(PaymentTerminalOperationStatus.APPROVED,"REF","AUTH","ok"),Instant.now());linked.linkDocument(UUID.randomUUID(),"T-PAYTEF",Instant.now());when(ticketCreator.create(any(),any(),any(),any())).thenReturn(linked);
  var value=new PosCardService(sales,documents,coordinator,ticketCreator,snapshots,configurations,List.of(gateway),terminalOperations,currentTerminal,methods,organization).charge(request(),authentication);
  assertThat(value.ticketNumber()).isEqualTo("T-PAYTEF");var order=inOrder(terminalOperations,coordinator,ticketCreator);order.verify(terminalOperations).charge(eq(checkoutId),anyString(),any(),eq(c));order.verify(coordinator).recordResult(eq(checkoutId),any(),any());order.verify(ticketCreator).create(eq(checkoutId),any(),eq(authentication),eq(terminal));
 }

 private CardTerminalConfiguration config(){var c=new CardTerminalConfiguration(terminal,PaymentCardMode.INTEGRATED,PaymentTerminalProvider.REDSYS_TPV_PC,true,true,"Redsys",Map.of("simulatorOutcome","APPROVED"));when(configurations.required(terminal)).thenReturn(c);when(gateway.supports(PaymentTerminalProvider.REDSYS_TPV_PC,true)).thenReturn(true);return c;}
 private PosCardCheckout pending(UUID owner){var now=Instant.now();return PosCardCheckout.reserve(checkoutId,terminal,"a".repeat(64),"snapshot",new BigDecimal("12.10"),owner,now,now.plusSeconds(30));}
 private PosCardController.CardRequest request(){return new PosCardController.CardRequest(checkoutId,sale,new BigDecimal("12.10"));}
 private PosCardService service(){return new PosCardService(sales,documents,coordinator,ticketCreator,snapshots,configurations,List.of(gateway),currentTerminal,methods,organization);}
}
