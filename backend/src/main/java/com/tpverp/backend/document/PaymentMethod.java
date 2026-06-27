package com.tpverp.backend.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "metodo_pago")
public class PaymentMethod {

    @Id
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(nullable = false, length = 64)
    private String nombre;

    @Column(nullable = false)
    private boolean protegido;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(name = "requiere_referencia", nullable = false)
    private boolean requiereReferencia;

    @Column(name = "abre_caja_registradora", nullable = false)
    private boolean abreCajaRegistradora;

    @Version
    private long version;

    protected PaymentMethod() {
    }

    public PaymentMethod(UUID empresaId, String nombre, boolean protegido) {
        this.id = UUID.randomUUID();
        this.empresaId = Objects.requireNonNull(empresaId, "empresaId");
        this.nombre = required(nombre).toUpperCase(Locale.ROOT);
        this.protegido = protegido;
        this.abreCajaRegistradora = "EFECTIVO".equals(this.nombre);
    }

    public PaymentMethod(
            UUID empresaId,
            String nombre,
            boolean protegido,
            boolean requiereReferencia,
            boolean abreCajaRegistradora) {
        this(empresaId, nombre, protegido);
        configure(requiereReferencia, abreCajaRegistradora);
    }

    public UUID getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public boolean isActivo() {
        return activo;
    }

    // Changes availability without deleting the method or its history.
    public void setActivo(boolean activo) {
        this.activo = activo;
    }

    // Updates operational flags controlled by ADMIN.
    public void configure(boolean requiereReferencia, boolean abreCajaRegistradora) {
        this.requiereReferencia = requiereReferencia;
        this.abreCajaRegistradora = abreCajaRegistradora;
    }

    public UUID getEmpresaId() {
        return empresaId;
    }

    public boolean isProtegido() {
        return protegido;
    }

    public boolean isRequiereReferencia() {
        return requiereReferencia;
    }

    public boolean isAbreCajaRegistradora() {
        return abreCajaRegistradora;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("nombre es obligatorio");
        }
        return value.trim();
    }
}
