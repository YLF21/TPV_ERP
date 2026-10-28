package com.tpverp.saas.customer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.sync.SaasSyncEvent;
import com.tpverp.saas.sync.SyncOperation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class CustomerAddressBookProjectorTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final UUID company = UUID.randomUUID(), store = UUID.randomUUID(), installation = UUID.randomUUID();
    final UUID local = UUID.randomUUID(), customer = UUID.randomUUID(), operation = UUID.randomUUID();
    final SaasSyncEvent event = mock(SaasSyncEvent.class, RETURNS_DEEP_STUBS);
    final CustomerAddressBookService service = new CustomerAddressBookService(mock(jakarta.persistence.EntityManager.class),
            jdbc, mock(SaasInstallationRepository.class), mock(InstallationAuthenticator.class), new ObjectMapper());

    @BeforeEach void setup() {
        when(event.getInstallation().isActive()).thenReturn(true);
        when(event.getInstallation().getId()).thenReturn(installation);
        when(event.getInstallation().getCompany().getId()).thenReturn(company);
        when(event.getInstallation().getStore().getId()).thenReturn(store);
        when(event.getCompany().getId()).thenReturn(company);
        when(event.getStore().getId()).thenReturn(store);
        when(event.getEntityId()).thenReturn(local);
        when(event.getEntityType()).thenReturn("CUSTOMER_ADDRESSES");
        when(event.getOperation()).thenReturn(SyncOperation.ACTUALIZAR);
        when(jdbc.queryForObject(contains("select exists"), eq(Boolean.class), eq(installation), eq(company), eq(local), eq(customer)))
                .thenReturn(true);
    }

    @Test void staleRevisionPersistsTerminalConflictWithoutOverwritingMaster() {
        service.project(event, payload());
        verify(jdbc).update(contains("update saas_customer_address_book"), anyString(), eq(company), eq(customer), eq(1L));
        verify(jdbc).update(contains("insert into saas_customer_address_operation"), eq(operation), eq(company), eq(customer),
                eq(installation), eq(store), eq(local), eq(1L), anyString(), eq("CONFLICT"));
        verify(jdbc, never()).update(contains("update saas_erp_customer"), any(Object[].class));
    }

    @Test void replayOfAppliedOperationIsReadOnlyButChangedHashIsRejected() {
        when(jdbc.update(contains("update saas_customer_address_book"), anyString(), eq(company), eq(customer), eq(1L))).thenReturn(1);
        service.project(event, payload());
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("insert into saas_customer_address_operation"), eq(operation), eq(company), eq(customer),
                eq(installation), eq(store), eq(local), eq(1L), hash.capture(), eq("APPLIED"));
        when(jdbc.queryForList(contains("select * from saas_customer_address_operation"), eq(operation))).thenReturn(List.of(
                Map.of("installation_id", installation, "store_id", store, "company_id", company,
                        "local_customer_id", local, "customer_id", customer, "payload_hash", hash.getValue(), "status", "APPLIED")));
        clearInvocations(jdbc);
        service.project(event, payload());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        var changed = new java.util.LinkedHashMap<>(payload()); changed.put("expectedRevision", 2L);
        assertThatThrownBy(() -> service.project(event, changed)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void wrongStoreAndMissingLinkNeverReachWrites() {
        when(event.getStore().getId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.project(event, payload())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
        when(event.getStore().getId()).thenReturn(store);
        when(jdbc.queryForObject(contains("select exists"), eq(Boolean.class), eq(installation), eq(company), eq(local), eq(customer)))
                .thenReturn(false);
        assertThatThrownBy(() -> service.project(event, payload())).isInstanceOf(ResponseStatusException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private Map<String, Object> payload() {
        return Map.of("operationId", operation.toString(), "customerId", customer.toString(), "expectedRevision", 1L,
                "addresses", List.of(Map.of("id", customer.toString(), "address", Map.of("city", "Madrid"))));
    }
}
