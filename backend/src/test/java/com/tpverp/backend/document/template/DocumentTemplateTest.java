package com.tpverp.backend.document.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentTemplateTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-09T09:00:00Z");

    @Test
    void createsImmutableVersionedStoreDraft() {
        var template = DocumentTemplate.storeDraft(
                store(), DocumentTemplateType.FACTURA_VENTA,
                " factura_a4_las_palmas ", 3, "Factura Las Palmas", null, CREATED_AT);

        assertThat(template.getScope()).isEqualTo(DocumentTemplateScope.STORE);
        assertThat(template.getCode()).isEqualTo("FACTURA_A4_LAS_PALMAS");
        assertThat(template.getTemplateVersion()).isEqualTo(3);
        assertThat(template.getStatus()).isEqualTo(DocumentTemplateStatus.DRAFT);
    }

    @Test
    void validatesActivatesAndRetiresWithArtifactEvidence() {
        var template = DocumentTemplate.storeDraft(
                store(), DocumentTemplateType.FACTURA_VENTA,
                "FACTURA_A4_LP", 1, "Factura LP", null, CREATED_AT);

        template.validateArtifact(
                1, "signed:templates/factura-a4-lp-v1",
                "A".repeat(64), CREATED_AT.plusSeconds(60));
        template.activate(CREATED_AT.plusSeconds(120));
        template.retire(CREATED_AT.plusSeconds(180));

        assertThat(template.getStatus()).isEqualTo(DocumentTemplateStatus.RETIRED);
        assertThat(template.getSha256()).isEqualTo("a".repeat(64));
        assertThat(template.getActivatedAt()).isEqualTo(CREATED_AT.plusSeconds(120));
        assertThat(template.getRetiredAt()).isEqualTo(CREATED_AT.plusSeconds(180));
    }

    @Test
    void refusesActivationBeforeTrustedValidation() {
        var template = DocumentTemplate.storeDraft(
                store(), DocumentTemplateType.FACTURA_VENTA,
                "FACTURA_A4_LP", 1, "Factura LP", null, CREATED_AT);

        assertThatThrownBy(() -> template.activate(CREATED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("document_template_not_validated");
    }

    @Test
    void keepsVersionOutsideTheTemplateCode() {
        assertThatThrownBy(() -> DocumentTemplate.storeDraft(
                store(), DocumentTemplateType.FACTURA_VENTA,
                "FACTURA_A4_LP_V2", 2, "Factura LP", null, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("document_template_code_must_not_include_version");
    }

    static Store store() {
        var company = new Company("B12345678", "Empresa", address());
        return new Store(company, "001", "Tienda", address(), "hash",
                "Atlantic/Canary", "EUR", "es-ES");
    }

    static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle Mayor 1",
                "codigoPostal", "35001",
                "ciudad", "Las Palmas",
                "provincia", "Las Palmas",
                "pais", "ES");
    }
}
