package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;

class CertificateTaxIdExtractorTest {

    private final CertificateTaxIdExtractor extractor = new CertificateTaxIdExtractor();

    @Test
    void extractsSpanishTaxIdFromSerialNumberPrefix() {
        var principal = new X500Principal("CN=Empresa,SERIALNUMBER=IDCES-B12345674");

        assertThat(extractor.extract(principal)).isEqualTo("B12345674");
    }

    @Test
    void rejectsSubjectWithoutSpanishTaxId() {
        assertThatThrownBy(() -> extractor.extract(new X500Principal("CN=Empresa")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No se pudo extraer el NIF del certificado");
    }
}
