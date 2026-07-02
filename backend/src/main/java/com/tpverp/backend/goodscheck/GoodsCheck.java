package com.tpverp.backend.goodscheck;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "comprobacion_mercancia")
public class GoodsCheck {

    @Id
    private UUID id;
    @Column(name = "documento_id", nullable = false)
    private UUID documentoId;
    @Column(name = "tienda_id", nullable = false)
    private UUID tiendaId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private GoodsCheckStatus estado = GoodsCheckStatus.ABIERTA;
    @Column(name = "creado_por", nullable = false)
    private UUID creadoPor;
    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;
    @Column(name = "cerrado_por")
    private UUID cerradoPor;
    @Column(name = "cerrado_en")
    private Instant cerradoEn;
    @OneToMany(mappedBy = "comprobacion", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("productoId")
    private List<GoodsCheckLine> lineas = new ArrayList<>();
    @Version
    private long version;

    protected GoodsCheck() {
    }

    public GoodsCheck(UUID documentId, UUID storeId, UUID userId, Instant now) {
        id = UUID.randomUUID();
        documentoId = Objects.requireNonNull(documentId, "documentId");
        tiendaId = Objects.requireNonNull(storeId, "storeId");
        creadoPor = Objects.requireNonNull(userId, "userId");
        creadoEn = Objects.requireNonNull(now, "now");
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentoId() {
        return documentoId;
    }

    public UUID getTiendaId() {
        return tiendaId;
    }

    public GoodsCheckStatus getEstado() {
        return estado;
    }

    public List<GoodsCheckLine> getLineas() {
        return List.copyOf(lineas);
    }

    public void addLine(UUID productId, BigDecimal expectedQuantity) {
        lineas.add(new GoodsCheckLine(this, productId, expectedQuantity));
    }

    public void register(UUID productId, BigDecimal quantity, UUID userId, UUID terminalId, Instant now) {
        requireOpen();
        if (Objects.requireNonNull(quantity, "quantity").signum() == 0) {
            throw new IllegalArgumentException("message.goods_check.quantity_required");
        }
        lineas.stream()
                .filter(line -> line.getProductoId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("message.goods_check.product_not_in_document"))
                .addQuantity(quantity, userId, terminalId, now);
    }

    public void close(UUID userId, Instant now) {
        requireOpen();
        cerradoPor = Objects.requireNonNull(userId, "userId");
        cerradoEn = Objects.requireNonNull(now, "now");
        estado = lineas.stream().allMatch(line -> line.getCantidadEsperada()
                .compareTo(line.getCantidadRegistrada()) == 0)
                ? GoodsCheckStatus.COMPLETA : GoodsCheckStatus.CON_DIFERENCIAS;
    }
    // Freezes the check and derives the final status from expected versus registered quantities.

    private void requireOpen() {
        if (estado != GoodsCheckStatus.ABIERTA) {
            throw new IllegalStateException("message.goods_check.closed");
        }
    }
}
