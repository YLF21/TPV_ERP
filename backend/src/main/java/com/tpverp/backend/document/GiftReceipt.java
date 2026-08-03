package com.tpverp.backend.document;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ticket_regalo")
public class GiftReceipt {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(name = "documento_origen_id", nullable = false)
    private UUID sourceDocumentId;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(nullable = false, length = 32)
    private String codigo;

    @Column(name = "creado_por", nullable = false)
    private UUID createdBy;

    @Column(name = "terminal_id")
    private UUID terminalId;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "giftReceipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    private List<GiftReceiptLine> lines = new ArrayList<>();

    @Version
    private long version;

    protected GiftReceipt() {
    }

    public GiftReceipt(
            UUID storeId,
            UUID sourceDocumentId,
            UUID requestId,
            String code,
            UUID createdBy,
            UUID terminalId,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.sourceDocumentId = Objects.requireNonNull(sourceDocumentId, "sourceDocumentId");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.codigo = required(code, "code");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.terminalId = terminalId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public void addLine(
            UUID sourceLineId,
            java.math.BigDecimal quantity,
            List<String> serialNumbers) {
        lines.add(new GiftReceiptLine(
                this, sourceLineId, quantity, lines.size() + 1, serialNumbers));
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getSourceDocumentId() {
        return sourceDocumentId;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getCode() {
        return codigo;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<GiftReceiptLine> getLines() {
        return List.copyOf(lines);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
