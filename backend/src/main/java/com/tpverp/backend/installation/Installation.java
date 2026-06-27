package com.tpverp.backend.installation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "instalacion")
public class Installation {

    @Id
    private UUID id;

    @Column(name = "singleton_key", nullable = false, unique = true)
    private boolean singletonKey = true;

    @Column(nullable = false, unique = true, length = 32)
    private String referencia;

    @Column(name = "public_key", nullable = false, columnDefinition = "text")
    private String publicKey;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn;

    @Column(name = "demo_hasta", nullable = false)
    private Instant demoHasta;

    @Version
    private long version;

    protected Installation() {
    }

    public Installation(String referencia, String publicKey, Instant creadaEn) {
        this.id = UUID.randomUUID();
        this.referencia = required(referencia, "referencia");
        this.publicKey = required(publicKey, "publicKey");
        this.creadaEn = Objects.requireNonNull(creadaEn, "creadaEn");
        this.demoHasta = creadaEn.plus(30, ChronoUnit.DAYS);
    }

    public UUID getId() {
        return id;
    }

    public String getReferencia() {
        return referencia;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public Instant getCreadaEn() {
        return creadaEn;
    }

    public Instant getDemoHasta() {
        return demoHasta;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}
