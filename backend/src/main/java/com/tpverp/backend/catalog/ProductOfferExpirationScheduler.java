package com.tpverp.backend.catalog;

import com.tpverp.backend.organization.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "tpv.catalog", name = "offer-expiration-enabled", matchIfMissing = true)
public class ProductOfferExpirationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(ProductOfferExpirationScheduler.class);
    private final StoreRepository stores;
    private final ProductOfferExpirationService expiration;

    public ProductOfferExpirationScheduler(StoreRepository stores, ProductOfferExpirationService expiration) {
        this.stores = stores;
        this.expiration = expiration;
    }

    @Scheduled(fixedDelayString = "${tpv.catalog.offer-expiration-delay-ms:60000}",
            initialDelayString = "${tpv.catalog.offer-expiration-initial-delay-ms:10000}")
    public void expire() {
        try {
            for (var store : stores.findAll()) {
                try {
                    expiration.expire(store.getId());
                } catch (RuntimeException failure) {
                    LOG.warn("PRODUCT_OFFER_EXPIRATION_PENDING store={}", store.getId());
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("PRODUCT_OFFER_EXPIRATION_UNAVAILABLE");
        }
    }
}
