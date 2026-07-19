package com.tpverp.backend.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class ControlAlertDetectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Mock private ControlRuleRepository rules;
    @Mock private ControlEventRepository events;
    @Mock private ControlAlertRepository alerts;
    @Mock private ControlAlertHistoryRepository history;
    @Mock private ProductRepository products;
    @Mock private CurrentOrganization organization;

    private ControlAlertDetectionService service;
    private Store store;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES");
        store = new Store(new Company("B00000000", "Company", address),
                "Store", address, "hash", "Atlantic/Canary", "EUR", "es-ES");
        user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        when(organization.currentUser(any())).thenReturn(user);
        lenient().when(rules.findAllByStoreIdAndTypeAndActiveTrue(
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.any(ControlAlertType.class))).thenReturn(List.of());
        lenient().when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(alerts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new ControlAlertDetectionService(
                rules, events, alerts, history, products, organization,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsAlertOnlyWhenOriginalManualDiscountIsStrictlyAboveThreshold() {
        var document = confirmedTicket();
        var rule = discountRule("10");
        when(rules.findAllByStoreIdAndTypeAndActiveTrue(
                store.getId(), ControlAlertType.MANUAL_DISCOUNT_OVER_PERCENT))
                .thenReturn(List.of(rule));

        service.detectConfirmedDocument(document,
                new ControlAlertDetectionService.ManualDiscountSnapshot(
                        BigDecimal.ZERO,
                        List.of(new ControlAlertDetectionService.ManualLineDiscount(
                                1, document.getLineas().getFirst().getProductoId(), new BigDecimal("10")))),
                UUID.randomUUID(), authentication());

        verify(events, never()).save(any());

        service.detectConfirmedDocument(document,
                new ControlAlertDetectionService.ManualDiscountSnapshot(
                        new BigDecimal("10.01"), List.of()),
                UUID.randomUUID(), authentication());

        var event = ArgumentCaptor.forClass(ControlEvent.class);
        verify(events).save(event.capture());
        assertThat(event.getValue().getType())
                .isEqualTo(ControlAlertType.MANUAL_DISCOUNT_OVER_PERCENT);
        assertThat(event.getValue().getDocumentId()).isEqualTo(document.getId());
        verify(history).save(any(ControlAlertHistory.class));
    }

    @Test
    void recordsAllowedInactiveProductSaleAgainstCurrentCatalog() {
        var document = confirmedTicket();
        var rule = new ControlRule(store.getId(), ControlAlertType.INACTIVE_PRODUCT_SOLD,
                true, Map.of(), user.getId(), NOW);
        var product = org.mockito.Mockito.mock(Product.class);
        when(product.getId()).thenReturn(document.getLineas().getFirst().getProductoId());
        when(product.isActive()).thenReturn(false);
        when(product.getCode()).thenReturn("P-1");
        when(product.getName()).thenReturn("Producto");
        when(rules.findAllByStoreIdAndTypeAndActiveTrue(
                store.getId(), ControlAlertType.INACTIVE_PRODUCT_SOLD)).thenReturn(List.of(rule));
        when(products.findAllByStoreIdAndIdIn(any(), any())).thenReturn(List.of(product));

        service.detectConfirmedDocument(document, null, null, authentication());

        var event = ArgumentCaptor.forClass(ControlEvent.class);
        verify(events).save(event.capture());
        assertThat(event.getValue().getType()).isEqualTo(ControlAlertType.INACTIVE_PRODUCT_SOLD);
        assertThat(event.getValue().getData().get("products")).asList().hasSize(1);
    }

    @Test
    void productDiscountRuleUsesOnlyOriginalManualLineDiscounts() {
        var document = confirmedTicket();
        var rule = new ControlRule(store.getId(), ControlAlertType.PRODUCT_DISCOUNT_APPLIED,
                true, Map.of(), user.getId(), NOW);
        when(rules.findAllByStoreIdAndTypeAndActiveTrue(
                store.getId(), ControlAlertType.PRODUCT_DISCOUNT_APPLIED)).thenReturn(List.of(rule));

        service.detectConfirmedDocument(
                document,
                new ControlAlertDetectionService.ManualDiscountSnapshot(
                        BigDecimal.ZERO,
                        List.of(new ControlAlertDetectionService.ManualLineDiscount(
                                1, document.getLineas().getFirst().getProductoId(), new BigDecimal("5")))),
                UUID.randomUUID(),
                authentication());

        var event = ArgumentCaptor.forClass(ControlEvent.class);
        verify(events).save(event.capture());
        assertThat(event.getValue().getType()).isEqualTo(ControlAlertType.PRODUCT_DISCOUNT_APPLIED);
        assertThat(event.getValue().getData().get("discountedLines")).asList().hasSize(1);
    }

    @Test
    void productDiscountRuleIgnoresRepricedDocumentWithoutOriginalManualSnapshot() {
        var document = confirmedTicket();

        service.detectConfirmedDocument(document, null, UUID.randomUUID(), authentication());

        verify(events, never()).save(any());
    }

    @Test
    void createsOnlyOneAlertWhenConsecutiveDeletionThresholdIsReached() {
        when(organization.currentStore()).thenReturn(store);
        var rule = new ControlRule(store.getId(), ControlAlertType.CONSECUTIVE_LINE_DELETIONS,
                true,
                Map.of("minimumCount", 3), user.getId(), NOW);
        when(rules.findAllByStoreIdAndTypeAndActiveTrue(
                store.getId(), ControlAlertType.CONSECUTIVE_LINE_DELETIONS))
                .thenReturn(List.of(rule));
        var saleOperationId = UUID.randomUUID();
        var terminalId = UUID.randomUUID();
        var deleted = new com.tpverp.backend.document.SaleLineDeletionView(
                UUID.randomUUID(), store.getId(), terminalId, user.getId(), NOW, "LINEA",
                UUID.randomUUID(), "P-1", "Producto", 1,
                new BigDecimal("10.00"), new BigDecimal("10.00"));

        service.detectConsecutiveLineDeletions(
                saleOperationId, 2, List.of(deleted), terminalId, authentication());

        verify(events, never()).save(any());

        service.detectConsecutiveLineDeletions(
                saleOperationId, 3, List.of(deleted), terminalId, authentication());

        var event = ArgumentCaptor.forClass(ControlEvent.class);
        verify(events).save(event.capture());
        assertThat(event.getValue().getType())
                .isEqualTo(ControlAlertType.CONSECUTIVE_LINE_DELETIONS);
        assertThat(event.getValue().getSourceId()).isEqualTo(saleOperationId);
        assertThat(event.getValue().getData())
                .containsEntry("minimumCount", 3)
                .containsEntry("deletionCount", 3);
    }

    private ControlRule discountRule(String threshold) {
        return new ControlRule(store.getId(), ControlAlertType.MANUAL_DISCOUNT_OVER_PERCENT,
                true, Map.of("thresholdPercent", threshold), user.getId(), NOW);
    }

    private CommercialDocument confirmedTicket() {
        var document = new CommercialDocument(
                store.getId(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2026, 7, 18), user.getId(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, 1, "P-1", "Producto", "VENTA",
                new BigDecimal("10"), BigDecimal.ZERO, true, "IGIC", new BigDecimal("7")));
        document.confirm("T-1", user.getId(), NOW, false);
        return document;
    }

    private static UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("ADMIN", "token");
    }
}
