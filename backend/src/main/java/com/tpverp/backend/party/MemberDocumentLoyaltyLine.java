package com.tpverp.backend.party;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "member_document_loyalty_line")
public class MemberDocumentLoyaltyLine {

    @Id
    @Column(name = "documento_linea_id")
    private UUID documentLineId;

    @Column(name = "documento_id", nullable = false)
    private UUID documentId;

    @Column(nullable = false)
    private boolean eligible;

    @Column(name = "eligible_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal eligibleAmount;

    protected MemberDocumentLoyaltyLine() {
    }

    public MemberDocumentLoyaltyLine(
            UUID documentId,
            UUID documentLineId,
            boolean eligible,
            BigDecimal eligibleAmount) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.documentLineId = Objects.requireNonNull(documentLineId, "documentLineId");
        this.eligible = eligible;
        this.eligibleAmount = PartyValues.money(eligibleAmount);
        if (this.eligibleAmount.signum() < 0
                || (!eligible && this.eligibleAmount.signum() != 0)) {
            throw new IllegalArgumentException(
                    "El importe elegible de la linea no es valido");
        }
    }

    public UUID getDocumentLineId() {
        return documentLineId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public boolean isEligible() {
        return eligible;
    }

    public BigDecimal getEligibleAmount() {
        return eligibleAmount;
    }
}
