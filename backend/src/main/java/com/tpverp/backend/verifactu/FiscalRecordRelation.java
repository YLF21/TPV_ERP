package com.tpverp.backend.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "registro_fiscal_relacion")
@IdClass(FiscalRecordRelation.Key.class)
public class FiscalRecordRelation {

    @Id
    @Column(name = "cadena_id", nullable = false)
    private UUID chainId;

    @Id
    @Column(name = "registro_id", nullable = false)
    private UUID recordId;

    @Id
    @Column(name = "relacionado_id", nullable = false)
    private UUID relatedId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 16)
    private FiscalRelationType type;

    protected FiscalRecordRelation() {
    }

    public FiscalRecordRelation(
            UUID chainId,
            UUID recordId,
            UUID relatedId,
            FiscalRelationType type) {
        this.chainId = Objects.requireNonNull(chainId, "chainId");
        this.recordId = Objects.requireNonNull(recordId, "recordId");
        this.relatedId = Objects.requireNonNull(relatedId, "relatedId");
        this.type = Objects.requireNonNull(type, "type");
        if (recordId.equals(relatedId)) {
            throw new IllegalArgumentException("Un registro fiscal no puede relacionarse consigo mismo");
        }
    }

    public static final class Key implements Serializable {

        private UUID chainId;
        private UUID recordId;
        private UUID relatedId;
        private FiscalRelationType type;

        public Key() {
        }

        public Key(
                UUID chainId,
                UUID recordId,
                UUID relatedId,
                FiscalRelationType type) {
            this.chainId = chainId;
            this.recordId = recordId;
            this.relatedId = relatedId;
            this.type = type;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof Key key
                    && Objects.equals(chainId, key.chainId)
                    && Objects.equals(recordId, key.recordId)
                    && Objects.equals(relatedId, key.relatedId)
                    && type == key.type;
        }

        @Override
        public int hashCode() {
            return Objects.hash(chainId, recordId, relatedId, type);
        }
    }
}
