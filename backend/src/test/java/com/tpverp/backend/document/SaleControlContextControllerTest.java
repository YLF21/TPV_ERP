package com.tpverp.backend.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SaleControlContextController.class)
@Import(SaleControlContextControllerTest.MethodSecurityConfiguration.class)
class SaleControlContextControllerTest {

    private static final String PATH = "/api/v1/sale-line-deletions/context";
    private static final UUID STORE_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID TERMINAL_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-17T12:34:56.123Z");

    @Autowired private MockMvc mvc;
    @MockitoBean private CurrentOrganization organization;
    @MockitoBean private CurrentTerminal terminal;
    @MockitoBean private Clock clock;

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "VENTA", "GESTION_VENTAS"})
    void returnsOnlyTheAuthenticatedScopeAndServerTimeForAuthorizedUsers(String authority) throws Exception {
        var store = mock(Store.class);
        var account = mock(UserAccount.class);
        when(store.getId()).thenReturn(STORE_ID);
        when(account.getId()).thenReturn(USER_ID);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(any())).thenReturn(account);
        when(terminal.terminalId(any())).thenReturn(TERMINAL_ID);
        when(clock.instant()).thenReturn(NOW);

        mvc.perform(get(PATH)
                        .with(user("seller").authorities(new SimpleGrantedAuthority(authority)))
                        // Client-provided identifiers cannot select a different outbox identity.
                        .param("storeId", UUID.randomUUID().toString())
                        .param("userId", UUID.randomUUID().toString())
                        .param("terminalId", UUID.randomUUID().toString())
                        .param("serverTime", "2040-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.storeId").value(STORE_ID.toString()))
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.terminalId").value(TERMINAL_ID.toString()))
                .andExpect(jsonPath("$.serverTime").value(NOW.toString()))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        verify(organization).currentStore();
        verify(organization).currentUser(argThat(authentication -> authentication.getName().equals("seller")));
        verify(terminal).terminalId(argThat(authentication -> authentication.getName().equals("seller")));
        verify(clock).instant();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_USER", "APP_GESTION_ACCESS", "CONTROL_ALERTS_READ", "TICKETS_CREATE"})
    void rejectsUnrelatedPermissionsBeforeResolvingAnyIdentity(String authority) throws Exception {
        mvc.perform(get(PATH).with(user("other").authorities(new SimpleGrantedAuthority(authority))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(organization, terminal);
    }

    @Test
    void rejectsRequestsWithoutAnAuthenticatedSession() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());

        verifyNoInteractions(organization, terminal);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {}
}
