package com.tpverp.backend.security.domain;

import com.tpverp.backend.organization.Store;
import com.tpverp.backend.shared.i18n.SupportedLanguage;
import com.tpverp.backend.terminal.Terminal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreRemove;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "usuario")
public class UserAccount {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tienda_id", nullable = false)
    private Store tienda;

    @Column(nullable = false, length = 64)
    private String nombre;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rol_id", nullable = false)
    private Role rol;

    @Column(nullable = false)
    private boolean protegido;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private SupportedLanguage idioma = SupportedLanguage.ES;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_terminal_id")
    private Terminal lastTerminal;

    @Version
    private long version;

    protected UserAccount() {
    }

    public UserAccount(Store tienda, String nombre, String passwordHash, Role rol) {
        this.id = UUID.randomUUID();
        this.tienda = Objects.requireNonNull(tienda, "tienda");
        this.nombre = uppercase(nombre);
        this.passwordHash = required(passwordHash, "passwordHash");
        this.rol = Objects.requireNonNull(rol, "rol");
        this.protegido = "ADMIN".equals(this.nombre);
    }

    public String getNombre() {
        return nombre;
    }

    public boolean isProtegido() {
        return protegido;
    }

    public UUID getId() {
        return id;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRol() {
        return rol;
    }

    public Store getTienda() {
        return tienda;
    }

    public boolean isActivo() {
        return activo;
    }

    public SupportedLanguage getIdioma() {
        return idioma;
    }

    public void cambiarIdioma(SupportedLanguage nuevoIdioma) {
        this.idioma = Objects.requireNonNullElse(nuevoIdioma, SupportedLanguage.ES);
    }

    public void renombrar(String nuevoNombre) {
        if (protegido) {
            throw new IllegalStateException("Un usuario protegido no se puede renombrar");
        }
        this.nombre = uppercase(nuevoNombre);
    }

    public void cambiarRol(Role nuevoRol) {
        if (protegido) {
            throw new IllegalStateException("El usuario ADMIN no puede cambiar de rol");
        }
        this.rol = Objects.requireNonNull(nuevoRol, "nuevoRol");
    }

    public void cambiarPassword(String nuevoPasswordHash) {
        this.passwordHash = required(nuevoPasswordHash, "nuevoPasswordHash");
    }

    public void deactivate() {
        if (protegido) {
            throw new IllegalStateException("El usuario ADMIN no se puede deactivate");
        }
        activo = false;
    }

    public void activate() {
        activo = true;
    }

    @PreRemove
    public void validateDeletion() {
        if (protegido) {
            throw new IllegalStateException("Un usuario protegido no se puede eliminar");
        }
    }

    private static String uppercase(String value) {
        return required(value, "nombre").toUpperCase(Locale.ROOT);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }
}
