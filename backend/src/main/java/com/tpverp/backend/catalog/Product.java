package com.tpverp.backend.catalog;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "producto")
public class Product {

    @Id
    private UUID id;

    @Column(name = "tienda_id", nullable = false)
    private UUID storeId;

    @Column(name = "familia_id", nullable = false)
    private UUID familyId;

    @Column(name = "subfamilia_id")
    private UUID subfamilyId;

    @Column(name = "impuesto_id", nullable = false)
    private UUID taxId;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, length = 16)
    private ProductType productType = ProductType.UNIT;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 32)
    private DiscountType discountType = DiscountType.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_use_mode", nullable = false, length = 32)
    private PriceUseMode priceUseMode = PriceUseMode.NORMAL;

    @Column(nullable = false)
    private String nombre;

    @Column(columnDefinition = "text")
    private String descripcion;

    @Column(columnDefinition = "text")
    private String comments;

    @Column(name = "precio_compra", nullable = false, precision = 19, scale = 2)
    private BigDecimal purchasePrice;

    @Column(name = "impuestos_incluidos", nullable = false)
    private boolean taxesIncluded;

    @Column(name = "oferta_activa", nullable = false)
    private boolean offerActive;

    @Column(name = "oferta_desde")
    private LocalDate offerFrom;

    @Column(name = "oferta_hasta")
    private LocalDate offerUntil;

    @Column(name = "oferta_descuento_porcentaje", precision = 5, scale = 2)
    private BigDecimal offerDiscountPercent;

    @Column(name = "imagen_id")
    private String imageId;

    @Column(name = "imagen_tipo")
    private String imageType;

    @Column(name = "imagen_tamano")
    private Long imageSize;

    @Column(name = "imagen_hash")
    private String imageHash;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "producto_id", insertable = false, updatable = false)
    private List<ProductIdentifier> identifiers = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "producto_id", insertable = false, updatable = false)
    private List<ProductPrice> prices = new ArrayList<>();

    @Version
    private long version;

    protected Product() {
    }

    public Product(
            UUID storeId,
            UUID familyId,
            UUID subfamilyId,
            UUID taxId,
            String name,
            String description,
            BigDecimal purchasePrice,
            boolean taxesIncluded) {
        this(storeId, familyId, subfamilyId, taxId, ProductType.UNIT, DiscountType.NORMAL,
                name, description, null, purchasePrice, taxesIncluded);
    }

    public Product(
            UUID storeId,
            UUID familyId,
            UUID subfamilyId,
            UUID taxId,
            ProductType productType,
            DiscountType discountType,
            String name,
            String description,
            String comments,
            BigDecimal purchasePrice,
            boolean taxesIncluded) {
        this.id = UUID.randomUUID();
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.subfamilyId = subfamilyId;
        this.taxId = Objects.requireNonNull(taxId, "taxId");
        this.productType = Objects.requireNonNull(productType, "productType");
        this.discountType = Objects.requireNonNull(discountType, "discountType");
        this.nombre = CatalogText.normalized(name, "nombre");
        this.descripcion = CatalogText.optional(description);
        this.comments = CatalogText.optional(comments);
        this.purchasePrice = nonNegative(purchasePrice, "precioCompra");
        this.taxesIncluded = taxesIncluded;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getName() {
        return nombre;
    }

    public boolean isOfferActive() {
        return offerActive;
    }

    public String getCode() {
        return identifier(IdentifierType.CODIGO);
    }

    public String getBarcode() {
        return identifier(IdentifierType.CODIGO_BARRAS);
    }

    public BigDecimal getSalePrice() {
        return price(PriceTier.VENTA);
    }

    public BigDecimal getMemberPrice() {
        return price(PriceTier.MEMBER);
    }

    public BigDecimal getWholesalePrice() {
        return price(PriceTier.MAYORISTA);
    }

    public BigDecimal getOfferPrice() {
        return price(PriceTier.OFERTA);
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public UUID getSubfamilyId() {
        return subfamilyId;
    }

    public UUID getTaxId() {
        return taxId;
    }

    public ProductType getProductType() {
        return productType;
    }

    public DiscountType getDiscountType() {
        return discountType;
    }

    public PriceUseMode getPriceUseMode() {
        return priceUseMode;
    }

    public String getDescription() {
        return descripcion;
    }

    public String getComments() {
        return comments;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public boolean isTaxesIncluded() {
        return taxesIncluded;
    }

    public LocalDate getOfferFrom() {
        return offerFrom;
    }

    public LocalDate getOfferUntil() {
        return offerUntil;
    }

    public BigDecimal getOfferDiscountPercent() {
        return offerDiscountPercent;
    }

    public UUID getImageId() {
        return imageId == null ? null : UUID.fromString(imageId);
    }

    public String getImageType() {
        return imageType;
    }

    public Long getImageSize() {
        return imageSize;
    }

    public String getImageHash() {
        return imageHash;
    }

    public void update(
            UUID familyId,
            UUID subfamilyId,
            UUID taxId,
            String name,
            String description,
            BigDecimal purchasePrice,
            boolean taxesIncluded) {
        update(familyId, subfamilyId, taxId, productType, discountType,
                name, description, comments, purchasePrice, taxesIncluded);
    }

    public void update(
            UUID familyId,
            UUID subfamilyId,
            UUID taxId,
            ProductType productType,
            DiscountType discountType,
            String name,
            String description,
            String comments,
            BigDecimal purchasePrice,
            boolean taxesIncluded) {
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.subfamilyId = subfamilyId;
        this.taxId = Objects.requireNonNull(taxId, "taxId");
        this.productType = Objects.requireNonNull(productType, "productType");
        this.discountType = Objects.requireNonNull(discountType, "discountType");
        this.nombre = CatalogText.normalized(name, "nombre");
        this.descripcion = CatalogText.optional(description);
        this.comments = CatalogText.optional(comments);
        this.purchasePrice = nonNegative(purchasePrice, "precioCompra");
        this.taxesIncluded = taxesIncluded;
    }

    public void attachImage(UUID imageId, String imageType, long imageSize, String imageHash) {
        this.imageId = Objects.requireNonNull(imageId, "imageId").toString();
        this.imageType = CatalogText.optional(imageType);
        if (this.imageType == null) {
            throw new IllegalArgumentException("tipoImagen es obligatorio");
        }
        this.imageSize = imageSize;
        this.imageHash = CatalogText.normalized(imageHash, "hashImagen");
    }
    // Actualiza solo metadatos; los bytes se escriben antes en almacenamiento local.

    public void clearImage() {
        imageId = null;
        imageType = null;
        imageSize = null;
        imageHash = null;
    }

    public void replaceIdentifier(IdentifierType type, String value) {
        identifiers.stream()
                .filter(identifier -> identifier.getType() == type)
                .findFirst()
                .ifPresentOrElse(
                        identifier -> identifier.replaceValue(value),
                        () -> identifiers.add(new ProductIdentifier(storeId, id, type, value)));
    }

    public void removeIdentifier(IdentifierType type) {
        identifiers.removeIf(identifier -> identifier.getType() == type);
    }

    public String identifier(IdentifierType type) {
        return identifiers.stream()
                .filter(identifier -> identifier.getType() == type)
                .map(ProductIdentifier::getValue)
                .findFirst()
                .orElse(null);
    }

    public void setPrice(PriceTier tier, BigDecimal amount) {
        prices.stream()
                .filter(price -> price.getTier() == tier)
                .findFirst()
                .ifPresentOrElse(
                        price -> price.replaceAmount(amount),
                        () -> prices.add(new ProductPrice(id, tier, amount)));
    }

    public BigDecimal price(PriceTier tier) {
        return prices.stream()
                .filter(price -> price.getTier() == tier)
                .findFirst()
                .map(ProductPrice::getAmount)
                .orElse(null);
    }

    public void configureOffer(boolean active, LocalDate from, LocalDate until) {
        if (active && price(PriceTier.OFERTA) == null) {
            throw new IllegalArgumentException("Una oferta activa necesita precio OFERTA");
        }
        if (active && from == null) {
            throw new IllegalArgumentException("Una oferta activa necesita fecha inicial");
        }
        if (until != null && (from == null || until.isBefore(from))) {
            throw new IllegalArgumentException("La fecha final de oferta no es valida");
        }
        offerActive = active;
        offerFrom = from;
        offerUntil = until;
    }

    public void configurePriceUse(PriceUseMode mode, BigDecimal discountPercent) {
        priceUseMode = Objects.requireNonNull(mode, "priceUseMode");
        if (discountPercent != null && (discountPercent.signum() < 0
                || discountPercent.compareTo(new BigDecimal("100")) > 0)) {
            throw new IllegalArgumentException("El descuento de oferta debe estar entre 0 y 100");
        }
        offerDiscountPercent = discountPercent;
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " no puede ser negativo");
        }
        return value;
    }
}
