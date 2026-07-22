package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class ParkedSaleServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-17T10:00:00Z");

    @Mock
    private ParkedSaleRepository repository;
    @Mock
    private ParkedSaleRecoveryRepository recoveries;
    @Mock
    private CurrentOrganization organization;

    private ParkedSaleService service;
    private Store store;
    private Company company;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        company = new Company("B00000000", "Company", address);
        store = new Store(
                company,
                "Store", address, "hash", "Atlantic/Canary", "EUR", "es-ES");
        user = new UserAccount(store, "ADMIN", "hash", new Role(store, "ADMIN"));
        when(organization.currentStore()).thenReturn(store);
        lenient().when(organization.currentCompany()).thenReturn(company);
        when(organization.currentUser(any())).thenReturn(user);
        service = new ParkedSaleService(
                repository, recoveries, organization,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void parksSaleWithoutTicketNumberAndKeepsItUntilRestoreIsAcknowledged() {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        var customerId = UUID.randomUUID();

        var parked = service.park(command(customerId), "Cliente vuelve en 5 min", auth());
        when(repository.findByIdAndTiendaId(parked.getId(), store.getId()))
                .thenReturn(Optional.of(parked));

        var opened = service.open(parked.getId());

        assertThat(parked.getTicketNumber()).isNull();
        assertThat(parked.getCustomerId()).isEqualTo(customerId);
        assertThat(parked.getComment()).isEqualTo("Cliente vuelve en 5 min");
        assertThat(parked.getTotal()).isEqualByComparingTo("10.00");
        assertThat(opened.document().tipo()).isEqualTo(CommercialDocumentType.TICKET);
        assertThat(opened.document().clienteId()).isEqualTo(customerId);
        verify(repository, never()).delete(parked);

        service.delete(parked.getId());
        verify(repository).delete(parked);
    }

    @Test
    void parksAndRestoresPromotionLineMetadata() {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        var promotionId = UUID.randomUUID();
        var promotionVersionId = UUID.randomUUID();
        var couponId = UUID.randomUUID();
        var command = command(UUID.randomUUID(), List.of(
                productLine(),
                new DocumentLineCommand(
                        null, BigDecimal.ONE, "CUPON", "CUPON BIENVENIDA", null,
                        new BigDecimal("-1.00"), BigDecimal.ZERO, true, "IVA",
                        new BigDecimal("21"), DocumentLineType.PROMOTIONAL_COUPON,
                        promotionId, promotionVersionId, couponId)));

        var parked = service.park(command, "Promo aparcada", auth());
        var restored = parked.documentCommand().lineas().get(1);

        assertThat(parked.getTotal()).isEqualByComparingTo("9.00");
        assertThat(restored.productoId()).isNull();
        assertThat(restored.lineType()).isEqualTo(DocumentLineType.PROMOTIONAL_COUPON);
        assertThat(restored.promotionId()).isEqualTo(promotionId);
        assertThat(restored.promotionVersionId()).isEqualTo(promotionVersionId);
        assertThat(restored.promotionalCouponId()).isEqualTo(couponId);
    }

    @Test
    void recoveryIsReplaySafeAndDeletesOnlyAfterAcknowledgement() {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(recoveries.save(any())).thenAnswer(call -> call.getArgument(0));
        var parked = service.park(command(UUID.randomUUID()), "Mesa 7", auth());
        var recoveryId = UUID.randomUUID();
        when(recoveries.findByRecoveryIdAndStoreIdAndCompanyId(
                recoveryId, store.getId(), company.getId()))
                .thenReturn(Optional.empty());
        when(repository.findLockedByIdAndStoreId(parked.getId(), store.getId()))
                .thenReturn(Optional.of(parked));
        when(recoveries.findByParkedSaleIdAndStoreIdAndCompanyId(
                parked.getId(), store.getId(), company.getId()))
                .thenReturn(Optional.empty());

        var claimed = service.recover(parked.getId(), recoveryId, auth());
        var capture = ArgumentCaptor.forClass(ParkedSaleRecovery.class);
        verify(recoveries).save(capture.capture());
        var recovery = capture.getValue();

        assertThat(claimed.status()).isEqualTo(ParkedSaleRecovery.Status.CLAIMED);
        assertThat(claimed.sale().document().clienteId())
                .isEqualTo(parked.getCustomerId());
        verify(repository, never()).delete(parked);

        when(recoveries.findByRecoveryIdAndStoreIdAndCompanyId(
                recoveryId, store.getId(), company.getId()))
                .thenReturn(Optional.of(recovery));
        var replay = service.recover(parked.getId(), recoveryId, auth());
        assertThat(replay).isEqualTo(claimed);

        when(recoveries.findLocked(
                recoveryId, store.getId(), company.getId()))
                .thenReturn(Optional.of(recovery));
        service.acknowledge(parked.getId(), recoveryId);
        service.acknowledge(parked.getId(), recoveryId);

        assertThat(recovery.getStatus())
                .isEqualTo(ParkedSaleRecovery.Status.ACKNOWLEDGED);
        verify(repository, times(1)).delete(parked);
    }

    @Test
    void rejectsASecondRecoveryClaimForTheSameParkedSale() {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        var parked = service.park(command(UUID.randomUUID()), "Caja 2", auth());
        var competing = new ParkedSaleRecovery(
                UUID.randomUUID(), parked, company.getId(), user.getId(), NOW);
        var recoveryId = UUID.randomUUID();
        when(recoveries.findByRecoveryIdAndStoreIdAndCompanyId(
                recoveryId, store.getId(), company.getId()))
                .thenReturn(Optional.empty());
        when(repository.findLockedByIdAndStoreId(parked.getId(), store.getId()))
                .thenReturn(Optional.of(parked));
        when(recoveries.findByParkedSaleIdAndStoreIdAndCompanyId(
                parked.getId(), store.getId(), company.getId()))
                .thenReturn(Optional.of(competing));

        assertThatThrownBy(() -> service.recover(
                parked.getId(), recoveryId, auth()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("parked_sale_recovery_already_claimed");
        verify(recoveries, never()).save(any());
    }

    private static DocumentCommand command(UUID customerId) {
        return command(customerId, List.of(productLine()));
    }

    private static DocumentCommand command(UUID customerId, List<DocumentLineCommand> lines) {
        return new DocumentCommand(
                UUID.randomUUID(),
                CommercialDocumentType.TICKET,
                LocalDate.of(2026, 6, 17),
                customerId,
                null,
                null,
                BigDecimal.ZERO,
                false,
                lines);
    }

    private static DocumentLineCommand productLine() {
        return new DocumentLineCommand(
                UUID.randomUUID(), 1, "P-1", "Producto", "VENTA",
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21"));
    }

    private static UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken("ADMIN", "n/a");
    }
}
