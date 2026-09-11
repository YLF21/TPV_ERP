package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Copies committed document values without changing historical money, identities or payment semantics. */
@Component
public class DocumentSyncPayloadFactory {

    public static final int SCHEMA_VERSION = 2;
    private final DocumentRelationRepository relations;
    private final DocumentAttributionResolver attributions;

    public DocumentSyncPayloadFactory(DocumentRelationRepository relations, DocumentAttributionResolver attributions) {
        this.relations = relations;
        this.attributions = attributions;
    }

    public Map<String, Object> create(CommercialDocument document, long sourceRevision) {
        Objects.requireNonNull(document, "document");
        if (sourceRevision < 1) throw new IllegalArgumentException("Revision documental no valida");
        var outgoing = relations.findOutgoingForSync(document.getId(), document.getTiendaId());
        for (var relation : outgoing) {
            if (!document.getTiendaId().equals(relation.getOriginStoreId())
                    || relation.getType() == null || relation.getOriginId() == null) {
                throw new IllegalStateException("La procedencia de una relacion documental no coincide");
            }
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("sourceRevision", sourceRevision);
        payload.put("tipo", document.getTipo().name());
        payload.put("numero", document.getNumero());
        payload.put("estado", document.getEstado().name());
        payload.put("fecha", document.getFecha().toString());
        payload.put("clienteId", nullableUuid(document.getClienteId()));
        payload.put("proveedorId", nullableUuid(document.getProveedorId()));
        payload.put("almacenId", nullableUuid(document.getAlmacenId()));
        payload.put("descuentoGlobal", document.getDescuentoGlobal().toPlainString());
        payload.put("subtotal", document.getBaseTotal().toPlainString());
        payload.put("impuestos", document.getImpuestoTotal().toPlainString());
        payload.put("total", document.getTotal().toPlainString());
        payload.put("moneda", document.getMoneda());
        payload.put("lineas", document.getLineas().stream().map(DocumentSyncPayloadFactory::linePayload).toList());
        payload.put("pagos", document.getPagos().stream().map(DocumentSyncPayloadFactory::paymentPayload).toList());
        payload.put("creadoPor", nullableUuid(document.getCreadoPor()));
        payload.put("confirmadoPor", nullableUuid(document.getConfirmadoPor()));
        payload.put("anuladoPor", nullableUuid(document.getAnuladoPor()));
        payload.put("creadoEn", nullableDate(document.getCreadoEn()));
        payload.put("confirmadoEn", nullableDate(document.getConfirmadoEn()));
        payload.put("anuladoEn", nullableDate(document.getAnuladoEn()));
        payload.put("terminalOrigenId", nullableUuid(document.getTerminalOrigenId()));
        var attribution = attributions.resolve(java.util.List.of(document))
                .getOrDefault(document.getId(), DocumentAttributionResolver.Attribution.empty(document));
        payload.put("usuarioNombre", nullableLabel(attribution.userName()));
        payload.put("terminalOrigenNombre", nullableLabel(attribution.terminalName()));
        payload.put("fechaVencimiento", nullableDate(document.getFechaVencimiento()));
        payload.put("settledByOrigin", document.isSettledByOrigin());
        payload.put("relaciones", outgoing.stream().map(relation -> Map.of(
                "tipo", relation.getType().name(), "origenId", relation.getOriginId().toString())).toList());
        return payload;
    }

    private static Map<String, Object> linePayload(DocumentLine line) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("productoId", nullableUuid(line.getProductoId()));
        payload.put("tipoLinea", line.getLineType().name());
        payload.put("promocionId", nullableUuid(line.getPromotionId()));
        payload.put("cuponPromocionalId", nullableUuid(line.getPromotionalCouponId()));
        payload.put("posicion", line.getPosicion());
        payload.put("codigo", line.getCodigo());
        payload.put("nombre", line.getNombre());
        payload.put("tarifa", line.getTarifa());
        payload.put("cantidad", String.valueOf(line.getCantidad()));
        payload.put("precioUnitario", line.getPrecioUnitario().toPlainString());
        payload.put("descuento", line.getDescuento().toPlainString());
        payload.put("impuestosIncluidos", line.isImpuestosIncluidos());
        payload.put("regimenImpuesto", line.getRegimenImpuesto());
        payload.put("porcentajeImpuesto", line.getPorcentajeImpuesto().toPlainString());
        payload.put("base", line.getBase().toPlainString());
        payload.put("impuesto", line.getImpuesto().toPlainString());
        payload.put("total", line.getTotal().toPlainString());
        return payload;
    }

    private static Map<String, Object> paymentPayload(DocumentPayment payment) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("metodoPagoId", payment.getMetodoPago().getId().toString());
        payload.put("metodoPago", payment.getMetodoPago().getNombre());
        payload.put("posicion", payment.getPosicion());
        payload.put("importe", payment.getImporte().toPlainString());
        payload.put("principal", payment.isPrincipal());
        payload.put("entregado", nullableAmount(payment.getEntregado()));
        payload.put("cambio", nullableAmount(payment.getCambio()));
        payload.put("voucherCode", payment.getVoucherCode());
        payload.put("referencia", payment.getReferencia());
        payload.put("terminalPagoModo", nullableEnum(payment.getCardMode()));
        payload.put("terminalPagoProvider", nullableEnum(payment.getPaymentTerminalProvider()));
        payload.put("terminalPagoEstado", nullableEnum(payment.getPaymentTerminalStatus()));
        payload.put("autorizacionTarjeta", payment.getCardAuthorizationCode());
        payload.put("terminalCobroId", nullableUuid(payment.getPaymentTerminalId()));
        return payload;
    }

    private static String nullableLabel(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static String nullableEnum(Enum<?> value) { return value == null ? null : value.name(); }
    private static String nullableUuid(UUID value) { return value == null ? null : value.toString(); }
    private static String nullableAmount(BigDecimal value) { return value == null ? null : value.toPlainString(); }
    private static String nullableDate(TemporalAccessor value) { return value == null ? null : value.toString(); }
}
