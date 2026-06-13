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
public class ContadorDocumento {

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

    protected ContadorDocumento() {
    }

    public ContadorDocumento(UUID tiendaId, TipoDocumento tipo, LocalDate fecha) {
        this.id = UUID.randomUUID();
        this.tiendaId = Objects.requireNonNull(tiendaId, "tiendaId");
        this.tipo = Objects.requireNonNull(tipo, "tipo").prefix();
        this.periodo = DocumentNumbering.period(tipo, fecha);
    }

    public static ContadorDocumento salidaAlmacen(UUID tiendaId, LocalDate fecha) {
        var counter = new ContadorDocumento();
        counter.id = UUID.randomUUID();
        counter.tiendaId = Objects.requireNonNull(tiendaId, "tiendaId");
        counter.tipo = "SAL";
        counter.periodo = Integer.toString(Objects.requireNonNull(fecha, "fecha").getYear());
        return counter;
    }

    // Incrementa el contador y devuelve el número ya formateado.
    public String siguiente(TipoDocumento tipo, LocalDate fecha) {
        if (!this.tipo.equals(tipo.prefix()) || !periodo.equals(DocumentNumbering.period(tipo, fecha))) {
            throw new IllegalArgumentException("tipo o periodo no coincide con el contador");
        }
        return DocumentNumbering.format(tipo, fecha, ++ultimoNumero);
    }

    public int getUltimoNumero() {
        return ultimoNumero;
    }

    // Incrementa la secuencia anual compartida por las salidas de almacén.
    public String siguienteSalidaAlmacen(LocalDate fecha) {
        var year = Integer.toString(Objects.requireNonNull(fecha, "fecha").getYear());
        if (!tipo.equals("SAL") || !periodo.equals(year)) {
            throw new IllegalArgumentException("periodo no coincide con el contador de salida");
        }
        return "SAL-%d-%06d".formatted(fecha.getYear(), ++ultimoNumero);
    }
}
