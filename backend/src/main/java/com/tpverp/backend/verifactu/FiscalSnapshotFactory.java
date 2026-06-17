package com.tpverp.backend.verifactu;

import com.tpverp.backend.document.Documento;
import com.tpverp.backend.document.DocumentoLinea;
import com.tpverp.backend.document.DocumentoPago;
import com.tpverp.backend.organization.SpanishTaxId;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.FiscalAddress;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FiscalSnapshotFactory {

    // Congela todos los datos fiscales persistidos con un orden estable para su huella.
    public Map<String, Object> create(
            Documento document,
            String issuerTaxId,
            FiscalRecordOperation operation,
            FiscalDocumentType fiscalType,
            Customer customer) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("identificador", document.getId().toString());
        snapshot.put("tipo", document.getTipo().name());
        snapshot.put("estado", document.getEstado().name());
        snapshot.put("numero", document.getNumero());
        snapshot.put("fecha", document.getFecha().toString());
        snapshot.put("tiendaId", document.getTiendaId().toString());
        snapshot.put("clienteId", id(document.getClienteId()));
        snapshot.put("cliente", customer(customer));
        snapshot.put("nifEmisor", SpanishTaxId.validate(issuerTaxId));
        snapshot.put("operacionFiscal", operation.name());
        snapshot.put("tipoFiscal", fiscalType.name());
        if (isRectification(fiscalType)) {
            snapshot.put("tipoRectificativa", "S");
        }
        snapshot.put("proveedorId", id(document.getProveedorId()));
        snapshot.put("moneda", document.getMoneda());
        snapshot.put("descuentoGlobal", document.getDescuentoGlobal());
        snapshot.put("baseTotal", document.getBaseTotal());
        snapshot.put("impuestoTotal", document.getImpuestoTotal());
        snapshot.put("total", document.getTotal());
        snapshot.put("lineas", document.getLineas().stream()
                .sorted(Comparator.comparingInt(DocumentoLinea::getPosicion))
                .map(FiscalSnapshotFactory::line)
                .toList());
        snapshot.put("pagos", document.getPagos().stream()
                .sorted(Comparator.comparingInt(DocumentoPago::getPosicion))
                .map(FiscalSnapshotFactory::payment)
                .toList());
        return ImmutableJson.copy(snapshot);
    }

    private static Map<String, Object> customer(Customer customer) {
        if (customer == null) {
            return null;
        }
        var value = new LinkedHashMap<String, Object>();
        value.put("id", customer.getId().toString());
        value.put("tipoDocumento", customer.getDocumentType().name());
        value.put("numeroDocumento", customer.getDocumentNumber());
        value.put("nombreFiscal", customer.getFiscalName());
        value.put("direccion", address(customer.getFiscalAddress()));
        return value;
    }

    private static Map<String, Object> address(FiscalAddress address) {
        if (address == null) {
            return null;
        }
        var value = new LinkedHashMap<String, Object>();
        value.put("calle", address.getAddress());
        value.put("codigoPostal", address.getPostalCode());
        value.put("ciudad", address.getCity());
        value.put("provincia", address.getProvince());
        value.put("pais", address.getCountry());
        return value;
    }

    private static Map<String, Object> line(DocumentoLinea line) {
        var value = new LinkedHashMap<String, Object>();
        value.put("productoId", line.getProductoId().toString());
        value.put("posicion", line.getPosicion());
        value.put("cantidad", line.getCantidad());
        value.put("codigo", line.getCodigo());
        value.put("nombre", line.getNombre());
        value.put("tarifa", line.getTarifa());
        value.put("precioUnitario", line.getPrecioUnitario());
        value.put("descuento", line.getDescuento());
        value.put("impuestosIncluidos", line.isImpuestosIncluidos());
        value.put("regimenImpuesto", line.getRegimenImpuesto());
        value.put("porcentajeImpuesto", line.getPorcentajeImpuesto());
        value.put("base", line.getBase());
        value.put("impuesto", line.getImpuesto());
        value.put("total", line.getTotal());
        return value;
    }

    private static Map<String, Object> payment(DocumentoPago payment) {
        var value = new LinkedHashMap<String, Object>();
        value.put("metodoPagoId", payment.getMetodoPago().getId().toString());
        value.put("metodoPagoNombre", payment.getMetodoPago().getNombre());
        value.put("posicion", payment.getPosicion());
        value.put("importe", payment.getImporte());
        value.put("principal", payment.isPrincipal());
        value.put("entregado", payment.getEntregado());
        value.put("cambio", payment.getCambio());
        value.put("codigoVale", payment.getVoucherCode());
        return value;
    }

    private static String id(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isRectification(FiscalDocumentType type) {
        return type == FiscalDocumentType.R1
                || type == FiscalDocumentType.R2
                || type == FiscalDocumentType.R3
                || type == FiscalDocumentType.R4;
    }
}
