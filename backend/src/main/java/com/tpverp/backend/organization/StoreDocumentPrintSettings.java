package com.tpverp.backend.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "configuracion_documento_impreso_tienda")
public class StoreDocumentPrintSettings {

    @Id
    @Column(name = "tienda_id")
    private UUID storeId;

    @Column(name = "logo_id")
    private UUID logoId;

    @Column(name = "observaciones_ticket", length = 2000)
    private String ticketObservations;

    @Column(name = "observaciones_factura", length = 2000)
    private String invoiceObservations;

    @Column(name = "observaciones_albaran", length = 2000)
    private String deliveryNoteObservations;

    @Enumerated(EnumType.STRING)
    @Column(name = "estilo_ticket", nullable = false, length = 16)
    private TicketPrintStyle ticketStyle = TicketPrintStyle.PRINCIPAL;

    @Version
    private long version;

    protected StoreDocumentPrintSettings() {
    }

    public StoreDocumentPrintSettings(UUID storeId) {
        this.storeId = java.util.Objects.requireNonNull(storeId, "storeId");
    }

    public UUID getStoreId() { return storeId; }
    public UUID getLogoId() { return logoId; }
    public String getTicketObservations() { return ticketObservations; }
    public String getInvoiceObservations() { return invoiceObservations; }
    public String getDeliveryNoteObservations() { return deliveryNoteObservations; }
    public TicketPrintStyle getTicketStyle() {
        return ticketStyle == null ? TicketPrintStyle.PRINCIPAL : ticketStyle;
    }

    public void useLogo(UUID value) { logoId = value; }
    public void useTicketStyle(TicketPrintStyle value) {
        ticketStyle = java.util.Objects.requireNonNull(value, "ticketStyle");
    }

    public void updateObservations(String ticket, String invoice, String deliveryNote) {
        ticketObservations = observations(ticket);
        invoiceObservations = observations(invoice);
        deliveryNoteObservations = observations(deliveryNote);
    }

    private static String observations(String value) {
        String normalized = value == null || value.isBlank() ? null : value.trim();
        if (normalized != null && normalized.length() > 2000) {
            throw new IllegalArgumentException("document_print_observations_too_long");
        }
        return normalized;
    }
}
