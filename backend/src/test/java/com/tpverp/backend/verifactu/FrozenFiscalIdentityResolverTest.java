package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.ArgumentCaptor;

class FrozenFiscalIdentityResolverTest {

    private LegacyFiscalArtifactIdentityRepository legacyIdentities;
    private FiscalRecordRepository records;
    private FiscalRecordRelationRepository relations;
    private FiscalRecordArtifactRepository artifacts;
    private FrozenFiscalIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        legacyIdentities = mock(LegacyFiscalArtifactIdentityRepository.class);
        records = mock(FiscalRecordRepository.class);
        relations = mock(FiscalRecordRelationRepository.class);
        artifacts = mock(FiscalRecordArtifactRepository.class);
        resolver = new FrozenFiscalIdentityResolver(
                legacyIdentities, records, relations, artifacts,
                new VerifactuXmlService(), Clock.fixed(
                        Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void freezesLegacyAltaIdentityOnlyFromItsImmutableXml() {
        var record = alta(UUID.randomUUID());
        var artifact = legacyArtifact(record, altaXml("Empresa &amp; Hijos", "B12345674"));
        when(legacyIdentities.findById(record.getId())).thenReturn(Optional.empty());

        var result = resolver.resolve(record, artifact);

        assertThat(result.issuerName()).isEqualTo("Empresa & Hijos");
        assertThat(result.issuerTaxId()).isEqualTo("B12345674");
        var frozen = ArgumentCaptor.forClass(LegacyFiscalArtifactIdentity.class);
        verify(legacyIdentities).saveAndFlush(frozen.capture());
        assertThat(frozen.getValue().getIssuerName()).isEqualTo("Empresa & Hijos");
        assertThat(frozen.getValue().getSource())
                .isEqualTo(LegacyFiscalIdentitySource.REGISTRO_ALTA_XML);
    }

    @Test
    void blocksLegacyIdentityWhenXmlTaxIdDoesNotMatchFrozenRecord() {
        var record = alta(UUID.randomUUID());
        var artifact = legacyArtifact(record, altaXml("Empresa ajena", "B99999999"));
        when(legacyIdentities.findById(record.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(record, artifact))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDENTIDAD_FISCAL_LEGACY_NO_RESUELTA");
        verify(legacyIdentities, never()).saveAndFlush(any());
    }

    @Test
    void revalidatesPersistedLegacyIdentityAgainstImmutableXml() {
        var record = alta(UUID.randomUUID());
        var artifact = legacyArtifact(record, altaXml("Empresa real", "B12345674"));
        when(legacyIdentities.findById(record.getId())).thenReturn(Optional.of(
                new LegacyFiscalArtifactIdentity(
                        record.getId(), "Nombre manipulado", "B12345674",
                        LegacyFiscalIdentitySource.REGISTRO_ALTA_XML,
                        Instant.parse("2026-08-25T09:30:00Z"))));

        assertThatThrownBy(() -> resolver.resolve(record, artifact))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evidencia inmutable");
        verify(legacyIdentities, never()).saveAndFlush(any());
    }

    @Test
    void cancellationInheritsOnlyFromItsExplicitRelatedAlta() {
        var chainId = UUID.randomUUID();
        var original = alta(chainId);
        var cancellation = cancellation(original);
        var cancellationArtifact = legacyArtifact(cancellation, anulacionXml("B12345674"));
        var originalArtifact = currentArtifact(original);
        when(legacyIdentities.findById(cancellation.getId())).thenReturn(Optional.empty());
        when(relations.findByRecordIdAndType(
                cancellation.getId(), FiscalRelationType.ANULA))
                .thenReturn(Optional.of(new FiscalRecordRelation(
                        chainId, cancellation.getId(), original.getId(), FiscalRelationType.ANULA)));
        when(records.findById(original.getId())).thenReturn(Optional.of(original));
        when(artifacts.findByRecordId(original.getId()))
                .thenReturn(Optional.of(originalArtifact));

        var result = resolver.resolve(cancellation, cancellationArtifact);

        assertThat(result.issuerName()).isEqualTo("Empresa congelada");
        var frozen = ArgumentCaptor.forClass(LegacyFiscalArtifactIdentity.class);
        verify(legacyIdentities).saveAndFlush(frozen.capture());
        assertThat(frozen.getValue().getRecordId()).isEqualTo(cancellation.getId());
        assertThat(frozen.getValue().getSource())
                .isEqualTo(LegacyFiscalIdentitySource.ALTA_RELACIONADA);
    }

    private static FiscalRecord alta(UUID chainId) {
        return new FiscalRecord(
                chainId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, FiscalRecordOperation.ALTA, FiscalDocumentType.F2,
                "001-260825-000001", LocalDate.of(2026, 8, 25),
                Instant.parse("2026-08-25T09:00:00Z"), "Atlantic/Canary",
                "B12345674", new BigDecimal("2.10"), new BigDecimal("12.10"),
                null, "A".repeat(64), "B".repeat(64), Map.of("total", "12.10"),
                "1.0", "SHA-256", "4.2.7", FiscalMode.VERIFACTU);
    }

    private static FiscalRecord cancellation(FiscalRecord original) {
        return new FiscalRecord(
                original.chainId(), original.getCompanyId(), original.getInstallationId(),
                original.getStoreId(), original.getDocumentId(), 2,
                FiscalRecordOperation.ANULACION, original.getDocumentType(),
                original.getNumber(), original.getIssueDate(),
                Instant.parse("2026-08-25T09:05:00Z"), original.getTimezone(),
                original.getIssuerTaxId(), null, null, original.getHash(),
                "C".repeat(64), "D".repeat(64), Map.of("anulacion", true),
                "1.0", "SHA-256", "4.2.7", FiscalMode.VERIFACTU);
    }

    private static FiscalRecordArtifact legacyArtifact(FiscalRecord record, String xml) {
        return new FiscalRecordArtifact(
                record.getId(), FiscalMode.VERIFACTU, FiscalEndpointEnvironment.TEST,
                true, UUID.randomUUID(), xml, null, null, "E".repeat(64),
                record.getOperation() == FiscalRecordOperation.ALTA ? print(record) : null,
                Instant.parse("2026-08-25T09:00:01Z"));
    }

    private static FiscalRecordArtifact currentArtifact(FiscalRecord record) {
        return new FiscalRecordArtifact(
                record.getId(), FiscalMode.VERIFACTU, FiscalEndpointEnvironment.TEST,
                true, UUID.randomUUID(), "Empresa congelada", "B12345674",
                altaXml("Empresa congelada", "B12345674"), null, null,
                "E".repeat(64), print(record), Instant.parse("2026-08-25T09:00:01Z"));
    }

    private static FiscalPrintSnapshot print(FiscalRecord record) {
        var url = new FiscalQrUrlService().url(
                record, FiscalMode.VERIFACTU, FiscalEndpointEnvironment.TEST);
        return new FiscalPrintSnapshot(
                FiscalPrintSnapshotFactory.FORMAT_VERSION, record.getApplicationVersion(),
                FiscalMode.VERIFACTU, FiscalEndpointEnvironment.TEST, url,
                "F".repeat(64), FiscalPrintSnapshotFactory.PREFIX,
                FiscalPrintSnapshotFactory.VERIFACTU_LEGEND,
                FiscalPrintSnapshotFactory.TEST_NOTICE);
    }

    private static String altaXml(String name, String taxId) {
        return """
                <sf:RegistroAlta xmlns:sf="https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd">
                  <sf:IDFactura><sf:IDEmisorFactura>%s</sf:IDEmisorFactura></sf:IDFactura>
                  <sf:NombreRazonEmisor>%s</sf:NombreRazonEmisor>
                  <sf:Encadenamiento><sf:RegistroAnterior>
                    <sf:IDEmisorFactura>%s</sf:IDEmisorFactura>
                  </sf:RegistroAnterior></sf:Encadenamiento>
                </sf:RegistroAlta>
                """.formatted(taxId, name, taxId);
    }

    private static String anulacionXml(String taxId) {
        return """
                <sf:RegistroAnulacion xmlns:sf="https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd">
                  <sf:IDFactura><sf:IDEmisorFacturaAnulada>%s</sf:IDEmisorFacturaAnulada></sf:IDFactura>
                </sf:RegistroAnulacion>
                """.formatted(taxId);
    }
}
