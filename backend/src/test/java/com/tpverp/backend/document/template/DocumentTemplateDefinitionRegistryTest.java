package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DocumentTemplateDefinitionRegistryTest {

    @Test
    void exposesEveryDocumentTypeWithSupportedFormats() {
        var registry = new DocumentTemplateDefinitionRegistry();

        assertThat(registry.all()).extracting(DocumentTemplateDefinitionRegistry.Definition::type)
                .containsExactly(DocumentTemplateType.values());
        for (var definition : registry.all()) {
            for (var format : DocumentTemplateFormat.values()) {
                assertThat(definition.formats().contains(format.name()))
                        .as("%s / %s", definition.type(), format)
                        .isEqualTo(format.supports(definition.type()));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = DocumentTemplateType.class, names = {"ENTRADA_CAJA", "RETIRADA_CAJA"})
    void cashReceiptsUseTicketFormatWithTranslatedDefinitions(DocumentTemplateType type) {
        var definition = new DocumentTemplateDefinitionRegistry().require(type);

        assertThat(DocumentTemplateFormat.defaultFor(type)).isEqualTo(DocumentTemplateFormat.TICKET_80);
        assertThat(definition.formats()).containsExactly("TICKET_80");
        assertThat(definition.requiredFields()).contains("issuer", "movement", "lines");
        assertThat(definition.label(Locale.forLanguageTag("es"))).doesNotContain(type.name()).isNotBlank();
        assertThat(definition.label(Locale.ENGLISH)).doesNotContain(type.name()).isNotBlank();
        assertThat(definition.label(Locale.CHINESE)).doesNotContain(type.name()).isNotBlank();
    }
}
