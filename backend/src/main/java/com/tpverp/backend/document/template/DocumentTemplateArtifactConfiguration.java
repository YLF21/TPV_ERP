package com.tpverp.backend.document.template;

import java.nio.file.Path;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
class DocumentTemplateArtifactConfiguration {

    @Bean
    DocumentTemplateArtifactStorage documentTemplateArtifactStorage(
            @Value("${tpv.document-templates.directory}") Path root) {
        return new DocumentTemplateArtifactStorage(root);
    }

    @Bean
    @Order(20)
    ApplicationRunner systemDocumentTemplateRunner(
            SystemDocumentTemplateBootstrap bootstrap) {
        return arguments -> bootstrap.initialize();
    }
}
