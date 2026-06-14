package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.Documento;
import com.tpverp.backend.document.DocumentoRepository;
import com.tpverp.backend.installation.Instalacion;
import com.tpverp.backend.installation.InstalacionRepository;
import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.EmpresaRepository;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.organization.TiendaRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.hibernate.annotations.Immutable;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FiscalRecordServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-14T09:15:30Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock FiscalChainRepository chains;
    @Mock FiscalRecordRepository records;
    @Mock FiscalSubmissionStateRepository states;
    @Mock EmpresaRepository companies;
    @Mock TiendaRepository stores;
    @Mock InstalacionRepository installations;
    @Mock DocumentoRepository documents;

    @Test
    void avanzaLaCabezaDeUnaCadenaVacia() {
        var companyId = UUID.randomUUID();
        var installationId = UUID.randomUUID();
        var chain = new FiscalChain(
                companyId, installationId, Instant.parse("2026-06-14T09:00:00Z"));
        var record = new FiscalRecord(
                chain.getId(),
                companyId,
                installationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                FiscalRecordOperation.ALTA,
                FiscalDocumentType.F2,
                "001-260614-000001",
                LocalDate.of(2026, 6, 14),
                Instant.parse("2026-06-14T10:00:00Z"),
                "Atlantic/Canary",
                "B12345678",
                new BigDecimal("2.10"),
                new BigDecimal("12.10"),
                null,
                "A".repeat(64),
                "B".repeat(64),
                Map.of("numero", "001-260614-000001"),
                "1.0",
                "SHA-256",
                "0.0.1");
        var updatedAt = Instant.parse("2026-06-14T10:00:01Z");

        chain.advance(record, updatedAt);

        assertThat(chain.nextSequence()).isEqualTo(2);
        assertThat(chain.previousHash()).isEqualTo(record.getHash());
        assertThat(chain.getLastRecord()).isSameAs(record);
        assertThat(chain.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void copiaProfundamenteElSnapshotYAdmiteValoresNulos() {
        var line = new LinkedHashMap<String, Object>();
        line.put("descripcion", "Original");
        line.put("referencia", null);
        var lines = new ArrayList<>(List.of(line));
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("cliente", null);
        snapshot.put("lineas", lines);
        var record = record(snapshot);

        line.put("descripcion", "Alterada");
        lines.clear();
        snapshot.put("cliente", "Otro");

        assertThat(record.getSnapshot())
                .containsEntry("cliente", null);
        assertThat((List<?>) record.getSnapshot().get("lineas")).hasSize(1);
        var savedLine = (Map<?, ?>) ((List<?>) record.getSnapshot().get("lineas")).getFirst();
        assertThat(savedLine.get("descripcion")).isEqualTo("Original");
        assertThat(savedLine.containsKey("referencia")).isTrue();
        assertThat(savedLine.get("referencia")).isNull();
    }

    @Test
    void devuelveUnaVistaProfundaInmutableDelSnapshot() {
        var record = record(Map.of("lineas", List.of(Map.of("cantidad", 1))));
        var lines = list(record.getSnapshot().get("lineas"));
        var line = map(lines.getFirst());

        assertThatThrownBy(() -> record.getSnapshot().put("otro", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> lines.add(Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> line.put("cantidad", 2))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void marcaComoInmutablesLosRegistrosYRelacionesFiscales() {
        assertThat(FiscalRecord.class).hasAnnotation(Immutable.class);
        assertThat(FiscalRecordRelation.class).hasAnnotation(Immutable.class);
    }

    @Test
    void protegeTambienElSnapshotHidratadoPorJpa() throws Exception {
        var record = record(Map.of("estado", "inicial"));
        var hydrated = new LinkedHashMap<String, Object>();
        hydrated.put("lineas", new ArrayList<>(List.of(Map.of("cantidad", 1))));
        var field = FiscalRecord.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        field.set(record, hydrated);

        var view = record.getSnapshot();
        hydrated.clear();

        assertThat(view).containsKey("lineas");
        assertThatThrownBy(() -> view.put("otro", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void registraUnAltaPendienteYAvanzaLaCadena() {
        var command = command(FiscalRecordOperation.ALTA);
        var context = stubContext(command);
        var chain = new FiscalChain(
                command.companyId(), command.installationId(),
                Instant.parse("2026-06-14T09:00:00Z"));
        when(chains.findForUpdate(command.companyId(), command.installationId()))
                .thenReturn(Optional.of(chain));
        when(records.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var saved = service().register(command);

        var record = ArgumentCaptor.forClass(FiscalRecord.class);
        var state = ArgumentCaptor.forClass(FiscalSubmissionState.class);
        verify(chains).insertIfMissing(
                any(UUID.class), eq(command.companyId()), eq(command.installationId()),
                eq(NOW));
        verify(records).save(record.capture());
        verify(states).save(state.capture());
        assertThat(saved).isSameAs(record.getValue());
        assertThat(saved.getSequence()).isEqualTo(1);
        assertThat(saved.getPreviousHash()).isNull();
        assertThat(saved.getSnapshotHash())
                .isEqualTo(new FiscalJsonHasher().hash(command.snapshot()));
        assertThat(saved.getHash()).isEqualTo(new OfficialHashService().hash(
                new AltaHashInput(
                        context.company().getTaxId(), context.document().getNumero(),
                        "10-01-2020", command.documentType().name(),
                        context.document().getImpuestoTotal(),
                        context.document().getTotal(), null,
                        NOW.atZone(ZoneOffset.ofHours(1)).toOffsetDateTime())));
        assertThat(state.getValue().getRecordId()).isEqualTo(saved.getId());
        assertThat(state.getValue().getStatus())
                .isEqualTo(FiscalSubmissionStatus.PENDIENTE);
        assertThat(chain.previousHash()).isEqualTo(saved.getHash());
        assertThat(chain.nextSequence()).isEqualTo(2);
    }

    @Test
    void registraUnaAnulacionEncadenadaSinImportes() {
        var command = command(FiscalRecordOperation.ANULACION);
        var context = stubContext(command);
        var chain = chainWithPreviousRecord(command);
        var previousHash = chain.previousHash();
        when(chains.findForUpdate(command.companyId(), command.installationId()))
                .thenReturn(Optional.of(chain));
        when(records.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var saved = service().register(command);

        assertThat(saved.getSequence()).isEqualTo(2);
        assertThat(saved.getPreviousHash()).isEqualTo(previousHash);
        assertThat(saved.getHash()).isEqualTo(new OfficialHashService().hash(
                new CancellationHashInput(
                        context.company().getTaxId(), context.document().getNumero(),
                        "10-01-2020", previousHash,
                        NOW.atZone(ZoneOffset.ofHours(1)).toOffsetDateTime())));
        var savedRecord = ArgumentCaptor.forClass(FiscalRecord.class);
        verify(records).save(savedRecord.capture());
        assertThat(field(savedRecord.getValue(), "totalTax")).isNull();
        assertThat(field(savedRecord.getValue(), "totalAmount")).isNull();
    }

    @Test
    void rechazaElComandoInvalidoAntesDeAccederALaCadena() {
        assertThatThrownBy(() -> service().register(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");

        verifyNoInteractions(
                chains, records, states, companies, stores, installations, documents);
    }

    @Test
    void rechazaUnaTiendaQueNoPerteneceALaEmpresa() {
        var command = command(FiscalRecordOperation.ALTA);
        var company = company("B12345674");
        var foreignStore = store(company("A58818501"), "Atlantic/Canary");
        when(companies.findById(command.companyId())).thenReturn(Optional.of(company));
        when(stores.findById(command.storeId())).thenReturn(Optional.of(foreignStore));

        assertThatThrownBy(() -> service().register(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tienda")
                .hasMessageContaining("empresa");

        verifyNoInteractions(chains, records, states);
    }

    @Test
    void rechazaElCifPersistidoSiSuControlEsInvalido() {
        var command = command(FiscalRecordOperation.ALTA);
        var company = company("B12345678");
        var store = store(company, "Atlantic/Canary");
        setId(company, command.companyId());
        setId(store, command.storeId());
        when(companies.findById(command.companyId())).thenReturn(Optional.of(company));
        when(stores.findById(command.storeId())).thenReturn(Optional.of(store));
        when(installations.findById(command.installationId()))
                .thenReturn(Optional.of(installation()));
        var document = document(command.storeId());
        when(documents.findById(command.documentId()))
                .thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service().register(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control");

        verifyNoInteractions(chains, records, states);
    }

    @Test
    void rechazaUnaInstalacionInexistenteAntesDeCrearLaCadena() {
        var command = command(FiscalRecordOperation.ALTA);
        stubCompanyAndStore(command);
        when(installations.findById(command.installationId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().register(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instalacion");

        verifyNoInteractions(chains, records, states);
    }

    @Test
    void rechazaUnDocumentoDeOtraTienda() {
        var command = command(FiscalRecordOperation.ALTA);
        stubCompanyAndStore(command);
        when(installations.findById(command.installationId()))
                .thenReturn(Optional.of(installation()));
        var document = document(UUID.randomUUID());
        when(documents.findById(command.documentId()))
                .thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service().register(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documento")
                .hasMessageContaining("tienda");

        verifyNoInteractions(chains, records, states);
    }

    @Test
    void exigeDocumentoEnElComandoDeProduccion() {
        assertThatThrownBy(() -> new FiscalRecordCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                FiscalRecordOperation.ALTA, FiscalDocumentType.F2, Map.of(),
                "1.0", "SHA-256", "0.0.1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("documentId");
    }

    @Test
    void rechazaUnaOperacionFiscalDuplicadaDespuesDeBloquearLaCadena() {
        var command = command(FiscalRecordOperation.ALTA);
        stubContext(command);
        var chain = new FiscalChain(command.companyId(), command.installationId(), NOW);
        when(chains.findForUpdate(command.companyId(), command.installationId()))
                .thenReturn(Optional.of(chain));
        when(records.existsByDocumentIdAndOperation(
                command.documentId(), command.operation())).thenReturn(true);

        assertThatThrownBy(() -> service().register(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registrada");

        var order = inOrder(chains, records);
        order.verify(chains).findForUpdate(command.companyId(), command.installationId());
        order.verify(records).existsByDocumentIdAndOperation(
                command.documentId(), command.operation());
        verify(records, never()).save(any());
        verify(states, never()).save(any());
    }

    @Test
    void noGuardaNadaSiNoPuedeInicializarLaCadena() {
        var command = command(FiscalRecordOperation.ALTA);
        stubContext(command);
        when(chains.findForUpdate(command.companyId(), command.installationId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().register(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cadena fiscal");

        verify(records, never()).save(any());
        verify(states, never()).save(any());
    }

    private static FiscalRecord record(Map<String, Object> snapshot) {
        return new FiscalRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                FiscalRecordOperation.ALTA,
                FiscalDocumentType.F2,
                "001-260614-000001",
                LocalDate.of(2026, 6, 14),
                Instant.parse("2026-06-14T10:00:00Z"),
                "Atlantic/Canary",
                "B12345678",
                new BigDecimal("2.10"),
                new BigDecimal("12.10"),
                null,
                "A".repeat(64),
                "B".repeat(64),
                snapshot,
                "1.0",
                "SHA-256",
                "0.0.1");
    }

    private FiscalRecordService service() {
        return new FiscalRecordService(
                chains, records, states, companies, stores, installations, documents, CLOCK);
    }

    private static FiscalRecordCommand command(FiscalRecordOperation operation) {
        return new FiscalRecordCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                operation,
                FiscalDocumentType.F2,
                Map.of("numero", "001-260614-000001"),
                "1.0",
                "SHA-256",
                "0.0.1");
    }

    private static FiscalChain chainWithPreviousRecord(FiscalRecordCommand command) {
        var chain = new FiscalChain(
                command.companyId(), command.installationId(),
                Instant.parse("2026-06-14T09:00:00Z"));
        chain.advance(new FiscalRecord(
                chain.getId(),
                command.companyId(),
                command.installationId(),
                command.storeId(),
                UUID.randomUUID(),
                1,
                FiscalRecordOperation.ALTA,
                FiscalDocumentType.F2,
                "001-260614-000000",
                LocalDate.of(2020, 1, 10),
                Instant.parse("2026-06-14T09:30:00Z"),
                "Atlantic/Canary",
                "B12345674",
                new BigDecimal("1.00"),
                new BigDecimal("6.00"),
                null,
                "A".repeat(64),
                "B".repeat(64),
                Map.of("numero", "001-260614-000000"),
                command.formatVersion(),
                command.algorithmVersion(),
                command.applicationVersion()),
                Instant.parse("2026-06-14T09:30:00Z"));
        return chain;
    }

    private TestContext stubContext(FiscalRecordCommand command) {
        var context = stubCompanyAndStore(command);
        when(installations.findById(command.installationId()))
                .thenReturn(Optional.of(installation()));
        var document = document(command.storeId());
        when(documents.findById(command.documentId()))
                .thenReturn(Optional.of(document));
        return new TestContext(context.company(), context.store(), document);
    }

    private TestContext stubCompanyAndStore(FiscalRecordCommand command) {
        var company = company("B12345674");
        var store = store(company, "Atlantic/Canary");
        setId(company, command.companyId());
        setId(store, command.storeId());
        when(companies.findById(command.companyId())).thenReturn(Optional.of(company));
        when(stores.findById(command.storeId())).thenReturn(Optional.of(store));
        return new TestContext(company, store, null);
    }

    private static Empresa company(String taxId) {
        return new Empresa(taxId, "Empresa", address());
    }

    private static Tienda store(Empresa company, String timezone) {
        return new Tienda(
                company, "Tienda", address(), "hash", timezone, "EUR", "es-ES");
    }

    private static Instalacion installation() {
        return new Instalacion(
                "INST-1", "public-key", Instant.parse("2026-06-14T08:00:00Z"));
    }

    private static Documento document(UUID storeId) {
        var document = mock(Documento.class);
        when(document.getTiendaId()).thenReturn(storeId);
        lenient().when(document.getNumero()).thenReturn("001-200110-000001");
        lenient().when(document.getFecha()).thenReturn(LocalDate.of(2020, 1, 10));
        lenient().when(document.getImpuestoTotal()).thenReturn(new BigDecimal("2.10"));
        lenient().when(document.getTotal()).thenReturn(new BigDecimal("12.10"));
        return document;
    }

    private static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
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

    private record TestContext(Empresa company, Tienda store, Documento document) {
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
