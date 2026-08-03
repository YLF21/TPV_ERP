package com.tpverp.backend.document;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ticket_regalo_linea")
public class GiftReceiptLine {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_regalo_id", nullable = false)
    private GiftReceipt giftReceipt;

    @Column(name = "documento_linea_origen_id", nullable = false)
    private UUID sourceDocumentLineId;

    @Column(name = "cantidad", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "posicion", nullable = false)
    private int position;

    @ElementCollection
    @CollectionTable(
            name = "ticket_regalo_linea_numero_serie",
            joinColumns = @JoinColumn(name = "ticket_regalo_linea_id"))
    @OrderColumn(name = "posicion")
    @Column(name = "numero_serie", nullable = false, length = 128)
    private List<String> serialNumbers = new ArrayList<>();

    protected GiftReceiptLine() {
    }

    GiftReceiptLine(
            GiftReceipt giftReceipt,
            UUID sourceDocumentLineId,
            BigDecimal quantity,
            int position,
            List<String> serialNumbers) {
        var normalized = Objects.requireNonNull(quantity, "quantity")
                .setScale(3, Money.ROUNDING);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("gift_receipt_quantity_must_be_positive");
        }
        if (position < 1) {
            throw new IllegalArgumentException("gift_receipt_position_must_be_positive");
        }
        this.id = UUID.randomUUID();
        this.giftReceipt = Objects.requireNonNull(giftReceipt, "giftReceipt");
        this.sourceDocumentLineId = Objects.requireNonNull(
                sourceDocumentLineId, "sourceDocumentLineId");
        this.quantity = normalized;
        this.position = position;
        this.serialNumbers = normalizedSerialNumbers(serialNumbers);
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceDocumentLineId() {
        return sourceDocumentLineId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public int getPosition() {
        return position;
    }

    public List<String> getSerialNumbers() {
        return List.copyOf(serialNumbers);
    }

    private static ArrayList<String> normalizedSerialNumbers(List<String> values) {
        var result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        var seen = new java.util.HashSet<String>();
        for (var value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("gift_receipt_serial_number_required");
            }
            var normalized = value.trim();
            if (!seen.add(normalized.toUpperCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("gift_receipt_serial_number_duplicated");
            }
            result.add(normalized);
        }
        return result;
    }
}
