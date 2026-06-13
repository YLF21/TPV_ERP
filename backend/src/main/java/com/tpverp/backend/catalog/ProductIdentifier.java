package com.tpverp.backend.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "producto_identificador")
public class ProductIdentifier {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(name = "producto_id", nullable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdentifierType tipo;

    @Column(nullable = false, length = 128)
    private String valor;

    @Version
    private long version;

    protected ProductIdentifier() {
    }

    public ProductIdentifier(UUID storeId, UUID productId, IdentifierType type, String value) {
        this.id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.tipo = Objects.requireNonNull(type, "type");
        this.valor = CatalogText.normalized(value, "valor");
    }

    public IdentifierType getType() {
        return tipo;
    }

    public String getValue() {
        return valor;
    }

    public void replaceValue(String value) {
        valor = CatalogText.normalized(value, "valor");
    }
}
