package com.tpverp.backend.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.MemberLoyaltyService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class SafeManagementRetirementServiceTest {

    @Mock EntityManager entityManager;
    @Mock JdbcTemplate jdbc;
    @Mock CurrentOrganization organization;
    @Mock AuditService audit;
    @Mock MemberLoyaltyService memberLoyalty;
    @Mock Store store;
    @Mock Product product;
    @Mock TypedQuery<Product> productQuery;

    private SafeManagementRetirementService service;
    private UUID id;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        service = new SafeManagementRetirementService(entityManager, jdbc, organization, audit, memberLoyalty);
        id = UUID.randomUUID();
        storeId = UUID.randomUUID();
        lenient().when(organization.currentStore()).thenReturn(store);
        lenient().when(store.getId()).thenReturn(storeId);
        lenient().when(entityManager.createQuery(anyString(), eq(Product.class))).thenReturn(productQuery);
        lenient().when(productQuery.setParameter(anyString(), any())).thenReturn(productQuery);
        lenient().when(productQuery.setLockMode(any(LockModeType.class))).thenReturn(productQuery);
        lenient().when(productQuery.getResultStream()).thenReturn(Stream.of(product));
        lenient().when(product.getId()).thenReturn(id);
        lenient().when(product.getStoreId()).thenReturn(storeId);
        lenient().doReturn(List.of()).when(jdbc).query(
                anyString(), any(PreparedStatementSetter.class), any(RowMapper.class));
    }

    @Test
    void rejectsStaleVersionBeforeCheckingReferencesOrMutating() {
        when(product.getVersion()).thenReturn(7L);

        assertThatThrownBy(() -> service.retire(
                SafeManagementRetirementService.EntityType.PRODUCT, id, 6L))
                .isInstanceOf(SafeManagementRetirementService.SafeRetirementStaleStateException.class);

        verify(entityManager).createQuery(anyString(), eq(Product.class));
        org.mockito.Mockito.verify(entityManager, org.mockito.Mockito.never()).remove(any());
    }

    @Test
    void physicallyDeletesAnUnusedProductAndAuditsTheOutcome() {
        when(product.getVersion()).thenReturn(2L);
        when(product.isActive()).thenReturn(true);
        when(product.getCode()).thenReturn("P-1");
        when(product.getImageId()).thenReturn(null);

        SafeRetirementResult result = service.retire(
                SafeManagementRetirementService.EntityType.PRODUCT, id, 2L);

        assertThat(result.outcome()).isEqualTo(RetirementOutcome.HARD_DELETED);
        assertThat(result.reasonCodes()).isEmpty();
        verify(entityManager).remove(product);
        verify(entityManager).flush();
        verify(audit).record(eq("SAFE_MANAGEMENT_RETIREMENT"),
                eq(com.tpverp.backend.audit.AuditResult.EXITO), any());
    }

    @Test
    void protectsTheSystemProductByDeactivatingIt() {
        when(product.getVersion()).thenReturn(3L);
        when(product.isActive()).thenReturn(true);
        when(product.getCode()).thenReturn("0");
        when(product.getImageId()).thenReturn(null);

        SafeRetirementImpact impact = service.impact(
                SafeManagementRetirementService.EntityType.PRODUCT, id);

        assertThat(impact.outcomeIfConfirmed()).isEqualTo(RetirementOutcome.DEACTIVATED);
        assertThat(impact.reasonCodes()).containsExactly("PROTECTED_SYSTEM_PRODUCT");
        assertThat(impact.executable()).isFalse();
    }

    @Test
    void refusesToMutateTheSystemProduct() {
        when(product.getVersion()).thenReturn(3L);
        when(product.isActive()).thenReturn(true);
        when(product.getCode()).thenReturn("0");
        when(product.getImageId()).thenReturn(null);

        assertThatThrownBy(() -> service.retire(
                SafeManagementRetirementService.EntityType.PRODUCT, id, 3L))
                .isInstanceOf(SafeManagementRetirementService.ProtectedSystemProductException.class);
        org.mockito.Mockito.verify(product, org.mockito.Mockito.never()).deactivate();
        org.mockito.Mockito.verify(entityManager, org.mockito.Mockito.never()).remove(any());
    }

    @Test
    void validatesBoundedPageSizeWithoutRunningAnUnboundedQuery() {
        assertThatThrownBy(() -> service.page(
                SafeManagementRetirementService.EntityType.PRODUCT, 101, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }
}
