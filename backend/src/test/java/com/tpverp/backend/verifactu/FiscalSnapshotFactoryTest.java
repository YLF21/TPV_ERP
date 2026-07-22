package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.document.DocumentPayment;
import com.tpverp.backend.document.PaymentMethod;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRate;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.FiscalAddress;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalSnapshotFactoryTest {

    @Test
    void construyeUnaCopiaFiscalCompletaOrdenadaEInmutable() {
        var companyId = UUID.randomUUID();
        var company = new Company("B12345674", "Company", address());
        var customer = new Customer(
                company, "Cliente Fiscal", DocumentType.NIF, "12345678Z",
                new FiscalAddress(
                        "Calle Cliente 1", "35001", "Las Palmas",
                        "Las Palmas", "ES"),
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2027, 1, 2), UUID.randomUUID(), new BigDecimal("5.00"));
        setParties(document, customer.getId(), null);
        document.addLine(line(document, 2, "B"));
        document.addLine(line(document, 1, "A"));
        var cash = new PaymentMethod(companyId, "Efectivo", false);
        document.addPayment(new DocumentPayment(
                document, cash, 1, new BigDecimal("20.00"), true,
                new BigDecimal("20.00"), BigDecimal.ZERO,
                Instant.parse("2027-01-02T10:00:00Z")));
        document.confirm(
                "001-270102-000001", UUID.randomUUID(),
                Instant.parse("2027-01-02T10:00:00Z"), true);

        var snapshot = new FiscalSnapshotFactory().create(
                document, " b12345674 ", FiscalRecordOperation.ALTA,
                FiscalDocumentType.F2, customer);

        assertThat(snapshot)
                .containsEntry("identificador", document.getId().toString())
                .containsEntry("tipo", "TICKET")
                .containsEntry("estado", "CONFIRMADO")
                .containsEntry("numero", "001-270102-000001")
                .containsEntry("fecha", "2027-01-02")
                .containsEntry("tiendaId", document.getTiendaId().toString())
                .containsEntry("clienteId", document.getClienteId().toString())
                .containsEntry("nifEmisor", "B12345674")
                .containsEntry("operacionFiscal", "ALTA")
                .containsEntry("tipoFiscal", "F2")
                .containsEntry("proveedorId", null)
                .containsEntry("moneda", "EUR")
                .containsEntry("descuentoGlobal", new BigDecimal("5.00"))
                .containsEntry("baseTotal", document.getBaseTotal())
                .containsEntry("impuestoTotal", document.getImpuestoTotal())
                .containsEntry("total", document.getTotal());
        assertThat(map(snapshot.get("cliente")))
                .containsEntry("id", customer.getId().toString())
                .containsEntry("tipoDocumento", "NIF")
                .containsEntry("numeroDocumento", "12345678Z")
                .containsEntry("nombreFiscal", "Cliente Fiscal");
        assertThat(map(map(snapshot.get("cliente")).get("direccion")))
                .containsEntry("calle", "Calle Cliente 1")
                .containsEntry("codigoPostal", "35001")
                .containsEntry("ciudad", "Las Palmas")
                .containsEntry("provincia", "Las Palmas")
                .containsEntry("pais", "ES");
        var lines = list(snapshot.get("lineas"));
        assertThat(lines).extracting(value -> map(value).get("posicion"))
                .containsExactly(1, 2);
        assertThat(map(lines.getFirst()))
                .containsKeys(
                        "productoId", "tipoLinea", "promocionId", "cuponPromocionalId",
                        "cantidad", "codigo", "nombre", "tarifa", "precioUnitario",
                        "descuento", "impuestosIncluidos", "regimenImpuesto",
                        "porcentajeImpuesto", "base", "impuesto", "total")
                .containsEntry("tipoLinea", "PRODUCT")
                .containsEntry("promocionId", null)
                .containsEntry("cuponPromocionalId", null);
        assertThat(map(list(snapshot.get("pagos")).getFirst()))
                .containsEntry("metodoPagoId", cash.getId().toString())
                .containsEntry("metodoPagoNombre", "EFECTIVO")
                .containsEntry("posicion", 1)
                .containsEntry("importe", new BigDecimal("20.00"))
                .containsEntry("principal", true)
                .containsEntry("entregado", new BigDecimal("20.00"))
                .containsEntry("cambio", BigDecimal.ZERO.setScale(2));
        assertThatThrownBy(() -> snapshot.put("total", BigDecimal.ZERO))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map(lines.getFirst()).put("codigo", "ALTERADO"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void noInventaMetodoRectificativoSinElFlujoFiscalVinculado() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.RECTIFICATIVA_VENTA,
                LocalDate.of(2027, 1, 2), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(line(document, 1, "R"));
        document.confirm(
                "FRV-001-27-000001", UUID.randomUUID(),
                Instant.parse("2027-01-02T10:00:00Z"), false);

        var snapshot = new FiscalSnapshotFactory().create(
                document, "B12345674", FiscalRecordOperation.ALTA,
                FiscalDocumentType.R1, null);

        assertThat(snapshot).doesNotContainKey("tipoRectificativa");
    }

    @Test
    void incluyeNumeroDeTicketEnFacturaSustitutiva() {
        var invoice = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2027, 1, 2), UUID.randomUUID(), BigDecimal.ZERO);
        invoice.addLine(line(invoice, 1, "F"));
        setNumTicket(invoice, "001-270102-000001");
        invoice.confirm(
                "FV-001-27-000001", UUID.randomUUID(),
                Instant.parse("2027-01-02T10:00:00Z"), false);

        var snapshot = new FiscalSnapshotFactory().create(
                invoice, "B12345674", FiscalRecordOperation.ALTA,
                FiscalDocumentType.F3, null);

        assertThat(snapshot).containsEntry("numTicket", "001-270102-000001");
    }

    @Test
    void incluyeMetadatosPromocionalesEnLineasEspeciales() {
        var promotionId = UUID.randomUUID();
        var couponId = UUID.randomUUID();
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.TICKET,
                LocalDate.of(2027, 1, 2), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(DocumentLine.promotion(
                document, 1, "CUPON BIENVENIDA", new BigDecimal("-1.00"),
                true, "IVA", new BigDecimal("21.00"), promotionId, couponId));
        document.confirm(
                "001-270102-000001", UUID.randomUUID(),
                Instant.parse("2027-01-02T10:00:00Z"), false);

        var snapshot = new FiscalSnapshotFactory().create(
                document, "B12345674", FiscalRecordOperation.ALTA,
                FiscalDocumentType.F2, null);

        assertThat(map(list(snapshot.get("lineas")).getFirst()))
                .containsEntry("productoId", null)
                .containsEntry("tipoLinea", "PROMOTIONAL_COUPON")
                .containsEntry("promocionId", promotionId.toString())
                .containsEntry("cuponPromocionalId", couponId.toString());
    }

    private static DocumentLine line(CommercialDocument document, int position, String code) {
        return new DocumentLine(
                document, UUID.randomUUID(), position, 1, code, "Producto " + code,
                "VENTA", new BigDecimal("10.00"), BigDecimal.ZERO, true,
                "IGIC", new BigDecimal("7.00"));
    }

    private static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
    }

    private static void setParties(CommercialDocument document, UUID customerId, UUID supplierId) {
        try {
            var method = CommercialDocument.class.getDeclaredMethod(
                    "setParties", UUID.class, UUID.class, String.class);
            method.setAccessible(true);
            method.invoke(document, customerId, supplierId, null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void setNumTicket(CommercialDocument document, String ticketNumber) {
        try {
            var method = CommercialDocument.class.getDeclaredMethod("setNumTicket", String.class);
            method.setAccessible(true);
            method.invoke(document, ticketNumber);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
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
