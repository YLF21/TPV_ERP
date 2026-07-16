package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLineCommand;
import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.sync.SyncOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberLoyaltyServiceTest {

    @Mock MemberRepository members;
    @Mock MemberCategoryRepository categories;
    @Mock MemberSettingsRepository settingsRepository;
    @Mock MemberMovementRepository movements;
    @Mock MemberBalanceLotRepository lots;
    @Mock MemberBalanceLotConsumptionRepository lotConsumptions;
    @Mock MemberCardDeliveryRepository cardDeliveries;
    @Mock MemberSmtpSettingsRepository smtpSettings;
    @Mock CommercialContactChannelRepository channels;
    @Mock SyncOutboxService syncOutbox;
    @Mock PartyContext context;

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
    void accruesPointsBalanceLotAndSyncFromPaidSalesAmount() {
        var company = PartyTestData.company();
        var store = PartyTestData.store(company);
        var user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", LocalDate.of(2026, 7, 2));
        var settings = new MemberSettings(company);
        settings.update(new BigDecimal("10.00"), BalanceExpirationPolicy.NO_CADUCA,
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

        assertThat(member.getMemberPoints()).isEqualTo(25);
        assertThat(member.getMemberBalance()).isEqualByComparingTo("2.59");
        verify(lots).save(any(MemberBalanceLot.class));
        verify(syncOutbox, org.mockito.Mockito.times(2)).enqueue(any());
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
        config.update(BigDecimal.ZERO, BalanceExpirationPolicy.NO_CADUCA, BigDecimal.ONE,
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

        var result = service().cardDeliveries(MemberCardDeliveryStatus.PENDIENTE);

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
                BigDecimal.ZERO, BalanceExpirationPolicy.NO_CADUCA, BigDecimal.ONE,
                true, true, MemberCardCodeFormat.QR, "Bienvenido", "Codigo {memberId}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("message.member_welcome.smtp_required");
    }

    private MemberLoyaltyService service() {
        return new MemberLoyaltyService(
                members, categories, settingsRepository, movements, lots, lotConsumptions,
                cardDeliveries, smtpSettings, channels,
                syncOutbox, context,
                Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC));
    }
}
