package com.tpverp.backend.security.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "permiso")
public class Permission {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 128)
    private String codigo;

    @Column(name = "translation_key", nullable = false)
    private String translationKey;

    @Column(nullable = false, length = 128)
    private String grupo;

    @Version
    private long version;

    protected Permission() {
    }

    public Permission(String codigo, String translationKey, String grupo) {
        this.id = UUID.randomUUID();
        this.codigo = required(codigo, "codigo").toUpperCase(Locale.ROOT);
        this.translationKey = required(translationKey, "translationKey");
        this.grupo = required(grupo, "grupo");
    }

    public String getCodigo() {
        return codigo;
    }

    public UUID getId() {
        return id;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    public String getGrupo() {
        return grupo;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}
