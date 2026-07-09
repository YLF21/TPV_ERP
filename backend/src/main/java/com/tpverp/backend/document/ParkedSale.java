package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "venta_aparcada")
public class ParkedSale {

    @Id
    private UUID id;
    @Column(name = "tienda_id", nullable = false)
    private UUID tiendaId;
    @Column(name = "creado_por", nullable = false)
    private UUID creadoPor;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;
    @Column(name = "cliente_id")
    private UUID clienteId;
    @Column
    private String comentario;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal total;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> documento;

    protected ParkedSale() {
    }

    public ParkedSale(UUID tiendaId, UUID userId, Instant createdAt,
            DocumentCommand command, String comment) {
        id = UUID.randomUUID();
        this.tiendaId = Objects.requireNonNull(tiendaId, "tiendaId");
        creadoPor = Objects.requireNonNull(userId, "userId");
        creadoEn = Objects.requireNonNull(createdAt, "createdAt");
        clienteId = command.clienteId();
        comentario = clean(comment);
        total = total(command, userId, tiendaId);
        documento = snapshot(command);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTiendaId() {
        return tiendaId;
    }

    public Instant getCreatedAt() {
        return creadoEn;
    }

    public UUID getCreatedBy() {
        return creadoPor;
    }

    public UUID getCustomerId() {
        return clienteId;
    }

    public String getTicketNumber() {
        return null;
    }

    public String getComment() {
        return comentario;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public DocumentCommand documentCommand() {
        return new DocumentCommand(
                uuid(documento.get("almacenId")),
                CommercialDocumentType.valueOf((String) documento.get("tipo")),
                LocalDate.parse((String) documento.get("fecha")),
                uuid(documento.get("clienteId")),
                null,
                null,
                decimal(documento.get("descuentoGlobal")),
                false,
                lines());
    }
    // Reconstruye el ticket borrador que se entrega al terminal al abrir la venta.

    private List<DocumentLineCommand> lines() {
        return ((List<?>) documento.get("lineas")).stream()
                .map(Map.class::cast)
                .map(ParkedSale::line)
                .toList();
    }

    private static DocumentLineCommand line(Map<?, ?> value) {
        var type = value.get("tipoLinea") == null
                ? DocumentLineType.PRODUCT
                : DocumentLineType.valueOf((String) value.get("tipoLinea"));
        return new DocumentLineCommand(
                uuid(value.get("productoId")),
                decimal(value.get("cantidad")),
                (String) value.get("codigo"),
                (String) value.get("nombre"),
                (String) value.get("tarifa"),
                decimal(value.get("precioUnitario")),
                decimal(value.get("descuento")),
                (Boolean) value.get("impuestosIncluidos"),
                (String) value.get("regimenImpuesto"),
                decimal(value.get("porcentajeImpuesto")),
                type,
                uuid(value.get("promocionId")),
                uuid(value.get("promocionVersionId")),
                uuid(value.get("cuponPromocionalId")));
    }

    private static Map<String, Object> snapshot(DocumentCommand command) {
        var value = new LinkedHashMap<String, Object>();
        value.put("almacenId", command.almacenId().toString());
        value.put("tipo", command.tipo().name());
        value.put("fecha", command.fecha().toString());
        value.put("clienteId", string(command.clienteId()));
        value.put("descuentoGlobal", command.descuentoGlobal().toPlainString());
        value.put("lineas", command.lineas().stream().map(ParkedSale::snapshot).toList());
        return value;
    }

    private static Map<String, Object> snapshot(DocumentLineCommand line) {
        var value = new LinkedHashMap<String, Object>();
        value.put("productoId", string(line.productoId()));
        value.put("tipoLinea", line.lineType() == null
                ? DocumentLineType.PRODUCT.name()
                : line.lineType().name());
        value.put("promocionId", string(line.promotionId()));
        value.put("promocionVersionId", string(line.promotionVersionId()));
        value.put("cuponPromocionalId", string(line.promotionalCouponId()));
        value.put("cantidad", line.cantidad());
        value.put("codigo", line.codigo());
        value.put("nombre", line.nombre());
        value.put("tarifa", line.tarifa());
        value.put("precioUnitario", line.precioUnitario().toPlainString());
        value.put("descuento", line.descuento().toPlainString());
        value.put("impuestosIncluidos", line.impuestosIncluidos());
        value.put("regimenImpuesto", line.regimenImpuesto());
        value.put("porcentajeImpuesto", line.porcentajeImpuesto().toPlainString());
        return value;
    }

    private static BigDecimal total(DocumentCommand command, UUID userId, UUID storeId) {
        var document = new CommercialDocument(
                storeId, command.almacenId(), CommercialDocumentType.TICKET,
                command.fecha(), userId, command.descuentoGlobal());
        command.lineas().forEach(line -> document.addLine(line.toEntity(document)));
        return document.getTotal();
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(value.toString());
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
