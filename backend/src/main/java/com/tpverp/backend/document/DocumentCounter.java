package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "contador_documento", uniqueConstraints = @UniqueConstraint(
        columnNames = {"tienda_id", "tipo", "periodo"}))
public class DocumentCounter {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID tiendaId;

    @Column(nullable = false, length = 16)
    private String tipo;

    @Column(nullable = false, length = 8)
    private String periodo;

    @Column(name = "ultimo_numero", nullable = false)
    private int ultimoNumero;

    @Version
    private long version;

    protected DocumentCounter() {
    }

    public DocumentCounter(UUID tiendaId, CommercialDocumentType tipo, LocalDate fecha) {
        this.id = UUID.randomUUID();
        this.tiendaId = Objects.requireNonNull(tiendaId, "tiendaId");
        this.tipo = Objects.requireNonNull(tipo, "tipo").prefix();
        this.periodo = DocumentNumbering.period(tipo, fecha);
    }

    public static DocumentCounter salidaAlmacen(UUID tiendaId, LocalDate fecha) {
        var counter = new DocumentCounter();
        counter.id = UUID.randomUUID();
        counter.tiendaId = Objects.requireNonNull(tiendaId, "tiendaId");
        counter.tipo = "SAL";
        counter.periodo = Integer.toString(Objects.requireNonNull(fecha, "fecha").getYear());
        return counter;
    }

    public static DocumentCounter entradaAlmacen(UUID tiendaId, LocalDate fecha) {
        var counter = new DocumentCounter();
        counter.id = UUID.randomUUID();
        counter.tiendaId = Objects.requireNonNull(tiendaId, "tiendaId");
        counter.tipo = "ENT";
        counter.periodo = Integer.toString(Objects.requireNonNull(fecha, "fecha").getYear());
        return counter;
    }

    // Increments the counter and returns the formatted number.
    public String siguiente(CommercialDocumentType tipo, LocalDate fecha) {
        return siguiente(tipo, fecha, "001");
    }

    // Incrementa el contador y devuelve el numero fiscal ya formateado.
    public String siguiente(CommercialDocumentType tipo, LocalDate fecha, String codigoTienda) {
        if (!this.tipo.equals(tipo.prefix()) || !periodo.equals(DocumentNumbering.period(tipo, fecha))) {
            throw new IllegalArgumentException("tipo o periodo no coincide con el contador");
        }
        return DocumentNumbering.format(tipo, fecha, ++ultimoNumero, codigoTienda);
    }

    public int getUltimoNumero() {
        return ultimoNumero;
    }

    // Increments the annual sequence shared by warehouse outputs.
    public String siguienteSalidaAlmacen(LocalDate fecha) {
        var year = Integer.toString(Objects.requireNonNull(fecha, "fecha").getYear());
        if (!tipo.equals("SAL") || !periodo.equals(year)) {
            throw new IllegalArgumentException("periodo no coincide con el contador de salida");
        }
        return "SAL-%d-%06d".formatted(fecha.getYear(), ++ultimoNumero);
    }

    public String siguienteEntradaAlmacen(LocalDate fecha) {
        var year = Integer.toString(Objects.requireNonNull(fecha, "fecha").getYear());
        if (!tipo.equals("ENT") || !periodo.equals(year)) {
            throw new IllegalArgumentException("periodo no coincide con el contador de entrada");
        }
        return "ENT-%d-%06d".formatted(fecha.getYear(), ++ultimoNumero);
    }
}
