package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.application.CorePermissionBootstrap;
import com.tpverp.backend.security.domain.Permission;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class DiscountAuthorizationServiceTest {

    @Mock UserAccountRepository users;
    @Mock CurrentOrganization organization;
    @Mock PasswordEncoder passwords;

    private DiscountAuthorizationService service;
    private Store store;
    private Company company;
    private UserAccount operator;
    private UserAccount manager;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        var address = Map.of("linea1", "Calle 1", "ciudad", "Madrid", "codigoPostal", "28001", "provincia", "Madrid", "pais", "ES");
        company = new Company("B00000000", "Empresa", address);
        store = new Store(company, "Tienda", address, "hash", "Europe/Madrid", "EUR", "es-ES");
        var permission = new Permission(CorePermissionBootstrap.APLICAR_DESCUENTO, "discount", "VENTA");
        var operatorRole = new Role(store, "CAJERO");
        operatorRole.conceder(permission);
        operator = new UserAccount(store, "CAJERO", "operator-hash", operatorRole);
        operator.changeMaxDiscountPercent(new BigDecimal("5.00"));
        var managerRole = new Role(store, "RESPONSABLE");
        managerRole.conceder(permission);
        manager = new UserAccount(store, "RESPONSABLE", "manager-hash", managerRole);
        manager.changeMaxDiscountPercent(new BigDecimal("25.00"));
        authentication = new UsernamePasswordAuthenticationToken(
                operator, null, List.of(new SimpleGrantedAuthority(CorePermissionBootstrap.APLICAR_DESCUENTO)));
        when(organization.currentCompany()).thenReturn(company);
        lenient().when(organization.currentStore()).thenReturn(store);
        service = new DiscountAuthorizationService(
                users, organization, passwords,
                Clock.fixed(Instant.parse("2026-07-21T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void managerGrantAllowsOperatorToExceedOwnLimit() {
        when(users.findByEmpresaIdAndNombre(company.getId(), "RESPONSABLE")).thenReturn(Optional.of(manager));
        when(passwords.matches("1234", "manager-hash")).thenReturn(true);

        var grant = service.authorize("responsable", "1234", new BigDecimal("20.00"), authentication);

        assertThat(grant.managerName()).isEqualTo("RESPONSABLE");
        assertThatCode(() -> service.enforce(new BigDecimal("20.00"), grant.token(), authentication))
                .doesNotThrowAnyException();
    }

    @Test
    void grantCannotApproveMoreThanManagersLimit() {
        when(users.findByEmpresaIdAndNombre(company.getId(), "RESPONSABLE")).thenReturn(Optional.of(manager));
        when(passwords.matches("1234", "manager-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.authorize(
                "responsable", "1234", new BigDecimal("30.00"), authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("limite del responsable");
    }

    @Test
    void tokenIsBoundToOperatorAndStore() {
        when(users.findByEmpresaIdAndNombre(company.getId(), "RESPONSABLE")).thenReturn(Optional.of(manager));
        when(passwords.matches("1234", "manager-hash")).thenReturn(true);
        var grant = service.authorize("responsable", "1234", new BigDecimal("20.00"), authentication);
        var other = new UserAccount(store, "OTRO", "hash", operator.getRol());
        other.changeMaxDiscountPercent(new BigDecimal("5.00"));
        var otherAuthentication = new UsernamePasswordAuthenticationToken(
                other, null, List.of(new SimpleGrantedAuthority(CorePermissionBootstrap.APLICAR_DESCUENTO)));

        assertThatThrownBy(() -> service.enforce(new BigDecimal("20.00"), grant.token(), otherAuthentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("autorizacion vigente");
    }
}
