package com.tpverp.backend.security.domain;

import com.tpverp.backend.organization.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PreRemove;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "rol")
public class Role {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tienda_id", nullable = false)
    private Store tienda;

    @Column(nullable = false, length = 64)
    private String nombre;

    @Column(nullable = false)
    private boolean protegido;

    @OneToMany(mappedBy = "rol", orphanRemoval = true)
    private Set<RolePermission> permisos = new LinkedHashSet<>();

    @Version
    private long version;

    protected Role() {
    }

    public Role(Store tienda, String nombre) {
        this.id = UUID.randomUUID();
        this.tienda = Objects.requireNonNull(tienda, "tienda");
        this.nombre = uppercase(nombre);
        this.protegido = "ADMIN".equals(this.nombre);
    }

    public String getNombre() {
        return nombre;
    }

    public UUID getId() {
        return id;
    }

    public boolean isProtegido() {
        return protegido;
    }

    public String authority() {
        return "ROLE_" + nombre;
    }

    public void renombrar(String nuevoNombre) {
        if (protegido) {
            throw new IllegalStateException("Un rol protegido no se puede renombrar");
        }
        this.nombre = uppercase(nuevoNombre);
    }

    public void conceder(Permission permiso) {
        permisos.add(new RolePermission(this, Objects.requireNonNull(permiso, "permiso")));
    }

    public Set<RolePermission> getPermisos() {
        return Set.copyOf(permisos);
    }

    public void replacePermissions(Set<Permission> newPermissions) {
        if (protegido) {
            throw new IllegalStateException("El rol ADMIN mantiene acceso total permanente");
        }
        permisos.clear();
        newPermissions.forEach(this::conceder);
    }

    @PreRemove
    public void validateDeletion() {
        if (protegido) {
            throw new IllegalStateException("Un rol protegido no se puede eliminar");
        }
    }

    private static String uppercase(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("nombre es obligatorio");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
