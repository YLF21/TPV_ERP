package com.tpverp.backend.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductView(
        UUID id,
        UUID storeId,
        UUID familyId,
        UUID subfamilyId,
        UUID taxId,
        ProductType productType,
        DiscountType discountType,
        PriceUseMode priceUseMode,
        String name,
        String description,
        String comments,
        BigDecimal purchasePrice,
        BigDecimal purchaseDiscountPercent,
        BigDecimal stockMin,
        BigDecimal stockMax,
        BigDecimal packageQuantity,
        boolean active,
        boolean taxesIncluded,
        boolean offerActive,
        LocalDate offerFrom,
        LocalDate offerUntil,
        BigDecimal offerDiscountPercent,
        UUID imageId,
        String imageType,
        Long imageSize,
        String imageHash,
        long version,
        String code,
        String barcode,
        String barcode2,
        BigDecimal salePrice,
        BigDecimal memberPrice,
        BigDecimal wholesalePrice,
        BigDecimal offerPrice) {

    public static ProductView publicView(Product product) {
        return from(product, false);
    }

    public static ProductView managementView(Product product) {
        return from(product, true);
    }

    private static ProductView from(Product product, boolean includePurchaseFields) {
        return new ProductView(
                product.getId(),
                product.getStoreId(),
                product.getFamilyId(),
                product.getSubfamilyId(),
                product.getTaxId(),
                product.getProductType(),
                product.getDiscountType(),
                product.getPriceUseMode(),
                product.getName(),
                product.getDescription(),
                product.getComments(),
                includePurchaseFields ? product.getPurchasePrice() : null,
                includePurchaseFields ? product.getPurchaseDiscountPercent() : null,
                product.getStockMin(),
                product.getStockMax(),
                product.getPackageQuantity(),
                product.isActive(),
                product.isTaxesIncluded(),
                product.isOfferActive(),
                product.getOfferFrom(),
                product.getOfferUntil(),
                product.getOfferDiscountPercent(),
                product.getImageId(),
                product.getImageType(),
                product.getImageSize(),
                product.getImageHash(),
                product.getVersion(),
                product.getCode(),
                product.getBarcode(),
                product.getBarcode2(),
                product.getSalePrice(),
                product.getMemberPrice(),
                product.getWholesalePrice(),
                product.getOfferPrice());
    }
}
