package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.Documento;
import com.tpverp.backend.document.EstadoDocumento;
import com.tpverp.backend.document.TipoDocumento;
import com.tpverp.backend.document.DocumentoRepository;
import com.tpverp.backend.installation.Instalacion;
import com.tpverp.backend.installation.InstalacionRepository;
import com.tpverp.backend.licensing.Licencia;
import com.tpverp.backend.licensing.LicenciaRepository;
import com.tpverp.backend.licensing.application.TaxpayerType;
import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.EmpresaRepository;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRate;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.FiscalAddress;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FiscalRecordServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-14T09:15:30.987654Z");
    private static final Instant TRUNCATED_NOW = Instant.parse("2026-06-14T09:15:30Z");

    @Mock FiscalChainRepository chains;
    @Mock FiscalRecordRepository records;
    @Mock FiscalRecordRelationRepository relations;
    @Mock FiscalSubmissionStateRepository states;
    @Mock VerifactuConfigurationRepository configurations;
    @Mock LicenciaRepository licenses;
    @Mock EmpresaRepository companies;
    @Mock TiendaRepository stores;
    @Mock InstalacionRepository installations;
    @Mock DocumentoRepository documents;
    @Mock CustomerRepository customers;

    private FiscalRecordCommand command;
    private Documento document;
    private FiscalChain chain;

    @BeforeEach
    void setUp() {
        command = command(FiscalRecordOperation.ALTA, FiscalDocumentType.F2);
        document = document(
                command.documentId(), command.storeId(), TipoDocumento.TICKET,
                EstadoDocumento.CONFIRMADO, new BigDecimal("12.10"));
        chain = new FiscalChain(command.companyId(), command.installationId(), TRUNCATED_NOW);
    }

    @Test
    void rechazaRegistroAntesDeActivacionVoluntariaOLegal() {
        stubContext(new VerifactuConfiguration(command.companyId()), document);

        assertThatThrownBy(() -> service().register(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("activo");

        verify(chains, never()).insertIfMissing(any(), any(), any(), any());
    }

    @Test
    void creaConfiguracionDesactivadaSiNoExisteYRechazaElRegistro() {
        stubIdentity(document);
        when(configurations.findByCompanyId(command.companyId())).thenReturn(Optional.empty());
        when(configurations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service().register(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("activo");

        var saved = ArgumentCaptor.forClass(VerifactuConfiguration.class);
        verify(configurations).save(saved.capture());
        assertThat(saved.getValue().isVoluntarilyActive()).isFalse();
    }

    @Test
    void derivaElSnapshotDelDocumentoYLoConservaInmutable() {
        stubActive(document);
        stubEmptyChain();

        var saved = service().register(command);
        var snapshot = saved.getSnapshot();

        assertThat(snapshot)
                .containsEntry("identificador", command.documentId().toString())
                .containsEntry("numero", "001-260614-000001")
                .containsEntry("estado", "CONFIRMADO")
                .containsEntry("nifEmisor", "B12345674")
                .containsEntry("operacionFiscal", "ALTA")
                .containsEntry("tipoFiscal", "F2")
                .containsEntry("total", new BigDecimal("12.10"));
        assertThatThrownBy(() -> snapshot.put("numero", "ALTERADO"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void congelaElClienteAunqueCambieDespuesDelRegistro() {
        var company = company(command.companyId());
        var customer = customer(company, "Cliente Original", "12345678Z");
        when(document.getClienteId()).thenReturn(customer.getId());
        stubActive(document);
        when(customers.findByIdAndCompanyId(customer.getId(), command.companyId()))
                .thenReturn(Optional.of(customer));
        stubEmptyChain();

        var saved = service().register(command);
        customer.update(
                "Cliente Alterado", DocumentType.NIF, "87654321X",
                new FiscalAddress("Otra calle", "35002", "Telde", "Las Palmas", "ES"),
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);

        var frozenCustomer = map(saved.getSnapshot().get("cliente"));
        assertThat(frozenCustomer)
                .containsEntry("nombreFiscal", "Cliente Original")
                .containsEntry("numeroDocumento", "12345678Z");
        assertThat(saved.getSnapshotHash())
                .isEqualTo(new FiscalJsonHasher().hash(saved.getSnapshot()));
    }

    @Test
    void rechazaDocumentoConClienteInexistenteODeOtraEmpresa() {
        var customerId = UUID.randomUUID();
        when(document.getClienteId()).thenReturn(customerId);
        stubActive(document);
        when(customers.findByIdAndCompanyId(customerId, command.companyId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().register(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cliente");

        verify(chains, never()).insertIfMissing(any(), any(), any(), any());
    }

    @Test
    void rechazaLicenciaFuturaCaducadaONifDistinto() {
        stubActive(document);
        var license = activeLicense();
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(
                command.storeId(), command.installationId()))
                .thenReturn(Optional.of(license));

        when(license.getValidaDesde()).thenReturn(NOW.plusSeconds(1));
        assertThatThrownBy(() -> service().register(command))
                .hasMessageContaining("vigente");

        when(license.getValidaDesde()).thenReturn(NOW.minusSeconds(60));
        when(license.getValidaHasta()).thenReturn(TRUNCATED_NOW);
        assertThatThrownBy(() -> service().register(command))
                .hasMessageContaining("vigente");

        when(license.getValidaHasta()).thenReturn(NOW.plusSeconds(60));
        when(license.getTaxId()).thenReturn("A58818501");
        assertThatThrownBy(() -> service().register(command))
                .hasMessageContaining("NIF");
    }

    @Test
    void rechazaBorradorCompraTicketF1YFacturaAnulada() {
        assertRejected(document(
                command.documentId(), command.storeId(), TipoDocumento.TICKET,
                EstadoDocumento.BORRADOR, BigDecimal.TEN), command);
        assertRejected(document(
                command.documentId(), command.storeId(), TipoDocumento.FACTURA_COMPRA,
                EstadoDocumento.PENDIENTE, BigDecimal.TEN),
                command(FiscalRecordOperation.ALTA, FiscalDocumentType.F1));
        assertRejected(document(
                command.documentId(), command.storeId(), TipoDocumento.TICKET,
                EstadoDocumento.CONFIRMADO, BigDecimal.TEN),
                command(FiscalRecordOperation.ALTA, FiscalDocumentType.F1));
        assertRejected(document(
                command.documentId(), command.storeId(), TipoDocumento.FACTURA_VENTA,
                EstadoDocumento.ANULADO, BigDecimal.TEN),
                command(FiscalRecordOperation.ALTA, FiscalDocumentType.F1));
    }

    @Test
    void rechazaAnulacionSinAltaPrevia() {
        var cancellation = command(FiscalRecordOperation.ANULACION, FiscalDocumentType.F2);
        var cancelled = document(
                cancellation.documentId(), cancellation.storeId(), TipoDocumento.TICKET,
                EstadoDocumento.ANULADO, BigDecimal.TEN);
        stubActive(cancellation, cancelled);
        when(chains.findForUpdate(cancellation.companyId(), cancellation.installationId()))
                .thenReturn(Optional.of(chain));
        when(records.findByDocumentIdAndOperation(
                cancellation.documentId(), FiscalRecordOperation.ANULACION))
                .thenReturn(Optional.empty());
        when(records.findByDocumentIdAndOperation(
                cancellation.documentId(), FiscalRecordOperation.ALTA))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().register(cancellation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alta");

        verify(records, never()).save(any());
    }

    @Test
    void creaRelacionDeAnulacionConElAltaOriginal() {
        var cancellation = command(FiscalRecordOperation.ANULACION, FiscalDocumentType.F2);
        var cancelled = document(
                cancellation.documentId(), cancellation.storeId(), TipoDocumento.TICKET,
                EstadoDocumento.ANULADO, BigDecimal.TEN);
        var original = fiscalRecord(
                chain, cancellation, FiscalRecordOperation.ALTA,
                FiscalDocumentType.F2, 1, null);
        chain.advance(original, TRUNCATED_NOW);
        stubActive(cancellation, cancelled);
        when(chains.findForUpdate(cancellation.companyId(), cancellation.installationId()))
                .thenReturn(Optional.of(chain));
        when(records.findByDocumentIdAndOperation(
                cancellation.documentId(), FiscalRecordOperation.ANULACION))
                .thenReturn(Optional.empty());
        when(records.findByDocumentIdAndOperation(
                cancellation.documentId(), FiscalRecordOperation.ALTA))
                .thenReturn(Optional.of(original));
        when(records.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var cancellationRecord = service().register(cancellation);

        var relation = ArgumentCaptor.forClass(FiscalRecordRelation.class);
        verify(relations).save(relation.capture());
        assertThat(field(relation.getValue(), "recordId"))
                .isEqualTo(cancellationRecord.getId());
        assertThat(field(relation.getValue(), "relatedId"))
                .isEqualTo(original.getId());
        assertThat(field(relation.getValue(), "type"))
                .isEqualTo(FiscalRelationType.ANULA);
    }

    @Test
    void bloqueaAntesDeComprobarLaUnicidadLogica() {
        stubActive(document);
        when(chains.findForUpdate(command.companyId(), command.installationId()))
                .thenReturn(Optional.of(chain));
        when(records.findByDocumentIdAndOperation(
                command.documentId(), command.operation()))
                .thenReturn(Optional.of(fiscalRecord(
                        chain, command, command.operation(), command.documentType(), 1, null)));

        assertThatThrownBy(() -> service().register(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registrada");

        var order = inOrder(chains, records);
        order.verify(chains).findForUpdate(command.companyId(), command.installationId());
        order.verify(records).findByDocumentIdAndOperation(
                command.documentId(), command.operation());
    }

    @Test
    void truncaLaHoraASegundosAntesDeGuardarYHashear() {
        stubActive(document);
        stubEmptyChain();

        var saved = service().register(command);

        assertThat(field(saved, "generatedAt")).isEqualTo(TRUNCATED_NOW);
        verify(chains).insertIfMissing(
                any(), eq(command.companyId()), eq(command.installationId()),
                eq(TRUNCATED_NOW));
        var state = ArgumentCaptor.forClass(FiscalSubmissionState.class);
        verify(states).save(state.capture());
        assertThat(field(state.getValue(), "updatedAt")).isEqualTo(TRUNCATED_NOW);
    }

    private void assertRejected(Documento invalidDocument, FiscalRecordCommand invalidCommand) {
        stubActive(invalidCommand, invalidDocument);

        assertThatThrownBy(() -> service().register(invalidCommand))
                .isInstanceOf(IllegalArgumentException.class);

        verify(chains, never()).insertIfMissing(any(), any(), any(), any());
    }

    private void stubEmptyChain() {
        when(chains.findForUpdate(command.companyId(), command.installationId()))
                .thenReturn(Optional.of(chain));
        when(records.findByDocumentIdAndOperation(
                command.documentId(), command.operation()))
                .thenReturn(Optional.empty());
        when(records.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubActive(Documento value) {
        stubActive(command, value);
    }

    private void stubActive(FiscalRecordCommand value, Documento persistedDocument) {
        var configuration = new VerifactuConfiguration(value.companyId());
        configuration.activateVoluntarily(Instant.parse("2026-06-01T00:00:00Z"));
        stubContext(value, configuration, persistedDocument);
    }

    private void stubContext(
            VerifactuConfiguration configuration, Documento persistedDocument) {
        stubContext(command, configuration, persistedDocument);
    }

    private void stubContext(
            FiscalRecordCommand value,
            VerifactuConfiguration configuration,
            Documento persistedDocument) {
        stubIdentity(value, persistedDocument);
        when(configurations.findByCompanyId(value.companyId()))
                .thenReturn(Optional.of(configuration));
    }

    private void stubIdentity(Documento persistedDocument) {
        stubIdentity(command, persistedDocument);
    }

    private void stubIdentity(
            FiscalRecordCommand value, Documento persistedDocument) {
        var company = new Empresa("B12345674", "Empresa", address());
        var store = new Tienda(
                company, "Tienda", address(), "hash",
                "Atlantic/Canary", "EUR", "es-ES");
        setId(company, value.companyId());
        setId(store, value.storeId());
        when(companies.findById(value.companyId())).thenReturn(Optional.of(company));
        when(stores.findById(value.storeId())).thenReturn(Optional.of(store));
        when(installations.findById(value.installationId()))
                .thenReturn(Optional.of(mock(Instalacion.class)));
        when(documents.findById(value.documentId()))
                .thenReturn(Optional.of(persistedDocument));
        var license = activeLicense();
        when(licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(
                value.storeId(), value.installationId()))
                .thenReturn(Optional.of(license));
    }

    private FiscalRecordService service() {
        return new FiscalRecordService(
                chains, records, relations, states, configurations, licenses,
                companies, stores, installations, documents, customers,
                new VerifactuActivationService(), new FiscalSnapshotFactory(),
                new FiscalDocumentPolicy(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static FiscalRecordCommand command(
            FiscalRecordOperation operation, FiscalDocumentType documentType) {
        return new FiscalRecordCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                operation, documentType, "1.0", "SHA-256", "0.0.1");
    }

    private static Documento document(
            UUID documentId,
            UUID storeId,
            TipoDocumento type,
            EstadoDocumento state,
            BigDecimal total) {
        var value = mock(Documento.class);
        when(value.getId()).thenReturn(documentId);
        when(value.getTiendaId()).thenReturn(storeId);
        when(value.getTipo()).thenReturn(type);
        when(value.getEstado()).thenReturn(state);
        when(value.getNumero()).thenReturn("001-260614-000001");
        when(value.getFecha()).thenReturn(LocalDate.of(2026, 6, 14));
        when(value.getMoneda()).thenReturn("EUR");
        when(value.getDescuentoGlobal()).thenReturn(new BigDecimal("0.00"));
        when(value.getBaseTotal()).thenReturn(new BigDecimal("10.00"));
        when(value.getImpuestoTotal()).thenReturn(new BigDecimal("2.10"));
        when(value.getTotal()).thenReturn(total);
        when(value.getLineas()).thenReturn(List.of());
        when(value.getPagos()).thenReturn(List.of());
        return value;
    }

    private static FiscalRecord fiscalRecord(
            FiscalChain fiscalChain,
            FiscalRecordCommand value,
            FiscalRecordOperation operation,
            FiscalDocumentType documentType,
            long sequence,
            String previousHash) {
        return new FiscalRecord(
                fiscalChain.getId(), value.companyId(), value.installationId(), value.storeId(),
                value.documentId(), sequence, operation, documentType,
                "001-260614-000001", LocalDate.of(2026, 6, 14), TRUNCATED_NOW,
                "Atlantic/Canary", "B12345674", new BigDecimal("2.10"),
                new BigDecimal("12.10"), previousHash, "A".repeat(64), "B".repeat(64),
                Map.of("numero", "001-260614-000001"),
                value.formatVersion(), value.algorithmVersion(), value.applicationVersion());
    }

    private static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
    }

    private static Empresa company(UUID id) {
        var company = new Empresa("B12345674", "Empresa", address());
        setId(company, id);
        return company;
    }

    private static Customer customer(Empresa company, String name, String documentNumber) {
        return new Customer(
                company, name, DocumentType.NIF, documentNumber,
                new FiscalAddress(
                        "Calle Cliente", "35001", "Las Palmas",
                        "Las Palmas", "ES"),
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
    }

    private static Licencia activeLicense() {
        var license = mock(Licencia.class);
        when(license.getTaxpayerType()).thenReturn(TaxpayerType.SOCIEDAD);
        when(license.getTaxId()).thenReturn("B12345674");
        when(license.getValidaDesde()).thenReturn(NOW.minusSeconds(60));
        when(license.getValidaHasta()).thenReturn(NOW.plusSeconds(60));
        return license;
    }

    private static void setId(Object target, UUID id) {
        try {
            var field = target.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
