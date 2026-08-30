package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLineCommand;
import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.sync.SyncOutboxEvent;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.party.loyalty.central.LocalMemberBalanceReservation;
import com.tpverp.backend.party.loyalty.central.LocalMemberBalanceReservationRepository;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCheckoutProtocolService;
import com.tpverp.backend.party.loyalty.central.MemberBalanceReservationCoordinator;
import com.tpverp.backend.party.loyalty.central.MemberReturnBalanceRetentionPlanner;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import static org.mockito.Mockito.lenient;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberLoyaltyServiceTest {

    @Mock MemberRepository members;
    @Mock MemberCategoryRepository categories;
    @Mock MemberSettingsRepository settingsRepository;
    @Mock MemberMovementRepository movements;
    @Mock MemberBalanceLotRepository lots;
    @Mock MemberBalanceLotConsumptionRepository lotConsumptions;
    @Mock MemberDocumentLoyaltySettlementRepository loyaltySettlements;
    @Mock MemberDocumentLoyaltyLineRepository loyaltyLines;
    @Mock MemberCardDeliveryRepository cardDeliveries;
    @Mock MemberSmtpSettingsRepository smtpSettings;
    @Mock CommercialContactChannelRepository channels;
    @Mock CommercialDocumentRepository documents;
    @Mock SyncOutboxService syncOutbox;
    @Mock SyncOutboxEvent queuedSyncEvent;
    @Mock LocalMemberBalanceReservationRepository localBalanceReservations;
    @Mock PartyContext context;

    @BeforeEach
    void stubOutboxQueueResult() {
        lenient().when(syncOutbox.enqueue(any())).thenReturn(queuedSyncEvent);
        lenient().when(queuedSyncEvent.getEventId()).thenReturn(UUID.randomUUID());
    }

    @Test
    void listsActiveAndInactiveMembersWithTheirOwnAndCustomerStatus() {
        var company = PartyTestData.company();
        var activeCustomer = new Customer(company, "Ana", DocumentType.NIF, "1",
                null, "600000001", "ana@example.com", null, CustomerRate.VENTA, BigDecimal.ZERO);
        activeCustomer.assignClientCode(UUID.randomUUID(), "C-001-000001");
        var inactiveCustomer = new Customer(company, "Luis", DocumentType.NIF, "2",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        inactiveCustomer.assignClientCode(UUID.randomUUID(), "C-001-000002");
        inactiveCustomer.deactivate();
        var activeMember = new Member(activeCustomer, "M-001-000001", LocalDate.of(2026, 7, 1));
        var inactiveMember = new Member(inactiveCustomer, "M-001-000002", LocalDate.of(2026, 7, 2));
        inactiveMember.deactivate();
        when(context.currentCompany()).thenReturn(company);
        when(members.findByCompanyIdOrderByCustomerFiscalNameAsc(company.getId()))
                .thenReturn(java.util.List.of(activeMember, inactiveMember));

        var result = service().list();

        assertThat(result).extracting(
                MemberLoyaltyService.MemberDirectoryView::memberId,
                MemberLoyaltyService.MemberDirectoryView::active,
                MemberLoyaltyService.MemberDirectoryView::customerActive)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("M-001-000001", true, true),
                        org.assertj.core.groups.Tuple.tuple("M-001-000002", false, false));
    }

    @Test
    void walletResolvesCommercialNumbersInOneCompanyScopedBatchAndKeepsSafeFallbacks() {
        var company = PartyTestData.company();
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 1));
        member.applyBalance(new BigDecimal("12.00"));
        var documentId = UUID.randomUUID();
        var unresolvedDocumentId = UUID.randomUUID();
        var movement = org.mockito.Mockito.mock(MemberMovement.class);
        when(movement.getDocumentId()).thenReturn(documentId);
        when(movement.getType()).thenReturn(MemberMovementType.ACUMULACION_SALDO);
        var firstLot = new MemberBalanceLot(member, movement, new BigDecimal("4.00"),
                Instant.parse("2026-07-01T10:00:00Z"), null);
        var secondLot = new MemberBalanceLot(member, movement, new BigDecimal("3.00"),
                Instant.parse("2026-07-01T11:00:00Z"), null);
        var unresolvedMovement = org.mockito.Mockito.mock(MemberMovement.class);
        when(unresolvedMovement.getDocumentId()).thenReturn(unresolvedDocumentId);
        when(unresolvedMovement.getType()).thenReturn(MemberMovementType.ACUMULACION_SALDO);
        var unresolvedLot = new MemberBalanceLot(member, unresolvedMovement, new BigDecimal("2.00"),
                Instant.parse("2026-07-01T12:00:00Z"), null);
        var noDocumentLot = new MemberBalanceLot(member, null, new BigDecimal("1.00"),
                Instant.parse("2026-07-01T13:00:00Z"), null);
        var number = org.mockito.Mockito.mock(CommercialDocumentRepository.DocumentNumberProjection.class);
        when(number.getDocumentId()).thenReturn(documentId);
        when(number.getDocumentNumber()).thenReturn("  FV-2026-0001  ");
        when(context.currentCompany()).thenReturn(company);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        when(lots.findByMemberIdAndAmountRemainingGreaterThan(member.getId(), BigDecimal.ZERO))
                .thenReturn(List.of(firstLot, secondLot, unresolvedLot, noDocumentLot));
        when(documents.findDocumentNumbersByIdsAndCompanyId(any(), any()))
                .thenReturn(List.of(number));

        var wallet = service().wallet(customer.getId());

        assertThat(wallet.lots()).extracting(
                MemberLoyaltyService.MemberBalanceLotView::documentNumber)
                .containsExactly("FV-2026-0001", "FV-2026-0001", null, null);
        assertThat(wallet.lots().get(0).documentId()).isEqualTo(documentId);
        var ids = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(documents).findDocumentNumbersByIdsAndCompanyId(ids.capture(), org.mockito.ArgumentMatchers.eq(company.getId()));
        assertThat(ids.getValue()).containsExactlyInAnyOrder(documentId, unresolvedDocumentId);
    }

    @Test
    void accruesProportionalPointsBalanceLotAndSyncFromPaidSalesAmount() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        var settings = new MemberSettings(company);
        settings.update(true, new BigDecimal("10.00"), new BigDecimal("10.00"),
                BalanceExpirationPolicy.NO_CADUCA, true, new BigDecimal("10.00"),
                new BigDecimal("3.00"), true, false, MemberCardCodeFormat.QR, null, null);
        var document = org.mockito.Mockito.mock(CommercialDocument.class);
        when(document.getTipo()).thenReturn(CommercialDocumentType.TICKET);
        when(document.getClienteId()).thenReturn(customer.getId());
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(context.currentUser()).thenReturn(user);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        when(settingsRepository.findById(company.getId())).thenReturn(Optional.of(settings));
        when(categories.findByCompanyIdAndActiveTrueOrderByMinPointsDesc(company.getId()))
                .thenReturn(java.util.List.of());
        when(movements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().recordPaidSale(document, new BigDecimal("15.00"));

        assertThat(member.getMemberPoints()).isEqualTo(4);
        assertThat(member.getMemberBalance()).isEqualByComparingTo("1.50");
        verify(lots).save(any(MemberBalanceLot.class));
        verify(syncOutbox, org.mockito.Mockito.times(3)).enqueue(any());
    }

    @Test
    void persistsNewSettlementBeforeItsEligibilityLines() {
        var company = PartyTestData.company();
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        var document = org.mockito.Mockito.mock(CommercialDocument.class);
        var line = org.mockito.Mockito.mock(
                com.tpverp.backend.document.DocumentLine.class);
        var documentId = UUID.randomUUID();
        var lineId = UUID.randomUUID();
        when(document.getTipo()).thenReturn(CommercialDocumentType.TICKET);
        when(document.getClienteId()).thenReturn(customer.getId());
        when(document.getId()).thenReturn(documentId);
        when(document.getLineas()).thenReturn(java.util.List.of(line));
        when(line.getId()).thenReturn(lineId);
        when(line.getLineType()).thenReturn(
                com.tpverp.backend.document.DocumentLineType.PRODUCT);
        when(context.currentCompany()).thenReturn(company);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        when(loyaltySettlements.findById(documentId)).thenReturn(Optional.empty());
        when(loyaltyLines.findAllById(any())).thenReturn(java.util.List.of());

        service().recordPaidSale(document, new MemberLoyaltyService.LoyaltyAccrual(
                new BigDecimal("10.00"),
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                java.util.Map.of(lineId,
                        new MemberLoyaltyService.LoyaltyLineEligibility(
                                true, new BigDecimal("10.00")))));

        var persistenceOrder = org.mockito.Mockito.inOrder(
                loyaltySettlements, loyaltyLines);
        persistenceOrder.verify(loyaltySettlements).saveAndFlush(any());
        persistenceOrder.verify(loyaltyLines).saveAll(any());
    }

    @Test
    void paidSaleRepaysLoyaltyDebtBeforeMakingRewardsSpendable() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        member.addLoyaltyDebt(new BigDecimal("5.00"), 30);
        var settings = new MemberSettings(company);
        settings.update(true, BigDecimal.ONE, new BigDecimal("10.00"),
                BalanceExpirationPolicy.NO_CADUCA, true, BigDecimal.ONE,
                BigDecimal.ONE, true, false, MemberCardCodeFormat.QR, null, null);
        var document = org.mockito.Mockito.mock(CommercialDocument.class);
        when(document.getTipo()).thenReturn(CommercialDocumentType.TICKET);
        when(document.getClienteId()).thenReturn(customer.getId());
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(context.currentUser()).thenReturn(user);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        when(settingsRepository.findById(company.getId())).thenReturn(Optional.of(settings));
        when(categories.findByCompanyIdAndActiveTrueOrderByMinPointsDesc(company.getId()))
                .thenReturn(java.util.List.of());
        when(movements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().recordPaidSale(document, new BigDecimal("25.99"));

        assertThat(member.getMemberPoints()).isZero();
        assertThat(member.getMemberBalance()).isEqualByComparingTo("0.00");
        assertThat(member.getLoyaltyPointsDebt()).isEqualTo(5);
        assertThat(member.getLoyaltyBalanceDebt()).isEqualByComparingTo("2.41");
        verify(lots, never()).save(any(MemberBalanceLot.class));
        verify(syncOutbox, org.mockito.Mockito.times(2)).enqueue(any());
    }

    @Test
    void repeatedCumulativeAccrualDoesNotGenerateBenefitsTwice() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        var settings = new MemberSettings(company);
        settings.update(false, BigDecimal.ONE, BigDecimal.ZERO,
                BalanceExpirationPolicy.NO_CADUCA, true, BigDecimal.ONE,
                BigDecimal.ONE, true, false, MemberCardCodeFormat.QR, null, null);
        var document = org.mockito.Mockito.mock(CommercialDocument.class);
        var documentId = UUID.randomUUID();
        when(document.getTipo()).thenReturn(CommercialDocumentType.TICKET);
        when(document.getClienteId()).thenReturn(customer.getId());
        when(document.getId()).thenReturn(documentId);
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(context.currentUser()).thenReturn(user);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        when(settingsRepository.findById(company.getId())).thenReturn(Optional.of(settings));
        when(categories.findByCompanyIdAndActiveTrueOrderByMinPointsDesc(company.getId()))
                .thenReturn(java.util.List.of());
        when(movements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var stored = new AtomicReference<MemberDocumentLoyaltySettlement>();
        when(loyaltySettlements.findById(documentId))
                .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(loyaltySettlements.save(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return stored.get();
        });

        service().recordPaidSale(document, new BigDecimal("10.00"));
        service().recordPaidSale(document, new BigDecimal("10.00"));

        assertThat(member.getMemberPoints()).isEqualTo(10);
        assertThat(stored.get().getEligiblePaidAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void paidSaleUsesDefaultCategorySettingsWithoutPersistingSharedPrimaryKeyEntity() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        var document = org.mockito.Mockito.mock(CommercialDocument.class);
        when(document.getTipo()).thenReturn(CommercialDocumentType.TICKET);
        when(document.getClienteId()).thenReturn(customer.getId());
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(context.currentUser()).thenReturn(user);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        when(settingsRepository.findById(company.getId())).thenReturn(Optional.empty());
        when(categories.findByCompanyIdAndActiveTrueOrderByMinPointsDesc(company.getId()))
                .thenReturn(java.util.List.of());
        when(movements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().recordPaidSale(document, new BigDecimal("10.00"));

        assertThat(member.getMemberPoints()).isEqualTo(10);
        verify(settingsRepository, never()).save(any(MemberSettings.class));
    }

    @Test
    void confirmedReturnCreatesPointsDebtOnlyOnceWhenGrantedPointsWereSpent() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        var originalId = UUID.randomUUID();
        var returnId = UUID.randomUUID();
        var settlement = new MemberDocumentLoyaltySettlement(
                originalId, member, new BigDecimal("10.00"),
                new BigDecimal("10.00"), Instant.parse("2026-07-02T12:00:00Z"));
        settlement.recordAccrual(
                new BigDecimal("10.00"), 10, 10, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                Instant.parse("2026-07-02T12:00:00Z"));
        var original = org.mockito.Mockito.mock(CommercialDocument.class);
        var returned = org.mockito.Mockito.mock(CommercialDocument.class);
        when(original.getId()).thenReturn(originalId);
        when(original.getNumero()).thenReturn("001-260702-00001");
        when(returned.getId()).thenReturn(returnId);
        when(loyaltySettlements.findById(originalId)).thenReturn(Optional.of(settlement));
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(context.currentUser()).thenReturn(user);
        when(categories.findByCompanyIdAndActiveTrueOrderByMinPointsDesc(company.getId()))
                .thenReturn(java.util.List.of());
        when(movements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().reverseConfirmedReturn(
                original, returned, new BigDecimal("10.00"), new BigDecimal("10.00"));
        service().reverseConfirmedReturn(
                original, returned, new BigDecimal("10.00"), new BigDecimal("10.00"));

        assertThat(member.getLoyaltyPointsDebt()).isEqualTo(10);
        assertThat(settlement.getReversedPoints()).isEqualTo(10);
        assertThat(settlement.getReturnPointsDebtCreated()).isEqualTo(10);
    }

    @Test
    void confirmedPartialReturnRestoresMemberBalanceUsedProportionally() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        member.applyBalance(new BigDecimal("10.00"));
        member.applyBalance(new BigDecimal("-4.00"));
        var originalId = UUID.randomUUID();
        var returnId = UUID.randomUUID();
        var earnedMovement = new MemberMovement(
                member, store, user, UUID.randomUUID(), MemberMovementType.ACUMULACION_SALDO,
                new BigDecimal("10.00"), 0, null, null, "origen", Instant.now());
        var sourceLot = new MemberBalanceLot(
                member, earnedMovement, new BigDecimal("10.00"), Instant.now(), null);
        sourceLot.consume(new BigDecimal("4.00"));
        var usageMovement = new MemberMovement(
                member, store, user, originalId, MemberMovementType.USO_SALDO,
                new BigDecimal("-4.00"), 0, null, null, "pago", Instant.now());
        var consumption = new MemberBalanceLotConsumption(
                usageMovement, sourceLot, new BigDecimal("4.00"));
        var settlement = new MemberDocumentLoyaltySettlement(
                originalId, member, new BigDecimal("10.00"),
                BigDecimal.ZERO, Instant.parse("2026-07-02T12:00:00Z"));
        settlement.updateMemberBalanceUsed(new BigDecimal("4.00"), Instant.now());
        var original = org.mockito.Mockito.mock(CommercialDocument.class);
        var returned = org.mockito.Mockito.mock(CommercialDocument.class);
        when(original.getId()).thenReturn(originalId);
        when(original.getNumero()).thenReturn("001-260702-00001");
        when(returned.getId()).thenReturn(returnId);
        when(loyaltySettlements.findById(originalId)).thenReturn(Optional.of(settlement));
        when(movements.findByDocumentIdOrderByCreatedAtAsc(originalId))
                .thenReturn(java.util.List.of(usageMovement));
        when(lotConsumptions.findByMovement_Id(usageMovement.getId()))
                .thenReturn(java.util.List.of(consumption));
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(context.currentUser()).thenReturn(user);
        when(movements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().reverseConfirmedReturn(
                original, returned, new BigDecimal("5.00"), BigDecimal.ZERO);

        assertThat(member.getMemberBalance()).isEqualByComparingTo("8.00");
        assertThat(sourceLot.getAmountRemaining()).isEqualByComparingTo("8.00");
        assertThat(settlement.getRestoredMemberBalance()).isEqualByComparingTo("2.00");
    }

    @Test
    void confirmedReturnWithGrantedBalanceAndRetentionPublishesTypedRecoveryOnly() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        member.applyBalance(new BigDecimal("1.00"));
        var originalId = UUID.randomUUID();
        var returnId = UUID.randomUUID();
        var operationId = UUID.randomUUID();
        var now = Instant.parse("2026-07-02T12:00:00Z");
        var settlement = new MemberDocumentLoyaltySettlement(
                originalId, member, new BigDecimal("10.00"), new BigDecimal("10.00"), now);
        settlement.recordAccrual(
                new BigDecimal("10.00"), 0, 0, 0,
                new BigDecimal("1.00"), new BigDecimal("1.00"), BigDecimal.ZERO, now);
        var sourceMovement = new MemberMovement(
                member, store, user, originalId, MemberMovementType.ACUMULACION_SALDO,
                new BigDecimal("1.00"), 0, null, null, "acumulacion", now);
        var sourceLot = new MemberBalanceLot(member, sourceMovement,
                new BigDecimal("1.00"), now, null);
        var claim = new MemberBalanceCentralGateway.RetentionClaim(
                UUID.randomUUID(), UUID.randomUUID(), originalId,
                new BigDecimal("1.00"), new BigDecimal(".50"));
        var retention = new MemberReturnBalanceRetentionPlanner.Plan(
                originalId, member.getId(), new BigDecimal(".50"), List.of(claim),
                MemberReturnBalanceRetentionPlanner.fingerprint(
                        originalId, new BigDecimal(".50"), List.of(claim)));
        var planner = org.mockito.Mockito.mock(MemberReturnBalanceRetentionPlanner.class);
        when(planner.plan(any(), any(), any())).thenReturn(retention);
        var original = org.mockito.Mockito.mock(CommercialDocument.class);
        var returned = org.mockito.Mockito.mock(CommercialDocument.class);
        when(original.getId()).thenReturn(originalId);
        when(original.getNumero()).thenReturn("001-260702-00001");
        when(returned.getId()).thenReturn(returnId);
        when(returned.getTiendaId()).thenReturn(store.getId());
        when(loyaltySettlements.findById(originalId)).thenReturn(Optional.of(settlement));
        when(movements.findByDocumentIdOrderByCreatedAtAsc(originalId))
                .thenReturn(List.of(sourceMovement));
        when(lots.findBySourceMovement_Id(sourceMovement.getId()))
                .thenReturn(List.of(sourceLot));
        when(lotConsumptions.findByLot_Id(sourceLot.getId())).thenReturn(List.of());
        when(members.findByIdAndCompanyId(member.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(context.currentUser()).thenReturn(user);
        when(movements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = service();
        service.setRetentionPlanner(planner);

        service.reverseConfirmedReturn(
                original, returned, new BigDecimal("10.00"), new BigDecimal("10.00"), operationId);

        var savedReversal = org.mockito.ArgumentCaptor.forClass(MemberMovement.class);
        verify(movements).save(savedReversal.capture());
        assertThat(savedReversal.getValue().getType())
                .isEqualTo(MemberMovementType.DEVOLUCION_ACUMULACION_SALDO);
        assertThat(savedReversal.getValue().getBalanceAmount()).isEqualByComparingTo("-1.00");
        var recoveryEvents = org.mockito.ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(syncOutbox, org.mockito.Mockito.times(1)).enqueue(recoveryEvents.capture());
        assertThat(recoveryEvents.getAllValues())
                .extracting(SyncOutboundEventCommand::entityType)
                .containsExactly("MEMBER_RETURN_BALANCE_RECOVERY")
                .doesNotContain("MEMBER_MOVEMENT");
        assertThat(member.getMemberBalance()).isEqualByComparingTo("0.00");
        assertThat(settlement.getGrantedBalance()).isEqualByComparingTo("1.00");

        org.mockito.Mockito.reset(syncOutbox);
        when(syncOutbox.enqueue(any())).thenReturn(queuedSyncEvent);
        service.adjustBalance(member.getId(), new BigDecimal("1.00"), "movimiento distinto");

        var movementEvents = org.mockito.ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(syncOutbox, org.mockito.Mockito.times(1)).enqueue(movementEvents.capture());
        assertThat(movementEvents.getValue().entityType()).isEqualTo("MEMBER_MOVEMENT");
    }

    @Test
    void confirmedReturnEmitsAuthoritativeZeroRecoveryWhenLocalRevisionWasNotSaved() {
        var company = PartyTestData.company();
        var storeId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var sourceId = UUID.randomUUID();
        var returnId = UUID.randomUUID();
        var operationId = UUID.randomUUID();
        var originStoreId = UUID.randomUUID();
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        var reservation = org.mockito.Mockito.mock(LocalMemberBalanceReservation.class);
        var reservationCentralId = UUID.randomUUID();
        when(reservation.getMemberId()).thenReturn(member.getId());
        when(reservation.getCentralReservationId()).thenReturn(reservationCentralId);
        when(reservation.getSaleId()).thenReturn(operationId.toString());
        var original = org.mockito.Mockito.mock(CommercialDocument.class);
        var returned = org.mockito.Mockito.mock(CommercialDocument.class);
        when(original.getId()).thenReturn(sourceId);
        org.mockito.Mockito.lenient().when(original.getTiendaId()).thenReturn(originStoreId);
        when(returned.getId()).thenReturn(returnId);
        when(returned.getTiendaId()).thenReturn(storeId);
        when(returned.getTerminalOrigenId()).thenReturn(terminalId);
        when(context.currentCompany()).thenReturn(company);
        when(localBalanceReservations
                .findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                        storeId, terminalId, operationId.toString()))
                .thenReturn(Optional.of(reservation));
        when(loyaltySettlements.findById(sourceId)).thenReturn(Optional.empty());
        when(members.findById(member.getId())).thenReturn(Optional.of(member));
        var service = service();
        service.setLocalBalanceReservations(localBalanceReservations);

        service.reverseConfirmedReturn(
                original, returned, BigDecimal.ZERO, BigDecimal.ZERO, operationId);

        assertThat(originStoreId).isNotEqualTo(storeId);
        verify(localBalanceReservations, org.mockito.Mockito.times(2))
                .findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, operationId.toString());
        var event = org.mockito.ArgumentCaptor.forClass(
                com.tpverp.backend.sync.SyncOutboundEventCommand.class);
        verify(syncOutbox).enqueue(event.capture());
        assertThat(event.getValue().entityId()).isEqualTo(operationId);
        assertThat(event.getValue().storeId()).isEqualTo(storeId);
        assertThat(event.getValue().payload())
                .containsEntry("memberId", member.getId().toString())
                .containsEntry("storeId", storeId.toString())
                .containsEntry("sourceDocumentId", sourceId.toString())
                .containsEntry("returnDocumentId", returnId.toString())
                .containsEntry("attributedAmount", new BigDecimal("0.00"));
        assertThat(event.getValue().payload().get("claims")).isEqualTo(List.of());
        assertThat(event.getValue().payload())
                .containsEntry("reservationId", reservationCentralId.toString())
                .containsEntry("reservationSaleId", operationId.toString());
    }

    @Test
    void returnForMemberADoesNotAttachMemberBReservationOrCoupleItsCapacity() {
        var company = PartyTestData.company();
        var storeId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var sourceId = UUID.randomUUID();
        var returnId = UUID.randomUUID();
        var operationId = UUID.randomUUID();
        var customerA = new Customer(company, "A", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var memberA = new Member(customerA, "M-A", LocalDate.of(2026, 7, 2));
        var customerB = new Customer(company, "B", DocumentType.NIF, "2",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var memberB = new Member(customerB, "M-B", LocalDate.of(2026, 7, 2));
        var settlement = new MemberDocumentLoyaltySettlement(
                sourceId, memberA, new BigDecimal("10.00"), new BigDecimal("10.00"),
                Instant.parse("2026-07-02T12:00:00Z"));
        settlement.recordAccrual(new BigDecimal("10.00"), 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                Instant.parse("2026-07-02T12:00:00Z"));
        var sourceMovementId = UUID.randomUUID();
        var claim = new MemberBalanceCentralGateway.RetentionClaim(
                UUID.randomUUID(), sourceMovementId, sourceId,
                new BigDecimal("1.00"), new BigDecimal(".50"));
        var retention = new MemberReturnBalanceRetentionPlanner.Plan(
                sourceId, memberA.getId(), new BigDecimal(".50"), List.of(claim),
                MemberReturnBalanceRetentionPlanner.fingerprint(
                        sourceId, new BigDecimal(".50"), List.of(claim)));
        var reservationB = org.mockito.Mockito.mock(LocalMemberBalanceReservation.class);
        when(reservationB.getMemberId()).thenReturn(memberB.getId());
        org.mockito.Mockito.lenient().when(reservationB.getCentralReservationId())
                .thenReturn(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(reservationB.getSaleId())
                .thenReturn(operationId.toString());
        org.mockito.Mockito.lenient().when(reservationB.getPreparedLoyaltyAmount())
                .thenReturn(new BigDecimal("1.00"));
        org.mockito.Mockito.lenient().when(reservationB.getReservedLoyaltyAmount())
                .thenReturn(BigDecimal.ZERO.setScale(2));
        var original = org.mockito.Mockito.mock(CommercialDocument.class);
        var returned = org.mockito.Mockito.mock(CommercialDocument.class);
        when(original.getId()).thenReturn(sourceId);
        when(returned.getId()).thenReturn(returnId);
        when(returned.getTiendaId()).thenReturn(storeId);
        when(returned.getTerminalOrigenId()).thenReturn(terminalId);
        when(loyaltySettlements.findById(sourceId)).thenReturn(Optional.of(settlement));
        when(localBalanceReservations
                .findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                        storeId, terminalId, operationId.toString()))
                .thenReturn(Optional.of(reservationB));
        var planner = org.mockito.Mockito.mock(MemberReturnBalanceRetentionPlanner.class);
        when(planner.plan(any(), any(), any())).thenReturn(retention);
        var service = service();
        service.setLocalBalanceReservations(localBalanceReservations);
        service.setRetentionPlanner(planner);

        service.reverseConfirmedReturn(
                original, returned, BigDecimal.ZERO, BigDecimal.ZERO, operationId);

        var event = org.mockito.ArgumentCaptor.forClass(
                com.tpverp.backend.sync.SyncOutboundEventCommand.class);
        verify(syncOutbox, org.mockito.Mockito.times(1)).enqueue(event.capture());
        assertThat(event.getValue().payload()).containsEntry("memberId", memberA.getId().toString());
        assertThat(event.getValue().payload()).doesNotContainKey("reservationId");
        assertThat(event.getValue().payload()).doesNotContainKey("reservationSaleId");
        assertThat(event.getValue().entityType())
                .isEqualTo("MEMBER_RETURN_BALANCE_RECOVERY");
    }

    @Test
    void confirmedPartialReturnRestoresBalanceUsingOriginalExpiryFifoOrder() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        member.applyBalance(new BigDecimal("10.00"));
        member.applyBalance(new BigDecimal("-4.00"));
        var originalId = UUID.randomUUID();
        var returnId = UUID.randomUUID();
        var olderMovement = new MemberMovement(
                member, store, user, UUID.randomUUID(), MemberMovementType.ACUMULACION_SALDO,
                new BigDecimal("5.00"), 0, null, null, "origen antiguo",
                Instant.parse("2026-07-01T10:00:00Z"));
        var expiringMovement = new MemberMovement(
                member, store, user, UUID.randomUUID(), MemberMovementType.ACUMULACION_SALDO,
                new BigDecimal("5.00"), 0, null, null, "origen con caducidad",
                Instant.parse("2026-07-02T10:00:00Z"));
        var olderLot = new MemberBalanceLot(
                member, olderMovement, new BigDecimal("5.00"),
                Instant.parse("2026-07-01T10:00:00Z"), null);
        var expiringLot = new MemberBalanceLot(
                member, expiringMovement, new BigDecimal("5.00"),
                Instant.parse("2026-07-02T10:00:00Z"),
                Instant.parse("2026-07-31T23:59:59Z"));
        expiringLot.consume(new BigDecimal("2.00"));
        olderLot.consume(new BigDecimal("2.00"));
        var usageMovement = new MemberMovement(
                member, store, user, originalId, MemberMovementType.USO_SALDO,
                new BigDecimal("-4.00"), 0, null, null, "pago", Instant.now());
        var expiringConsumption = new MemberBalanceLotConsumption(
                usageMovement, expiringLot, new BigDecimal("2.00"));
        var olderConsumption = new MemberBalanceLotConsumption(
                usageMovement, olderLot, new BigDecimal("2.00"));
        var settlement = new MemberDocumentLoyaltySettlement(
                originalId, member, new BigDecimal("10.00"),
                BigDecimal.ZERO, Instant.parse("2026-07-02T12:00:00Z"));
        settlement.updateMemberBalanceUsed(new BigDecimal("4.00"), Instant.now());
        var original = org.mockito.Mockito.mock(CommercialDocument.class);
        var returned = org.mockito.Mockito.mock(CommercialDocument.class);
        when(original.getId()).thenReturn(originalId);
        when(original.getNumero()).thenReturn("001-260702-00001");
        when(returned.getId()).thenReturn(returnId);
        when(loyaltySettlements.findById(originalId)).thenReturn(Optional.of(settlement));
        when(movements.findByDocumentIdOrderByCreatedAtAsc(originalId))
                .thenReturn(java.util.List.of(usageMovement));
        when(lotConsumptions.findByMovement_Id(usageMovement.getId()))
                .thenReturn(java.util.List.of(olderConsumption, expiringConsumption));
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(context.currentUser()).thenReturn(user);
        when(movements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().reverseConfirmedReturn(
                original, returned, new BigDecimal("5.00"), BigDecimal.ZERO);

        assertThat(member.getMemberBalance()).isEqualByComparingTo("8.00");
        assertThat(expiringLot.getAmountRemaining()).isEqualByComparingTo("5.00");
        assertThat(olderLot.getAmountRemaining()).isEqualByComparingTo("3.00");
        assertThat(settlement.getRestoredMemberBalance()).isEqualByComparingTo("2.00");
    }

    @Test
    void appliesMemberPriceAndActiveCategoryDiscount() {
        var company = PartyTestData.company();
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        member.setCategory(new MemberCategory(
                company, "Oro", 0, new BigDecimal("5.00"), true, 1), false);
        var product = org.mockito.Mockito.mock(Product.class);
        when(product.getDiscountType()).thenReturn(DiscountType.MEMBER_PRICE);
        when(product.getMemberPrice()).thenReturn(new BigDecimal("80.00"));
        when(context.currentCompany()).thenReturn(company);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        var line = new DocumentLineCommand(
                UUID.randomUUID(), BigDecimal.ONE, "P-1", "Producto", "VENTA",
                new BigDecimal("100.00"), BigDecimal.ZERO, true, "IVA", new BigDecimal("21.00"));

        var priced = service().applyLineBenefit(customer.getId(), line, product);

        assertThat(priced.tarifa()).isEqualTo("MEMBER");
        assertThat(priced.precioUnitario()).isEqualByComparingTo("80.00");
        assertThat(priced.descuento()).isEqualByComparingTo("5.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-1.00"})
    void compatibilityPricingIgnoresNonPositiveMemberPrice(String memberPrice) {
        var company = PartyTestData.company();
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        var product = org.mockito.Mockito.mock(Product.class);
        when(product.getDiscountType()).thenReturn(DiscountType.MEMBER_PRICE);
        when(product.getMemberPrice()).thenReturn(new BigDecimal(memberPrice));
        when(context.currentCompany()).thenReturn(company);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        var line = line(BigDecimal.ZERO);

        var priced = service().applyLineBenefit(customer.getId(), line, product);

        assertThat(priced.precioUnitario()).isEqualByComparingTo("100.00");
        assertThat(priced.tarifa()).isEqualTo("VENTA");
    }

    @Test
    void appliesActiveCategoryDiscountToNormalProduct() {
        var company = PartyTestData.company();
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        member.setCategory(new MemberCategory(
                company, "Oro", 0, new BigDecimal("5.00"), true, 1), false);
        var product = org.mockito.Mockito.mock(Product.class);
        when(product.getDiscountType()).thenReturn(DiscountType.NONE);
        when(context.currentCompany()).thenReturn(company);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        var line = line(new BigDecimal("5.00"));

        var priced = service().applyLineBenefit(customer.getId(), line, product);

        assertThat(priced.precioUnitario()).isEqualByComparingTo("100.00");
        assertThat(priced.descuento()).isEqualByComparingTo("5.00");
    }

    @Test
    void preservesManualDiscountWhenItExceedsCategoryDiscount() {
        var company = PartyTestData.company();
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        member.setCategory(new MemberCategory(
                company, "Oro", 0, new BigDecimal("5.00"), true, 1), false);
        var product = org.mockito.Mockito.mock(Product.class);
        when(product.getDiscountType()).thenReturn(DiscountType.NONE);
        when(context.currentCompany()).thenReturn(company);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));

        var priced = service().applyLineBenefit(customer.getId(), line(new BigDecimal("8.00")), product);

        assertThat(priced.descuento()).isEqualByComparingTo("8.00");
    }

    @Test
    void doesNotApplyCategoryBenefitForInactiveMemberOrDisabledCategory() {
        var company = PartyTestData.company();
        var inactiveCustomer = new Customer(company, "Inactivo", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var inactive = new Member(inactiveCustomer, "M-001-000001", LocalDate.of(2026, 7, 2));
        inactive.setCategory(new MemberCategory(
                company, "Oro", 0, new BigDecimal("5.00"), true, 1), false);
        inactive.deactivate();
        var disabledCustomer = new Customer(company, "Deshabilitado", DocumentType.NIF, "2",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var disabled = new Member(disabledCustomer, "M-001-000002", LocalDate.of(2026, 7, 2));
        disabled.setCategory(new MemberCategory(
                company, "Plata", 0, new BigDecimal("5.00"), false, 2), false);
        var product = org.mockito.Mockito.mock(Product.class);
        when(product.getDiscountType()).thenReturn(DiscountType.NONE);
        when(context.currentCompany()).thenReturn(company);
        when(members.findByCustomerIdAndCompanyId(inactiveCustomer.getId(), company.getId()))
                .thenReturn(Optional.of(inactive));
        when(members.findByCustomerIdAndCompanyId(disabledCustomer.getId(), company.getId()))
                .thenReturn(Optional.of(disabled));

        assertThat(service().applyLineBenefit(inactiveCustomer.getId(), line(BigDecimal.ZERO), product)
                .descuento()).isEqualByComparingTo("0.00");
        assertThat(service().applyLineBenefit(disabledCustomer.getId(), line(BigDecimal.ZERO), product)
                .descuento()).isEqualByComparingTo("0.00");
    }

    private static DocumentLineCommand line(BigDecimal discount) {
        return new DocumentLineCommand(
                UUID.randomUUID(), BigDecimal.ONE, "P-1", "Producto", "VENTA",
                new BigDecimal("100.00"), discount, true, "IVA", new BigDecimal("21.00"));
    }

    @Test
    void consumesMemberBalanceFromOldestAvailableLot() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        member.applyBalance(new BigDecimal("10.00"));
        member.applyOfficialState(new BigDecimal("10.00"), 0, null,
                Instant.parse("2026-07-02T11:58:00Z"));
        var document = org.mockito.Mockito.mock(CommercialDocument.class);
        var movement = org.mockito.Mockito.mock(MemberMovement.class);
        var lot = new MemberBalanceLot(member, null, new BigDecimal("10.00"),
                Instant.parse("2026-07-01T12:00:00Z"), null);
        when(document.getClienteId()).thenReturn(customer.getId());
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(context.currentUser()).thenReturn(user);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        when(movements.save(any())).thenReturn(movement);
        when(movement.getId()).thenReturn(UUID.randomUUID());
        when(lots.findByMemberIdAndAmountRemainingGreaterThan(member.getId(), BigDecimal.ZERO))
                .thenReturn(java.util.List.of(lot));

        var consumed = service().consumeBalanceForPayment(document, new BigDecimal("4.00"));

        assertThat(consumed).isEqualByComparingTo("4.00");
        assertThat(member.getMemberBalance()).isEqualByComparingTo("6.00");
        assertThat(lot.getAmountRemaining()).isEqualByComparingTo("6.00");
        verify(lotConsumptions).save(any(MemberBalanceLotConsumption.class));
    }

    @Test
    void authorizePreparedConsumptionRefreshesOfficialSyncBeforeFinalQuoteValidation() {
        var now = Instant.parse("2026-07-02T12:00:00Z");
        var company = PartyTestData.company();
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        member.applyBalance(new BigDecimal("10.00"));
        member.applyOfficialState(new BigDecimal("10.00"), 0, null,
                now.minusSeconds(6 * 60L));
        var lot = new MemberBalanceLot(member, null, new BigDecimal("10.00"),
                now.minusSeconds(3600), null);
        var central = new MemberBalanceCentralGateway.ReservationResponse(
                UUID.randomUUID(), member.getId(), "PREPARED",
                new BigDecimal("1.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("1.00"), BigDecimal.ZERO.setScale(2), UUID.randomUUID(),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("10.00"), BigDecimal.ZERO.setScale(2), List.of(), now,
                now.plusSeconds(120), 30, 120);
        var reservation = LocalMemberBalanceReservation.create(
                UUID.randomUUID(), UUID.randomUUID(), member.getId(), "sale-1", central, now);
        when(localBalanceReservations.findForUpdate(reservation.getId()))
                .thenReturn(Optional.of(reservation));
        when(members.findById(member.getId())).thenReturn(Optional.of(member));
        when(context.currentCompany()).thenReturn(company);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        when(lots.findByMemberIdAndAmountRemainingGreaterThan(member.getId(), BigDecimal.ZERO))
                .thenReturn(List.of(lot));

        var protocol = new MemberBalanceCheckoutProtocolService(
                localBalanceReservations, mock(MemberBalanceReservationCoordinator.class),
                members, Clock.fixed(now, ZoneOffset.UTC));
        protocol.authorizePreparedLocalConsumption(
                reservation.getId(), customer.getId(), new BigDecimal("1.00"),
                BigDecimal.ZERO.setScale(2));

        assertThat(member.getOfficialSyncedAt()).isEqualTo(now);
        assertThatCode(() -> service().validateBalanceForCheckout(
                customer.getId(), new BigDecimal("1.00")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCheckoutWhenOfficialMemberBalanceSnapshotIsStaleWithStableCause() {
        var company = PartyTestData.company();
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        when(context.currentCompany()).thenReturn(company);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service().validateBalanceForCheckout(
                customer.getId(), BigDecimal.ONE))
                .isExactlyInstanceOf(MemberBalanceOfficialSyncRequiredException.class)
                .hasMessage("message.member.official_sync_required");
        verify(lots, never()).findByMemberIdAndAmountRemainingGreaterThan(any(), any());
    }

    @Test
    void rejectsBalanceThatExpiredAtTheCurrentInstant() {
        var company = PartyTestData.company();
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        member.applyBalance(new BigDecimal("5.00"));
        member.applyOfficialState(new BigDecimal("5.00"), 0, null,
                Instant.parse("2026-07-02T11:58:00Z"));
        var expiredLot = new MemberBalanceLot(
                member, null, new BigDecimal("5.00"),
                Instant.parse("2026-07-01T12:00:00Z"),
                Instant.parse("2026-07-02T12:00:00Z"));
        when(context.currentCompany()).thenReturn(company);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        when(lots.findByMemberIdAndAmountRemainingGreaterThan(member.getId(), BigDecimal.ZERO))
                .thenReturn(java.util.List.of(expiredLot));

        assertThatThrownBy(() -> service().validateBalanceForCheckout(
                customer.getId(), BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.member.balance_insufficient_or_expired");
        verify(movements, org.mockito.Mockito.never()).save(any());
        verify(lotConsumptions, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void consumesAvailableBalanceByExpirationAndThenCreationOrder() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        member.applyBalance(new BigDecimal("10.00"));
        member.applyOfficialState(new BigDecimal("10.00"), 0, null,
                Instant.parse("2026-07-02T11:58:00Z"));
        var laterExpiration = new MemberBalanceLot(
                member, null, new BigDecimal("5.00"),
                Instant.parse("2026-06-01T12:00:00Z"),
                Instant.parse("2026-08-31T23:59:59Z"));
        var earlierExpiration = new MemberBalanceLot(
                member, null, new BigDecimal("5.00"),
                Instant.parse("2026-07-01T12:00:00Z"),
                Instant.parse("2026-07-31T23:59:59Z"));
        var document = org.mockito.Mockito.mock(CommercialDocument.class);
        var movement = org.mockito.Mockito.mock(MemberMovement.class);
        when(document.getClienteId()).thenReturn(customer.getId());
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(context.currentUser()).thenReturn(user);
        when(members.findByCustomerIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        when(movements.save(any())).thenReturn(movement);
        when(movement.getId()).thenReturn(UUID.randomUUID());
        when(lots.findByMemberIdAndAmountRemainingGreaterThan(member.getId(), BigDecimal.ZERO))
                .thenReturn(java.util.List.of(laterExpiration, earlierExpiration));

        service().consumeBalanceForSaleReduction(document, new BigDecimal("6.00"));

        assertThat(earlierExpiration.getAmountRemaining()).isZero();
        assertThat(laterExpiration.getAmountRemaining()).isEqualByComparingTo("4.00");
        assertThat(member.getMemberBalance()).isEqualByComparingTo("4.00");
        verify(lotConsumptions, org.mockito.Mockito.times(2))
                .save(any(MemberBalanceLotConsumption.class));
    }

    @Test
    void activationAssignsLowestNonManualCategoryAndRecordsMovement() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        var employee = new MemberCategory(
                company, "Empleado", "EMPLEADO", 0, new BigDecimal("15.00"), true, true, 9000);
        var base = new MemberCategory(company, "Base", 0, BigDecimal.ZERO, false, 1);
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(context.currentUser()).thenReturn(user);
        when(categories.findByCompanyIdAndActiveTrueOrderByMinPointsAscNameAsc(company.getId()))
                .thenReturn(java.util.List.of(employee, base));
        when(movements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().activateMember(member);

        assertThat(member.getMemberCategory()).isEqualTo(base);
        verify(movements).save(any(MemberMovement.class));
        verify(syncOutbox).enqueue(any());
    }

    @Test
    void activationCreatesPendingWelcomeCardWhenConfiguredAndCustomerHasEmail() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, "cliente@example.com", null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        var config = new MemberSettings(company);
        config.update(false, BigDecimal.ONE, BigDecimal.ZERO,
                BalanceExpirationPolicy.NO_CADUCA, true, BigDecimal.ONE, BigDecimal.ONE,
                true, true, MemberCardCodeFormat.QR, "Bienvenido", "Codigo {memberId}");
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(context.currentUser()).thenReturn(user);
        when(settingsRepository.findById(company.getId())).thenReturn(Optional.of(config));
        when(categories.findByCompanyIdAndActiveTrueOrderByMinPointsAscNameAsc(company.getId()))
                .thenReturn(java.util.List.of());
        when(movements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().activateMember(member);

        var delivery = org.mockito.ArgumentCaptor.forClass(MemberCardDelivery.class);
        verify(cardDeliveries).save(delivery.capture());
        assertThat(delivery.getValue().getEmail()).isEqualTo("cliente@example.com");
        assertThat(delivery.getValue().getCardCode()).isEqualTo("M-001-000001");
        assertThat(delivery.getValue().getStatus()).isEqualTo(MemberCardDeliveryStatus.PENDIENTE);
    }

    @Test
    void officialStateOverwritesLocalStateOnlyOncePerSourceEvent() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        member.applyBalance(new BigDecimal("3.00"));
        member.applyPoints(3);
        var category = new MemberCategory(company, "Base", 0, BigDecimal.ZERO, false, 1);
        var eventId = UUID.randomUUID();
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(context.currentUser()).thenReturn(user);
        when(members.findByIdAndCompanyId(member.getId(), company.getId()))
                .thenReturn(Optional.of(member));
        when(categories.findByIdAndCompanyId(category.getId(), company.getId()))
                .thenReturn(Optional.of(category));
        when(movements.existsBySourceEventId(eventId)).thenReturn(false, true);

        var command = new MemberLoyaltyService.OfficialMemberStateCommand(
                eventId, member.getId(), new BigDecimal("9.00"), 12,
                category.getId(), Instant.parse("2026-07-02T11:59:00Z"));
        service().applyOfficialState(command);
        service().applyOfficialState(command);

        assertThat(member.getMemberBalance()).isEqualByComparingTo("9.00");
        assertThat(member.getMemberPoints()).isEqualTo(12);
        assertThat(member.getMemberCategory()).isEqualTo(category);
        verify(movements, org.mockito.Mockito.times(1)).save(any(MemberMovement.class));
    }

    @Test
    void expiresRemainingBalanceLots() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        member.applyBalance(new BigDecimal("7.00"));
        var lot = new MemberBalanceLot(member, null, new BigDecimal("7.00"),
                Instant.parse("2026-06-01T12:00:00Z"),
                Instant.parse("2026-07-01T12:00:00Z"));
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        when(context.currentUser()).thenReturn(user);
        when(lots.findByExpiresAtBeforeAndExpiredAtIsNullAndAmountRemainingGreaterThan(
                Instant.parse("2026-07-02T12:00:00Z"), BigDecimal.ZERO))
                .thenReturn(java.util.List.of(lot));
        when(movements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var expired = service().expireBalanceLots();

        assertThat(expired).isEqualTo(1);
        assertThat(member.getMemberBalance()).isEqualByComparingTo("0.00");
        assertThat(lot.getAmountRemaining()).isEqualByComparingTo("0.00");
        verify(syncOutbox).enqueue(any());
    }

    @Test
    void listsCardDeliveriesForCurrentCompanyAndStatus() {
        var company = PartyTestData.company();
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, "cliente@example.com", null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        var delivery = new MemberCardDelivery(
                member, "cliente@example.com", "Tarjeta", "Codigo",
                MemberCardCodeFormat.QR, Instant.parse("2026-07-02T12:00:00Z"));
        when(context.currentCompany()).thenReturn(company);
        when(cardDeliveries.findByCompanyIdAndStatus(company.getId(), MemberCardDeliveryStatus.PENDIENTE))
                .thenReturn(java.util.List.of(delivery));

        var result = service().cardDeliveries(MemberCardDeliveryStatus.PENDIENTE, null);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().email()).isEqualTo("cliente@example.com");
        assertThat(result.getFirst().status()).isEqualTo(MemberCardDeliveryStatus.PENDIENTE);
    }

    @Test
    void retriesErroredCardDelivery() {
        var company = PartyTestData.company();
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, "cliente@example.com", null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        var delivery = new MemberCardDelivery(
                member, "cliente@example.com", "Tarjeta", "Codigo",
                MemberCardCodeFormat.QR, Instant.parse("2026-07-02T12:00:00Z"));
        delivery.markError("smtp");
        when(context.currentCompany()).thenReturn(company);
        when(cardDeliveries.findByIdAndCompanyId(delivery.getId(), company.getId()))
                .thenReturn(Optional.of(delivery));

        var result = service().retryCardDelivery(delivery.getId());

        assertThat(result.status()).isEqualTo(MemberCardDeliveryStatus.PENDIENTE);
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    void enablingWelcomeCardsRequiresActiveSmtpSettings() {
        var company = PartyTestData.company();
        when(context.currentCompany()).thenReturn(company);
        when(smtpSettings.findById(company.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateSettings(new MemberLoyaltyService.MemberSettingsCommand(
                false, BigDecimal.ONE, BigDecimal.ZERO, BalanceExpirationPolicy.NO_CADUCA,
                true, BigDecimal.ONE, BigDecimal.ONE, true, true,
                MemberCardCodeFormat.QR, "Bienvenido", "Codigo {memberId}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("message.member_welcome.smtp_required");
    }

    private MemberLoyaltyService service() {
        return new MemberLoyaltyService(
                members, categories, settingsRepository, movements, lots, lotConsumptions,
                loyaltySettlements, loyaltyLines,
                cardDeliveries, smtpSettings, channels, documents,
                syncOutbox, context,
                Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC));
    }
}
