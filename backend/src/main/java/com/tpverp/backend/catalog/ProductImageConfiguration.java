package com.tpverp.backend.catalog;

import com.tpverp.backend.catalog.image.ProductImageStorage;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ProductImageConfiguration {

    @Bean
    ProductImageStorage productImageStorage(@Value("${tpv.product-images.directory}") Path root) {
        return new ProductImageStorage(root);
    }
}
