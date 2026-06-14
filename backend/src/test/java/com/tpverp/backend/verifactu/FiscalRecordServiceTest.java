package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

    @Mock FiscalChainRepository chains;
    @Mock FiscalRecordRepository records;
    @Mock FiscalSubmissionStateRepository states;

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
                eq(command.generatedAt().toInstant()));
        verify(records).save(record.capture());
        verify(states).save(state.capture());
        assertThat(saved).isSameAs(record.getValue());
        assertThat(saved.getSequence()).isEqualTo(1);
        assertThat(saved.getPreviousHash()).isNull();
        assertThat(saved.getSnapshotHash())
                .isEqualTo(new FiscalJsonHasher().hash(command.snapshot()));
        assertThat(saved.getHash()).isEqualTo(new OfficialHashService().hash(
                new AltaHashInput(
                        command.issuerTaxId(), command.number(), "14-06-2026",
                        command.documentType().name(), command.totalTax(),
                        command.totalAmount(), null, command.generatedAt())));
        assertThat(state.getValue().getRecordId()).isEqualTo(saved.getId());
        assertThat(state.getValue().getStatus())
                .isEqualTo(FiscalSubmissionStatus.PENDIENTE);
        assertThat(chain.previousHash()).isEqualTo(saved.getHash());
        assertThat(chain.nextSequence()).isEqualTo(2);
    }

    @Test
    void registraUnaAnulacionEncadenadaSinImportes() {
        var command = command(FiscalRecordOperation.ANULACION);
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
                        command.issuerTaxId(), command.number(), "14-06-2026",
                        previousHash, command.generatedAt())));
    }

    @Test
    void rechazaElComandoInvalidoAntesDeAccederALaCadena() {
        assertThatThrownBy(() -> service().register(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");

        verifyNoInteractions(chains, records, states);
    }

    @Test
    void rechazaUnNifEmisorInvalidoAlCrearElComando() {
        assertThatThrownBy(() -> new FiscalRecordCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F2, "001",
                LocalDate.of(2026, 6, 14),
                OffsetDateTime.parse("2026-06-14T10:00:00+01:00"),
                "Atlantic/Canary", "NIF-INVALIDO",
                BigDecimal.ZERO, BigDecimal.ZERO, Map.of(),
                "1.0", "SHA-256", "0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issuerTaxId");
    }

    @Test
    void rechazaImportesEnUnaAnulacion() {
        assertThatThrownBy(() -> new FiscalRecordCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                FiscalRecordOperation.ANULACION, FiscalDocumentType.F2, "001",
                LocalDate.of(2026, 6, 14),
                OffsetDateTime.parse("2026-06-14T10:00:00+01:00"),
                "Atlantic/Canary", "B12345678",
                BigDecimal.ZERO, BigDecimal.ZERO, Map.of(),
                "1.0", "SHA-256", "0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anulacion");
    }

    @Test
    void aceptaElOffsetDeCanariasAunqueLaFechaDeExpedicionSeaHistorica() {
        var command = new FiscalRecordCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F2, "001",
                LocalDate.of(2020, 1, 10),
                OffsetDateTime.parse("2026-06-14T10:00:00+01:00"),
                "Atlantic/Canary", "B12345678",
                BigDecimal.ZERO, BigDecimal.ZERO, Map.of(),
                "1.0", "SHA-256", "0.0.1");

        assertThat(command.issueDate()).isEqualTo(LocalDate.of(2020, 1, 10));
        assertThat(command.generatedAt().getOffset().getTotalSeconds()).isEqualTo(3600);
    }

    @Test
    void rechazaUnOffsetQueNoCoincideConLaZonaHoraria() {
        assertThatThrownBy(() -> new FiscalRecordCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                FiscalRecordOperation.ALTA, FiscalDocumentType.F2, "001",
                LocalDate.of(2020, 1, 10),
                OffsetDateTime.parse("2026-06-14T10:00:00Z"),
                "Atlantic/Canary", "B12345678",
                BigDecimal.ZERO, BigDecimal.ZERO, Map.of(),
                "1.0", "SHA-256", "0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generatedAt")
                .hasMessageContaining("timezone");
    }

    @Test
    void exigeDocumentoEnElComandoDeProduccion() {
        assertThatThrownBy(() -> new FiscalRecordCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                FiscalRecordOperation.ALTA, FiscalDocumentType.F2, "001",
                LocalDate.of(2026, 6, 14),
                OffsetDateTime.parse("2026-06-14T10:00:00+01:00"),
                "Atlantic/Canary", "B12345678",
                BigDecimal.ZERO, BigDecimal.ZERO, Map.of(),
                "1.0", "SHA-256", "0.0.1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("documentId");
    }

    @Test
    void noGuardaNadaSiNoPuedeInicializarLaCadena() {
        var command = command(FiscalRecordOperation.ALTA);
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
                chains, records, states,
                new OfficialHashService(), new FiscalJsonHasher());
    }

    private static FiscalRecordCommand command(FiscalRecordOperation operation) {
        return new FiscalRecordCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                operation,
                FiscalDocumentType.F2,
                "001-260614-000001",
                LocalDate.of(2026, 6, 14),
                OffsetDateTime.parse("2026-06-14T10:00:00+01:00"),
                "Atlantic/Canary",
                "B12345678",
                operation == FiscalRecordOperation.ALTA ? new BigDecimal("2.10") : null,
                operation == FiscalRecordOperation.ALTA ? new BigDecimal("12.10") : null,
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
                command.issueDate(),
                Instant.parse("2026-06-14T09:30:00Z"),
                command.timezone(),
                command.issuerTaxId(),
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

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
