package com.tpverp.backend.document.template;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DocumentTemplateArtifactConfiguration {

    @Bean
    DocumentTemplateArtifactStorage documentTemplateArtifactStorage(
            @Value("${tpv.document-templates.directory}") Path root) {
        return new DocumentTemplateArtifactStorage(root);
    }
}
