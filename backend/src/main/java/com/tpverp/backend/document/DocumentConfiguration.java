package com.tpverp.backend.document;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DocumentConfiguration {

    @Bean
    @ConditionalOnMissingBean(StockDocumentGateway.class)
    StockDocumentGateway stockDocumentGateway() {
        return new StockDocumentGateway() {
            @Override
            public boolean confirm(CommercialDocument document) {
                return false;
            }

            @Override
            public boolean cancel(CommercialDocument document) {
                return false;
            }
        };
    }
}
