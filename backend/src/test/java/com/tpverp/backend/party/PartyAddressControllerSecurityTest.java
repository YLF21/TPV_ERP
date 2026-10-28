package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PartyAddressControllerSecurityTest.Config.class)
class PartyAddressControllerSecurityTest {
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean PartyAddressController controller(PartyAddressService service) { return new PartyAddressController(service); }
    }
    @MockitoBean PartyAddressService service;
    @Autowired PartyAddressController controller;

    @Test @WithMockUser(authorities = "VENTA")
    void unrelatedPermissionCannotReadAddresses() {
        assertThatThrownBy(() -> controller.suggestions("mayor")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.resolve("1", "portal")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test @WithMockUser(authorities = "CUSTOMERS_WRITE")
    void customerEditorCanSearch() { assertSearch(); }

    @Test @WithMockUser(authorities = "SUPPLIERS_WRITE")
    void supplierEditorCanSearch() { assertSearch(); }

    @Test @WithMockUser(authorities = "GESTION_CLIENTE_PROVEEDOR")
    void partyManagerCanSearch() { assertSearch(); }

    @Test @WithMockUser(authorities = "GESTION_ALMACEN")
    void warehouseManagerCanSearch() { assertSearch(); }

    @Test @WithMockUser(roles = "ADMIN")
    void administratorCanResolve() {
        controller.resolve("1", "portal");
        verify(service).resolve("1", "portal");
    }

    private void assertSearch() {
        var expected = new PartyAddressService.Suggestions(true, List.of());
        when(service.suggestions("mayor")).thenReturn(expected);
        assertThat(controller.suggestions("mayor")).isEqualTo(expected);
    }
}
