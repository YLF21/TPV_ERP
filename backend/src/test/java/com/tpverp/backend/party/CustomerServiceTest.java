package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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

        var created = service().create(new CustomerService.CustomerCommand(
                "Cliente", DocumentType.NIF, " 12ab ", null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO));

        assertThat(created.documentNumber()).isEqualTo("12AB");
        verify(customers).save(any(Customer.class));
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
                customers, movements, new PartyContext(organization),
                Clock.fixed(Instant.parse("2026-06-08T10:00:00Z"), ZoneOffset.UTC));
    }
}
