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

    @Version
    private long version;

    protected PaymentMethod() {
    }

    public PaymentMethod(UUID empresaId, String nombre, boolean protegido) {
        this.id = UUID.randomUUID();
        this.empresaId = Objects.requireNonNull(empresaId, "empresaId");
        this.nombre = required(nombre).toUpperCase(Locale.ROOT);
        this.protegido = protegido;
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
        if (protegido && !activo) {
            throw new IllegalStateException("message.payment_method.protected_cannot_deactivate");
        }
        this.activo = activo;
    }

    public UUID getEmpresaId() {
        return empresaId;
    }

    public boolean isProtegido() {
        return protegido;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("nombre es obligatorio");
        }
        return value.trim();
    }
}
