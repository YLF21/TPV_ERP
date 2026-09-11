package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CustomerDocumentIdentityTest {

    @ParameterizedTest
    @CsvSource({
            "DNI, 12 345 678-z, DNI, 12345678Z",
            "DNI, 00000001-r, DNI, 00000001R",
            "NIE, x-1234567-l, NIE, X1234567L",
            "NIE, Y1234567X, NIE, Y1234567X",
            "NIE, Z1234567R, NIE, Z1234567R",
            "NIF, 12345678z, NIF, 12345678Z",
            "NIF, X1234567L, NIF, X1234567L",
            "NIF, B12345674, NIF, B12345674",
            "CIF, B-12345674, NIF, B12345674",
            "NIF, A58818501, NIF, A58818501",
            "NIF, P1234567D, NIF, P1234567D",
            "NIF, Q1234567D, NIF, Q1234567D",
            "NIF, R1234567D, NIF, R1234567D",
            "NIF, N1234567D, NIF, N1234567D",
            "NIF, W1234567D, NIF, W1234567D",
            "NIF, K1234567L, NIF, K1234567L",
            "NIF, L1234567L, NIF, L1234567L",
            "NIF, M1234567L, NIF, M1234567L",
            "PASAPORTE, 00-ab/ñ.1, PASAPORTE, 00AB/Ñ.1",
            "OTRO, 12ab, PASAPORTE, 12AB"
    })
    void validatesCanonicalDocumentWithoutLosingLeadingZeros(
            DocumentType type, String number, DocumentType expectedType, String expectedNumber) {
        var identity = CustomerDocumentIdentity.validate(type, number);

        assertThat(identity.canonicalType()).isEqualTo(expectedType);
        assertThat(identity.canonicalNumber()).isEqualTo(expectedNumber);
    }

    @ParameterizedTest
    @CsvSource({
            "DNI, 12345678A",
            "DNI, 1234567L",
            "DNI, X1234567L",
            "DNI, B12345674",
            "DNI, 12.345.678Z",
            "NIE, X1234567A",
            "NIE, 12345678Z",
            "NIE, K1234567L",
            "NIE, X12345678Z",
            "NIE, T1234567L",
            "NIF, 12345678A",
            "NIF, B12345678",
            "NIF, P12345674",
            "NIF, A5881850A",
            "NIF, K1234567A",
            "NIF, I12345674",
            "CIF, B12345678"
    })
    void rejectsInvalidStructureOrControl(DocumentType type, String number) {
        assertThatThrownBy(() -> CustomerDocumentIdentity.validate(type, number))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " -- "})
    void requiresDocumentEvenForPassport(String number) {
        assertThatThrownBy(() -> CustomerDocumentIdentity.validate(DocumentType.PASAPORTE, number))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("obligatorio");
    }

    @Test
    void hasTheSameNumberKeyRegardlessOfTaxDocumentType() {
        var dni = CustomerDocumentIdentity.validate(DocumentType.DNI, "12345678-Z");
        var nif = CustomerDocumentIdentity.validate(DocumentType.NIF, " 12 345 678 z ");
        var passport = CustomerDocumentIdentity.validate(DocumentType.PASAPORTE, "12345678Z");

        assertThat(dni.canonicalNumber()).isEqualTo(nif.canonicalNumber())
                .isEqualTo(passport.canonicalNumber());
    }

    @Test
    void passportDoesNotCheckFormatOrChecksumAndDoesNotRemoveSignificantPunctuation() {
        assertThat(CustomerDocumentIdentity.validate(DocumentType.PASAPORTE, " not-a-tax.id/01 ")
                .canonicalNumber()).isEqualTo("NOTATAX.ID/01");
        assertThat(CustomerDocumentIdentity.validate(DocumentType.PASAPORTE, "12345678A")
                .canonicalNumber()).isEqualTo("12345678A");
        assertThat(CustomerDocumentIdentity.normalizeNumber("a\u00a0b\tc"))
                .isEqualTo("A\u00a0B\tC");
    }

    @Test
    void enforcesCanonicalLengthAndRequiresType() {
        assertThat(CustomerDocumentIdentity.validate(DocumentType.PASAPORTE, "0".repeat(64))
                .canonicalNumber()).hasSize(64);
        assertThatThrownBy(() -> CustomerDocumentIdentity.validate(DocumentType.PASAPORTE, "0".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64");
        assertThatThrownBy(() -> CustomerDocumentIdentity.validate(null, "12345678Z"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tipo");
    }

    @Test
    void recordConstructorCannotBypassValidation() {
        assertThatThrownBy(() -> new CustomerDocumentIdentity(DocumentType.DNI, "12345678A"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
