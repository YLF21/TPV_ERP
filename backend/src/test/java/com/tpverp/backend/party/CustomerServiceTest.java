package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.security.domain.Rol;
import com.tpverp.backend.security.domain.Usuario;
import com.tpverp.backend.security.domain.UsuarioRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock CustomerRepository customers;
    @Mock MemberBalanceMovementRepository movements;
    @Mock TiendaRepository stores;
    @Mock UsuarioRepository users;
    @Mock PartyCodeAllocator codes;

    private Empresa company;
    private Tienda store;
    private Usuario user;

    @BeforeEach
    void setUp() {
        company = PartyTestData.company();
        store = PartyTestData.store(company);
        user = new Usuario(store, "CAJA", "hash", new Rol(store, "VENDEDOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("CAJA", "token"));
        when(stores.findAll()).thenReturn(List.of(store));
    }

    @Test
    void createsCustomerInCurrentStoreCompanyAndNormalizesDocument() {
        when(customers.findByCompanyIdAndDocumentTypeAndDocumentNumber(
                PartyTestData.id(company), DocumentType.NIF, "12AB"))
                .thenReturn(Optional.empty());
        when(customers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(codes.nextClient(store)).thenReturn("C-001-000001");

        var created = service().create(new CustomerService.CustomerCommand(
                "Cliente", DocumentType.NIF, " 12ab ", null,
                null, null, null, BigDecimal.ZERO, false, null));

        assertThat(created.documentNumber()).isEqualTo("12AB");
        assertThat(created.codeClient()).isEqualTo("C-001-000001");
        assertThat(created.isMember()).isFalse();
        verify(customers).save(any(Customer.class));
    }

    @Test
    void activaMemberYAsignaCodigoFechaYNumeroLibre() {
        when(customers.findByCompanyIdAndDocumentTypeAndDocumentNumber(
                PartyTestData.id(company), DocumentType.NIF, "99Z"))
                .thenReturn(Optional.empty());
        when(customers.findByCompanyIdAndNumMember(PartyTestData.id(company), "EXT/2026 #1"))
                .thenReturn(Optional.empty());
        when(customers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(codes.nextClient(store)).thenReturn("C-001-000001");
        when(codes.nextMember(store)).thenReturn("M-001-000001");

        var created = service().create(new CustomerService.CustomerCommand(
                "Member", DocumentType.NIF, "99z", null,
                null, null, null, BigDecimal.ZERO, true, " EXT/2026 #1 "));

        assertThat(created.isMember()).isTrue();
        assertThat(created.codeMember()).isEqualTo("M-001-000001");
        assertThat(created.numMember()).isEqualTo("EXT/2026 #1");
        assertThat(created.memberSince()).isEqualTo(java.time.LocalDate.of(2026, 6, 8));
        assertThat(created.rate()).isEqualTo(CustomerRate.MEMBER);
    }

    @Test
    void importacionMasivaAsignaBloqueOrdenadoPorNif() {
        when(customers.findByCompanyIdAndDocumentTypeAndDocumentNumber(
                eq(PartyTestData.id(company)), eq(DocumentType.NIF), any()))
                .thenReturn(Optional.empty());
        when(codes.nextClients(store, 2))
                .thenReturn(List.of("C-001-000001", "C-001-000002"));
        when(customers.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var zeta = new CustomerService.CustomerCommand(
                "Zeta", DocumentType.NIF, "Z9", null,
                null, null, null, BigDecimal.ZERO, false, null);
        var alfa = new CustomerService.CustomerCommand(
                "Alfa", DocumentType.NIF, "A1", null,
                null, null, null, BigDecimal.ZERO, false, null);

        var imported = service().createBatch(List.of(zeta, alfa));

        assertThat(imported)
                .extracting(CustomerService.CustomerView::documentNumber,
                        CustomerService.CustomerView::codeClient)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A1", "C-001-000001"),
                        org.assertj.core.groups.Tuple.tuple("Z9", "C-001-000002"));
    }

    @Test
    void reactivarMemberConservaCodigoSinConsumirOtroNumero() {
        var customer = new Customer(
                company, "Member", DocumentType.NIF, "M1", null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        customer.assignClientCode(store.getId(), "C-001-000001");
        customer.activateMember("M-001-000001", java.time.LocalDate.of(2026, 5, 1));
        customer.assignMemberStore(store.getId());
        customer.deactivateMember();
        when(customers.findByIdAndCompanyId(customer.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.of(customer));

        var updated = service().update(customer.getId(), new CustomerService.CustomerCommand(
                "Member", DocumentType.NIF, "M1", null,
                null, null, null, BigDecimal.ZERO, true, null));

        assertThat(updated.codeMember()).isEqualTo("M-001-000001");
        assertThat(updated.memberSince()).isEqualTo(java.time.LocalDate.of(2026, 5, 1));
        verify(codes, never()).nextMember(any());
    }

    @Test
    void recordsManualMemberBalanceMovementWithAuthenticatedUser() {
        var customer = new Customer(
                company, "Member", DocumentType.NIF, "1", null,
                null, null, null, CustomerRate.MEMBER, BigDecimal.ZERO);
        when(customers.findByIdAndCompanyId(customer.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.of(customer));
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
                company, "Cliente", DocumentType.NIF, "1", null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        when(customers.findByIdAndCompanyId(customer.getId(), PartyTestData.id(company)))
                .thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service().validateFiscalData(customer.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fiscales");
    }

    private CustomerService service() {
        var organization = new CurrentOrganization(stores, users);
        return new CustomerService(
                customers, movements, new PartyContext(organization), codes,
                Clock.fixed(Instant.parse("2026-06-08T10:00:00Z"), ZoneOffset.UTC));
    }
}
