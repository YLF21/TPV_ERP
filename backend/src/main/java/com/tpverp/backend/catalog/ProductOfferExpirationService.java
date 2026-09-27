package com.tpverp.backend.catalog;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductOfferExpirationService {
    private final ProductRepository products;
    private final StoreRepository stores;
    private final AuditService audit;
    private final Clock clock;
    private final ProductCommercialHistoryService commercialHistory;

    public ProductOfferExpirationService(ProductRepository products, StoreRepository stores,
            AuditService audit, Clock clock, ProductCommercialHistoryService commercialHistory) {
        this.products = products;
        this.stores = stores;
        this.audit = audit;
        this.clock = clock;
        this.commercialHistory = commercialHistory;
    }

    @Transactional
    public void expire(UUID storeId) {
        var store = stores.findById(storeId).orElseThrow();
        var date = LocalDate.now(clock.withZone(ZoneId.of(store.getTimezone())));
        var ids = products.findExpiredOfferIds(storeId, date, PageRequest.of(0, 250));
        if (ids.isEmpty()) return;

        // Use the same store -> product lock order as manual/bulk catalogue edits.
        stores.findByIdForUpdate(storeId).orElseThrow();
        for (var product : products.findAllByStoreIdAndIdInForUpdate(storeId, ids)) {
            commercialHistory.recordOffer(product);
            var beforeMode = product.getPriceUseMode();
            var beforeDiscount = product.getDiscountType();
            boolean beforeActive = product.isOfferActive();
            // Recheck after locking: an operator may have extended the offer.
            if (!product.expireOffer(date)) continue;
            commercialHistory.recordProduct(product);
            var details = new LinkedHashMap<String, Object>();
            details.put("productId", product.getId().toString());
            details.put("businessDate", date.toString());
            details.put("offerFrom", product.getOfferFrom() == null ? null : product.getOfferFrom().toString());
            details.put("offerUntil", product.getOfferUntil().toString());
            details.put("offerPrice", product.getOfferPrice());
            details.put("offerDiscountPercent", product.getOfferDiscountPercent());
            details.put("previousPriceUseMode", beforeMode.name());
            details.put("priceUseMode", product.getPriceUseMode().name());
            details.put("previousDiscountType", beforeDiscount.name());
            details.put("discountType", product.getDiscountType().name());
            details.put("previousOfferActive", beforeActive);
            details.put("offerActive", false);
            audit.recordSystem(store, "PRODUCT_OFFER_EXPIRED", AuditResult.EXITO, details);
        }
        // JPA increments the product version; concurrent stale edits remain protected.
        products.flush();
    }
}
