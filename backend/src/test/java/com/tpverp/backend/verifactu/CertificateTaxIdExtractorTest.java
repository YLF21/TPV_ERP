package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;

class CertificateTaxIdExtractorTest {

    private final CertificateTaxIdExtractor extractor = new CertificateTaxIdExtractor();

    @Test
    void extractsSpanishTaxIdFromSerialNumberPrefix() {
        var principal = new X500Principal("CN=Company,SERIALNUMBER=IDCES-B12345674");

        assertThat(extractor.extract(principal)).isEqualTo("B12345674");
    }

    @Test
    void prioritizesRepresentedCompanyOverRepresentative() {
        var principal = new X500Principal(
                "CN=Representative,SERIALNUMBER=IDCES-12345678Z,"
                        + "2.5.4.97=VATES-B12345674,O=Company");

        assertThat(extractor.extract(principal)).isEqualTo("B12345674");
    }

    @Test
    void detectsSelfEmployedCertificateWhenOrganizationIdentifierIsAbsent() {
        var principal = new X500Principal(
                "CN=Self employed,SERIALNUMBER=IDCES-12345678Z,C=ES");

        assertThat(extractor.extract(principal)).isEqualTo("12345678Z");
    }

    @Test
    void doesNotFallBackToRepresentativeWhenOrganizationIdentifierIsInvalid() {
        var principal = new X500Principal(
                "CN=Representative,SERIALNUMBER=IDCES-12345678Z,"
                        + "2.5.4.97=VATES-B12345675,O=Company");

        assertThatThrownBy(() -> extractor.extract(principal))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSubjectWithoutSpanishTaxId() {
        assertThatThrownBy(() -> extractor.extract(new X500Principal("CN=Company")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No se pudo extraer el NIF del certificado");
    }
}
