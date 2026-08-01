package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SaleDocumentAuthorizationManifestServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");
    private static final String FINGERPRINT = "a".repeat(64);

    @Mock private SaleDocumentAuthorizationManifestRepository manifests;
    @Mock private SaleDocumentAuthorizationFingerprint fingerprints;
    @Mock private SaleOperationSecurityService operationSecurity;
    @Mock private CurrentOrganization organization;

    private SaleDocumentAuthorizationManifestService service;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        storeId = UUID.randomUUID();
        var store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        service = new SaleDocumentAuthorizationManifestService(
                manifests,
                fingerprints,
                operationSecurity,
                organization,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recordsOnlyFingerprintAndNonSecretPolicyVersions() {
        var document = document();
        var proof = new SaleDocumentMutationAuthorizationService.AuthorizationProof(
                Map.of(SaleOperationCode.TEMPORARY_PRICE_CHANGE, 3L));
        when(fingerprints.fingerprint(document)).thenReturn(FINGERPRINT);

        service.record(document, proof);

        var captor = ArgumentCaptor.forClass(
                SaleDocumentAuthorizationManifest.class);
        verify(manifests).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getDocumentId()).isEqualTo(document.getId());
        assertThat(captor.getValue().getStoreId()).isEqualTo(storeId);
        assertThat(captor.getValue().getPolicyVersions())
                .containsExactly(Map.entry(
                        SaleOperationCode.TEMPORARY_PRICE_CHANGE, 3L));
    }

    @Test
    void reportsPolicyDriftWithoutTrustingStaleVersion() {
        var document = document();
        var manifest = manifest(
                document,
                Map.of(SaleOperationCode.APPLY_SALE_DISCOUNT, 1L));
        var current = mock(SaleOperationSecurityService.ResolvedOperation.class);
        when(current.version()).thenReturn(2L);
        when(operationSecurity.resolve(SaleOperationCode.APPLY_SALE_DISCOUNT))
                .thenReturn(current);
        when(manifests.findForUpdate(document.getId(), storeId))
                .thenReturn(Optional.of(manifest));
        when(fingerprints.fingerprint(document)).thenReturn(FINGERPRINT);

        var validation = service.validate(document);

        assertThat(validation.operations())
                .containsExactly(SaleOperationCode.APPLY_SALE_DISCOUNT);
        assertThat(validation.policyChanged()).isTrue();
    }

    @Test
    void rejectsDraftWhosePersistedContentChangedAfterAuthorization() {
        var document = document();
        var manifest = manifest(document, Map.of());
        when(manifests.findForUpdate(document.getId(), storeId))
                .thenReturn(Optional.of(manifest));
        when(fingerprints.fingerprint(document)).thenReturn("b".repeat(64));

        assertThatThrownBy(() -> service.validate(document))
                .isInstanceOf(GenericSaleConfirmationBlockedException.class)
                .satisfies(exception -> assertThat(
                        ((GenericSaleConfirmationBlockedException) exception).reason())
                        .isEqualTo(
                                GenericSaleConfirmationBlockedException.Reason.MISMATCH));
    }

    @Test
    void rejectsLegacyDraftWithoutManifest() {
        var document = document();
        when(manifests.findForUpdate(document.getId(), storeId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(document))
                .isInstanceOf(GenericSaleConfirmationBlockedException.class)
                .satisfies(exception -> assertThat(
                        ((GenericSaleConfirmationBlockedException) exception).reason())
                        .isEqualTo(
                                GenericSaleConfirmationBlockedException.Reason.MISSING));
    }

    private SaleDocumentAuthorizationManifest manifest(
            CommercialDocument document,
            Map<SaleOperationCode, Long> policyVersions) {
        return new SaleDocumentAuthorizationManifest(
                document.getId(), storeId, FINGERPRINT, policyVersions, NOW);
    }

    private CommercialDocument document() {
        var document = new CommercialDocument(
                storeId,
                UUID.randomUUID(),
                CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 7, 31),
                UUID.randomUUID(),
                BigDecimal.ZERO);
        document.setParties(UUID.randomUUID(), null, null);
        document.addLine(new DocumentLine(
                document,
                UUID.randomUUID(),
                1,
                BigDecimal.ONE,
                "P1",
                "Producto",
                "VENTA",
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                true,
                "IVA",
                new BigDecimal("21.00")));
        return document;
    }
}
