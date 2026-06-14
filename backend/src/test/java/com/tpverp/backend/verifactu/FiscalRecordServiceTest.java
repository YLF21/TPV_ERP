package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.hibernate.annotations.Immutable;

class FiscalRecordServiceTest {

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

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
