package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock CustomerRepository customers;
    @Mock MemberRepository members;
    @Mock MemberBalanceMovementRepository movements;
    @Mock StoreRepository stores;
    @Mock UserAccountRepository users;
    @Mock PartyCodeAllocator codes;
    @Mock MemberLoyaltyService memberLoyalty;
    @Mock CustomerIdentityCoordinator identities;

    private Company company;
    private Store store;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        company = PartyTestData.company();
        store = PartyTestData.store(company);
        user = new UserAccount(store, "CAJA", "hash", new Role(store, "VENDEDOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("CAJA", "token"));
        when(stores.findAll()).thenReturn(List.of(store));
    }

    @Test
    void createsCustomerInCurrentStoreCompanyAndNormalizesDocument() {
        var approval = allowReservation(null, DocumentType.DNI, "12345678Z");
        when(customers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(codes.nextClient(store)).thenReturn("C-001-000001");

        var created = service().create(new CustomerService.CustomerCommand(
                "Cliente", DocumentType.DNI, " 12 345 678-z ", null,
                null, null, null, BigDecimal.ZERO, false, null));

        assertThat(created.documentNumber()).isEqualTo("12345678Z");
        assertThat(created.documentType()).isEqualTo(DocumentType.DNI);
        assertThat(created.id()).isEqualTo(approval.customerId());
        assertThat(created.clientId()).isEqualTo("C-001-000001");
        assertThat(created.isMember()).isFalse();
        var order = inOrder(customers, identities, codes);
        order.verify(customers).findByCompanyAndNormalizedDocument(company.getId(), "12345678Z");
        order.verify(codes).nextClient(store);
        order.verify(identities).reserve(eq(company.getId()), eq(store.getId()), eq(null),
                eq(CustomerDocumentIdentity.validate(DocumentType.DNI, "12345678Z")), any(Customer.class));
        var persisted = ArgumentCaptor.forClass(Customer.class);
        order.verify(customers).save(persisted.capture());
        order.verify(identities).complete(approval, persisted.getValue());
    }

    @Test
    void rejectsCreatingCustomerAndMemberInTheSameOperation() {
        var command = new CustomerService.CustomerCommand(
                "Member", DocumentType.PASAPORTE, "99z", null,
                null, null, null, BigDecimal.ZERO, true, " EXT/2026 #1 ");

        assertThatThrownBy(() -> service().create(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message.member.customer_must_exist");
        verify(customers, never()).save(any());
        verify(members, never()).save(any());
    }

    @Test
    void activatesMemberFromAnExistingActiveCustomer() {
        var customer = new Customer(
                company, "Member", DocumentType.PASAPORTE, "99Z", null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        customer.assignClientCode(store.getId(), "C-001-000001");
        when(customers.findByIdAndCompanyId(customer.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.of(customer));
        when(members.findByCustomerIdAndCompanyId(customer.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.empty());
        when(codes.nextMember(store)).thenReturn("M-001-000001");
        when(members.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var activated = service().activateMember(customer.getId());

        assertThat(activated.memberId()).isEqualTo("M-001-000001");
        assertThat(activated.id()).isEqualTo(customer.getId());
        var member = ArgumentCaptor.forClass(Member.class);
        verify(members).save(member.capture());
        verify(memberLoyalty).activateMember(member.getValue());
    }

    @Test
    void storesOptionalPersonalAndCommercialConsentData() {
        UUID channelId = UUID.randomUUID();
        allowReservation(null, DocumentType.PASAPORTE, "55A");
        when(customers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(codes.nextClient(store)).thenReturn("C-001-000001");

        var created = service().create(new CustomerService.CustomerCommand(
                "Cliente", DocumentType.PASAPORTE, "55a", null,
                null, "cliente@example.com", null, BigDecimal.ZERO,
                false, null, LocalDate.of(1990, 5, 12), CustomerGender.FEMENINO,
                true, channelId));

        assertThat(created.birthday()).isEqualTo(LocalDate.of(1990, 5, 12));
        assertThat(created.gender()).isEqualTo(CustomerGender.FEMENINO);
        assertThat(created.commercialConsent()).isTrue();
        assertThat(created.preferredCommercialChannelId()).isEqualTo(channelId);
    }

    @Test
    void importacionMasivaAsignaBloqueOrdenadoPorNif() {
        var firstApproval = allowReservation(null, DocumentType.PASAPORTE, "A1");
        var secondApproval = allowReservation(null, DocumentType.PASAPORTE, "Z9");
        when(codes.nextClients(store, 2))
                .thenReturn(List.of("C-001-000001", "C-001-000002"));
        when(customers.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var zeta = new CustomerService.CustomerCommand(
                "Zeta", DocumentType.PASAPORTE, "Z9", null,
                null, null, null, BigDecimal.ZERO, false, null);
        var alfa = new CustomerService.CustomerCommand(
                "Alfa", DocumentType.PASAPORTE, "A1", null,
                null, null, null, BigDecimal.ZERO, false, null);

        var imported = service().createBatch(List.of(zeta, alfa));

        assertThat(imported)
                .extracting(CustomerService.CustomerView::documentNumber,
                        CustomerService.CustomerView::clientId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A1", "C-001-000001"),
                        org.assertj.core.groups.Tuple.tuple("Z9", "C-001-000002"));
        assertThat(imported).extracting(CustomerService.CustomerView::id)
                .containsExactly(firstApproval.customerId(), secondApproval.customerId());
        verify(identities).complete(eq(firstApproval), any(Customer.class));
        verify(identities).complete(eq(secondApproval), any(Customer.class));
    }

    @Test
    void reactivarMemberConservaCodigoSinConsumirOtroNumero() {
        var customer = new Customer(
                company, "Member", DocumentType.PASAPORTE, "M1", null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        customer.assignClientCode(store.getId(), "C-001-000001");
        var member = new Member(customer, "M-001-000001", java.time.LocalDate.of(2026, 5, 1));
        member.assignMemberStore(store.getId());
        member.deactivate();
        when(customers.findLockedByIdAndCompanyId(customer.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.of(customer));
        when(members.findByCustomerIdAndCompanyId(customer.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.of(member));

        var updated = service().update(customer.getId(), new CustomerService.CustomerCommand(
                "Member", DocumentType.PASAPORTE, "M1", null,
                null, null, null, BigDecimal.ZERO, true, null));

        assertThat(updated.memberId()).isEqualTo("M-001-000001");
        assertThat(updated.memberSince()).isEqualTo(java.time.LocalDate.of(2026, 5, 1));
        verify(codes, never()).nextMember(any());
        verify(memberLoyalty).activateMember(member);
    }

    @Test
    void recordsManualMemberBalanceMovementWithAuthenticatedUser() {
        var customer = new Customer(
                company, "Member", DocumentType.PASAPORTE, "1", null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", java.time.LocalDate.of(2026, 5, 1));
        when(customers.findByIdAndCompanyId(customer.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.of(customer));
        when(members.findByCustomerIdAndCompanyId(customer.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.of(member));
        when(users.findByTiendaIdAndNombre(store.getId(), "CAJA")).thenReturn(Optional.of(user));
        when(movements.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service().moveBalance(
                customer.getId(), new BigDecimal("15"), "Carga manual");

        assertThat(result.balance()).isEqualByComparingTo("15.00");
        var movement = ArgumentCaptor.forClass(MemberBalanceMovement.class);
        verify(movements).save(movement.capture());
        assertThat(movement.getValue().getAmount()).isEqualByComparingTo("15.00");
    }

    @Test
    void rejectsIncompleteFiscalCustomerWhenValidationIsRequested() {
        var customer = new Customer(
                company, "Cliente", DocumentType.PASAPORTE, "1", null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        when(customers.findByIdAndCompanyId(customer.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service().validateFiscalData(customer.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fiscales");
    }

    @Test
    void reactivatesCustomerInCurrentCompanyWithoutChangingItsCode() {
        var customer = new Customer(
                company, "Cliente", DocumentType.PASAPORTE, "1", null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        customer.assignClientCode(store.getId(), "C-001-000001");
        customer.deactivate();
        when(customers.findByIdAndCompanyId(customer.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.of(customer));

        service().activate(customer.getId());
        service().activate(customer.getId());

        assertThat(customer.isActive()).isTrue();
        assertThat(customer.getClientId()).isEqualTo("C-001-000001");
        verify(codes, never()).nextClient(any());
    }

    @Test
    void rejectsMemberActivationWhenCustomerIsInactive() {
        var customer = new Customer(
                company, "Cliente", DocumentType.PASAPORTE, "1", null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        customer.deactivate();
        when(customers.findByIdAndCompanyId(customer.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service().activateMember(customer.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message.member.customer_inactive");
        verify(members, never()).findByCustomerIdAndCompanyId(any(), any());
    }

    @Test
    void searchesLimitedSaleOptionsAndKeepsInactiveCustomersVisible() {
        var active = new Customer(
                company, "Cliente Activo", DocumentType.PASAPORTE, "1", null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        active.assignClientCode(store.getId(), "C-001-000001");
        var inactive = new Customer(
                company, "Cliente Desactivado", DocumentType.PASAPORTE, "2", null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        inactive.assignClientCode(store.getId(), "C-001-000002");
        inactive.deactivate();
        when(customers.searchSaleOptions(
                eq(PartyTestData.id(company)), eq("Cliente"),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(active, inactive));
        var debt = mock(CustomerRepository.CustomerDebtSummary.class);
        when(debt.getCustomerId()).thenReturn(active.getId());
        when(debt.getOutstandingDebt()).thenReturn(new BigDecimal("42.50"));
        when(debt.getOverdueDebt()).thenReturn(new BigDecimal("12.50"));
        when(customers.debtSummaries(any(), any())).thenReturn(List.of(debt));

        var result = service().searchSaleOptions("  Cliente  ", 500);

        assertThat(result).extracting(
                CustomerService.SaleCustomerSearchView::fiscalName,
                CustomerService.SaleCustomerSearchView::active)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Cliente Activo", true),
                        org.assertj.core.groups.Tuple.tuple("Cliente Desactivado", false));
        assertThat(result.getFirst().outstandingDebt()).isEqualByComparingTo("42.50");
        assertThat(result.getFirst().overdueDebt()).isEqualByComparingTo("12.50");
        assertThat(result.get(1).outstandingDebt()).isEqualByComparingTo("0.00");
        var pageable = ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(customers).searchSaleOptions(
                eq(PartyTestData.id(company)), eq("Cliente"), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
        verify(customers).debtSummaries(eq(List.of(active.getId(), inactive.getId())), any());
        verify(members).findByCompanyIdAndCustomerIdIn(
                PartyTestData.id(company), List.of(active.getId(), inactive.getId()));
    }

    @Test
    void updateCanExplicitlyRemoveAnExistingCreditLimit() {
        var customer = new Customer(
                company, "Cliente", DocumentType.PASAPORTE, "1", null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        customer.configureCredit(true, new BigDecimal("500.00"), 30, false, false);
        when(customers.findLockedByIdAndCompanyId(customer.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.of(customer));
        when(members.findByCustomerIdAndCompanyId(customer.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.empty());

        var request = new CustomerController.CustomerRequest(
                "Cliente", DocumentType.PASAPORTE, "1", null, null, null, null,
                BigDecimal.ZERO, false, null, null, null, false, null,
                true, null, 30, false, false, true);

        var updated = service().update(customer.getId(), request.command());

        assertThat(updated.creditLimit()).isNull();
        assertThat(customer.getCreditLimit()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"DNI,12345678A", "NIE,X1234567A", "NIF,B12345678", "DNI,X1234567L"})
    void rejectsInvalidCustomerIdentityBeforeReservationOrLocalWrite(DocumentType type, String number) {
        assertThatThrownBy(() -> service().create(command(type, number)))
                .isInstanceOf(CustomerIdentityException.class)
                .hasMessage("CUSTOMER_DOCUMENT_INVALID");

        verifyNoInteractions(identities, codes);
        verify(customers, never()).save(any());
        verify(customers, never()).findByCompanyAndNormalizedDocument(any(), any());
    }

    @Test
    void preventsDuplicatingADocumentBySelectingAnotherTypeOrChangingSeparators() {
        var existing = existing(DocumentType.DNI, "12345678Z");
        when(customers.findByCompanyAndNormalizedDocument(company.getId(), "12345678Z"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service().create(command(DocumentType.NIF, "12 345 678-z")))
                .isInstanceOf(CustomerIdentityException.class)
                .hasMessage("CUSTOMER_DOCUMENT_DUPLICATE");

        verifyNoInteractions(identities, codes);
        verify(customers, never()).save(any());
    }

    @Test
    void batchRejectsCrossTypeNormalizedDuplicatesBeforeAnyReservation() {
        assertThatThrownBy(() -> service().createBatch(List.of(
                command(DocumentType.DNI, "12345678Z"),
                command(DocumentType.PASAPORTE, "12 345 678-z"))))
                .isInstanceOf(CustomerIdentityException.class)
                .hasMessage("CUSTOMER_DOCUMENT_DUPLICATE");

        verifyNoInteractions(identities, codes);
        verify(customers, never()).saveAll(any());
    }

    @Test
    void unavailableSaasBlocksCreationWithoutSavingAndLeavesCodeRollbackToTheTransaction() {
        when(codes.nextClient(store)).thenReturn("C-001-000001");
        when(identities.reserve(eq(company.getId()), eq(store.getId()), eq(null),
                eq(CustomerDocumentIdentity.validate(DocumentType.PASAPORTE, "PASS123")), any(Customer.class)))
                .thenThrow(CustomerIdentityException.unavailable());

        assertThatThrownBy(() -> service().create(command(DocumentType.PASAPORTE, "PASS123")))
                .isInstanceOf(CustomerIdentityException.class)
                .hasMessage("CUSTOMER_IDENTITY_SAAS_UNAVAILABLE");

        verify(customers, never()).save(any());
        verify(identities, never()).complete(any(), any());
        verify(codes).nextClient(store);
    }

    @Test
    void correctsAnUnlinkedHistoricalIdentityThroughCentralReservationWithTheStableLocalId() {
        var customer = lockedCustomer(DocumentType.NIF, "LEGACY-INVALID");
        var approval = allowReservation(customer, DocumentType.DNI, "00000001R");

        var updated = service().update(customer.getId(), command(DocumentType.DNI, "00000001R"));

        assertThat(updated.id()).isEqualTo(customer.getId());
        assertThat(updated.documentNumber()).isEqualTo("00000001R");
        assertThat(approval.operation().customerId()).isEqualTo(customer.getId());
        assertThat(approval.operation().expectedCustomerId()).isNull();
        verify(identities).complete(approval, customer);
        verify(customers, never()).save(any());
    }

    @Test
    void historicalIdentityCorrectionIsBlockedOfflineWithoutChangingTheLocalRecord() {
        var customer = lockedCustomer(DocumentType.NIF, "LEGACY-INVALID");
        when(identities.reserve(eq(company.getId()), eq(store.getId()), eq(customer),
                eq(CustomerDocumentIdentity.validate(DocumentType.DNI, "00000001R")), any(Customer.class)))
                .thenThrow(CustomerIdentityException.unavailable());

        assertThatThrownBy(() -> service().update(customer.getId(), command(DocumentType.DNI, "00000001R")))
                .isInstanceOf(CustomerIdentityException.class)
                .hasMessage("CUSTOMER_IDENTITY_SAAS_UNAVAILABLE");

        assertThat(customer.getDocumentNumber()).isEqualTo("LEGACY-INVALID");
        assertThat(customer.getDocumentType()).isEqualTo(DocumentType.NIF);
        assertThat(customer.getSaasCustomerId()).isNull();
        verify(identities, never()).complete(any(), any());
        verifyNoInteractions(members, codes);
    }

    @Test
    void changesLinkedIdentityOnlyAfterTheCompanyScopedReservationSucceeds() {
        var customer = lockedCustomer(DocumentType.DNI, "12345678Z");
        customer.linkSaasIdentity(UUID.randomUUID(), 3);
        var approval = allowReservation(customer, DocumentType.NIE, "X1234567L");

        var updated = service().update(customer.getId(), command(DocumentType.NIE, "x-1234567-l"));

        assertThat(updated.documentType()).isEqualTo(DocumentType.NIE);
        assertThat(updated.documentNumber()).isEqualTo("X1234567L");
        assertThat(updated.clientId()).isEqualTo("C-001-000001");
        var order = inOrder(customers, identities, members);
        order.verify(customers).findLockedByIdAndCompanyId(customer.getId(), company.getId());
        order.verify(customers).findByCompanyAndNormalizedDocument(company.getId(), "X1234567L");
        order.verify(identities).reserve(eq(company.getId()), eq(store.getId()), eq(customer),
                eq(CustomerDocumentIdentity.validate(DocumentType.NIE, "X1234567L")), any(Customer.class));
        order.verify(members).findByCustomerIdAndCompanyId(customer.getId(), company.getId());
        order.verify(identities).complete(approval, customer);
        verifyNoInteractions(codes);
    }

    @Test
    void unavailableSaasDoesNotMutateLinkedCustomerIdentityOrOtherFields() {
        var customer = lockedCustomer(DocumentType.DNI, "12345678Z");
        customer.linkSaasIdentity(UUID.randomUUID(), 3);
        when(identities.reserve(eq(company.getId()), eq(store.getId()), eq(customer),
                eq(CustomerDocumentIdentity.validate(DocumentType.DNI, "00000001R")), any(Customer.class)))
                .thenThrow(CustomerIdentityException.unavailable());

        assertThatThrownBy(() -> service().update(customer.getId(), command(DocumentType.DNI, "00000001R")))
                .isInstanceOf(CustomerIdentityException.class)
                .hasMessage("CUSTOMER_IDENTITY_SAAS_UNAVAILABLE");

        assertThat(customer.getDocumentNumber()).isEqualTo("12345678Z");
        assertThat(customer.getFiscalName()).isEqualTo("Original");
        assertThat(customer.getSaasIdentityRevision()).isEqualTo(3);
        verify(identities, never()).complete(any(), any());
        verifyNoInteractions(members);
    }

    @Test
    void updateCannotTakeAnotherCustomersNormalizedNumberRegardlessOfType() {
        var customer = lockedCustomer(DocumentType.PASAPORTE, "PASS123");
        customer.linkSaasIdentity(UUID.randomUUID(), 1);
        var other = existing(DocumentType.DNI, "12345678Z");
        when(customers.findByCompanyAndNormalizedDocument(company.getId(), "12345678Z"))
                .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service().update(customer.getId(), command(DocumentType.NIF, "12345678-z")))
                .isInstanceOf(CustomerIdentityException.class)
                .hasMessage("CUSTOMER_DOCUMENT_DUPLICATE");

        assertThat(customer.getDocumentNumber()).isEqualTo("PASS123");
        verifyNoInteractions(identities);
    }

    @Test
    void allowsOtherCustomerFieldsOfflineWithoutRevalidatingAnUnchangedHistoricalNumber() {
        var customer = lockedCustomer(DocumentType.NIF, "OLD-INVALID");

        var updated = service().update(customer.getId(), command(DocumentType.NIF, "OLD INVALID"));

        assertThat(updated.fiscalName()).isEqualTo("Updated");
        assertThat(updated.documentNumber()).isEqualTo("OLDINVALID");
        assertThat(updated.clientId()).isEqualTo("C-001-000001");
        verifyNoInteractions(identities, codes);
        verify(customers, never()).findByCompanyAndNormalizedDocument(any(), any());
    }

    @Test
    void convertsLegacyCifDisplayTypeToNifWithoutChangingIdentityOrContactingSaas() {
        var customer = lockedCustomer(DocumentType.CIF, "B12345674");

        var updated = service().update(customer.getId(), command(DocumentType.NIF, "B-12345674"));

        assertThat(updated.documentType()).isEqualTo(DocumentType.NIF);
        assertThat(updated.documentNumber()).isEqualTo("B12345674");
        verifyNoInteractions(identities);
    }

    @Test
    void validatesChangedIdentityEvenForAnAlreadyLinkedCustomer() {
        var customer = lockedCustomer(DocumentType.DNI, "12345678Z");
        customer.linkSaasIdentity(UUID.randomUUID(), 1);

        assertThatThrownBy(() -> service().update(customer.getId(), command(DocumentType.DNI, "00000001A")))
                .isInstanceOf(CustomerIdentityException.class)
                .hasMessage("CUSTOMER_DOCUMENT_INVALID");

        assertThat(customer.getDocumentNumber()).isEqualTo("12345678Z");
        verifyNoInteractions(identities);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" -- ", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void invalidUpdatedNumberUsesTheStructuredIdentityError(String number) {
        var customer = lockedCustomer(DocumentType.PASAPORTE, "PASS123");

        assertThatThrownBy(() -> service().update(customer.getId(), command(DocumentType.PASAPORTE, number)))
                .isInstanceOf(CustomerIdentityException.class)
                .hasMessage("CUSTOMER_DOCUMENT_INVALID");

        assertThat(customer.getDocumentNumber()).isEqualTo("PASS123");
        verifyNoInteractions(identities);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" -- ", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void invalidBatchNumberUsesTheStructuredIdentityErrorBeforeSortingOrReserving(String number) {
        assertThatThrownBy(() -> service().createBatch(List.of(
                command(DocumentType.PASAPORTE, "PASS123"), command(DocumentType.PASAPORTE, number))))
                .isInstanceOf(CustomerIdentityException.class)
                .hasMessage("CUSTOMER_DOCUMENT_INVALID");

        verifyNoInteractions(identities, codes);
        verify(customers, never()).saveAll(any());
    }

    @Test
    void changingOnlyTheDocumentTypeStillRequiresCentralApprovalAndAcceptsItsOwnNumber() {
        var customer = lockedCustomer(DocumentType.DNI, "12345678Z");
        customer.linkSaasIdentity(UUID.randomUUID(), 1);
        when(customers.findByCompanyAndNormalizedDocument(company.getId(), "12345678Z"))
                .thenReturn(Optional.of(customer));
        var approval = allowReservation(customer, DocumentType.NIF, "12345678Z");

        var updated = service().update(customer.getId(), command(DocumentType.NIF, "12345678Z"));

        assertThat(updated.documentType()).isEqualTo(DocumentType.NIF);
        verify(identities).complete(approval, customer);
    }

    @Test
    void unavailableSaasDoesNotRegisterHistoricalCustomerOrChangeItsIdentity() {
        var customer = lockedCustomer(DocumentType.DNI, "12345678Z");
        when(identities.reserve(eq(company.getId()), eq(store.getId()), eq(customer),
                eq(CustomerDocumentIdentity.validate(DocumentType.DNI, "12345678Z")), any(Customer.class)))
                .thenThrow(CustomerIdentityException.unavailable());

        assertThatThrownBy(() -> service().registerIdentity(customer.getId()))
                .isInstanceOf(CustomerIdentityException.class)
                .hasMessage("CUSTOMER_IDENTITY_SAAS_UNAVAILABLE");

        assertThat(customer.getSaasCustomerId()).isNull();
        assertThat(customer.getDocumentNumber()).isEqualTo("12345678Z");
        verify(identities, never()).complete(any(), any());
        verify(customers, never()).save(any());
    }

    @Test
    void explicitlyRegistersHistoricalCustomerWithItsCurrentIdentityAndOriginalLocalId() {
        var customer = lockedCustomer(DocumentType.CIF, "B12345674");
        when(customers.findByCompanyAndNormalizedDocument(company.getId(), "B12345674"))
                .thenReturn(Optional.of(customer));
        var approval = allowReservation(customer, DocumentType.NIF, "B12345674");

        var registered = service().registerIdentity(customer.getId());

        assertThat(registered.id()).isEqualTo(customer.getId());
        assertThat(registered.documentType()).isEqualTo(DocumentType.NIF);
        assertThat(registered.documentNumber()).isEqualTo("B12345674");
        assertThat(registered.clientId()).isEqualTo("C-001-000001");
        verify(identities).complete(approval, customer);
        verify(customers, never()).save(any());
        verifyNoInteractions(codes);
    }

    @Test
    void historicalRegistrationRejectsInvalidIdentityBeforeContactingSaas() {
        var customer = lockedCustomer(DocumentType.NIF, "INVALID");

        assertThatThrownBy(() -> service().registerIdentity(customer.getId()))
                .isInstanceOf(CustomerIdentityException.class)
                .hasMessage("CUSTOMER_DOCUMENT_INVALID");

        verifyNoInteractions(identities);
        verify(customers, never()).findByCompanyAndNormalizedDocument(any(), any());
    }

    @Test
    void historicalRegistrationRejectsAnotherCustomerWithTheSameNormalizedNumber() {
        var customer = lockedCustomer(DocumentType.NIF, "12345678Z");
        var other = existing(DocumentType.DNI, "12345678Z");
        when(customers.findByCompanyAndNormalizedDocument(company.getId(), "12345678Z"))
                .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service().registerIdentity(customer.getId()))
                .isInstanceOf(CustomerIdentityException.class)
                .hasMessage("CUSTOMER_DOCUMENT_DUPLICATE");

        verifyNoInteractions(identities);
    }

    @Test
    void alreadyRegisteredCustomerDoesNotReserveAgain() {
        var customer = lockedCustomer(DocumentType.DNI, "12345678Z");
        customer.linkSaasIdentity(UUID.randomUUID(), 1);

        var result = service().registerIdentity(customer.getId());

        assertThat(result.id()).isEqualTo(customer.getId());
        verifyNoInteractions(identities, codes);
        verify(customers, never()).findByCompanyAndNormalizedDocument(any(), any());
    }

    private Customer existing(DocumentType type, String number) {
        var customer = new Customer(company, "Original", type, number, null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        customer.assignClientCode(store.getId(), "C-001-000001");
        return customer;
    }

    private Customer lockedCustomer(DocumentType type, String number) {
        var customer = existing(type, number);
        when(customers.findLockedByIdAndCompanyId(customer.getId(), company.getId()))
                .thenReturn(Optional.of(customer));
        return customer;
    }

    private CustomerIdentityCoordinator.Approval allowReservation(Customer existing, DocumentType type, String number) {
        var identity = CustomerDocumentIdentity.validate(type, number);
        var operationId = UUID.randomUUID();
        var customerId = existing == null ? UUID.randomUUID() : existing.getId();
        var centralId = existing == null || existing.getSaasCustomerId() == null
                ? UUID.randomUUID() : existing.getSaasCustomerId();
        var operation = new CustomerIdentityOperations.Operation(operationId, company.getId(), store.getId(),
                customerId, existing == null, type, number,
                existing == null ? null : existing.getSaasCustomerId(),
                existing == null ? null : existing.getSaasIdentityRevision(), "PENDING");
        var reservation = new CustomerIdentitySaasClient.Reservation(operationId, centralId, 1L, type.name(), number);
        var approval = new CustomerIdentityCoordinator.Approval(operation, reservation, Map.of());
        when(identities.reserve(eq(company.getId()), eq(store.getId()), eq(existing), eq(identity), any(Customer.class)))
                .thenReturn(approval);
        return approval;
    }

    private static CustomerService.CustomerCommand command(DocumentType type, String number) {
        return new CustomerService.CustomerCommand("Updated", type, number, null,
                null, null, null, BigDecimal.ZERO, false, null);
    }

    private CustomerService service() {
        var organization = new CurrentOrganization(stores, users);
        return new CustomerService(
                customers, movements, new PartyContext(organization), codes,
                members, memberLoyalty,
                Clock.fixed(Instant.parse("2026-06-08T10:00:00Z"), ZoneOffset.UTC), identities);
    }
}
