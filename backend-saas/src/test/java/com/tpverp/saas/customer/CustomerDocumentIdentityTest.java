package com.tpverp.saas.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CustomerDocumentIdentityTest {
    @ParameterizedTest
    @CsvSource({"DNI,12345678Z,DNI,12345678Z", "NIE,X1234567L,NIE,X1234567L",
            "NIF,B12345674,NIF,B12345674", "CIF,b-1234 5674,NIF,B12345674",
            "PASAPORTE,not-a checksum,PASAPORTE,NOTACHECKSUM", "OTRO,abc-123,PASAPORTE,ABC123",
            "NIF,12345678Z,NIF,12345678Z", "NIF,X1234567L,NIF,X1234567L"})
    void validatesTypesAndCanonicalizesAliases(String type, String number, String expectedType, String expectedNumber) {
        assertThat(CustomerDocumentIdentity.validate(type, number))
                .isEqualTo(new CustomerDocumentIdentity(expectedType, expectedNumber));
    }

    @ParameterizedTest
    @CsvSource({"DNI,12345678A", "NIE,X1234567A", "NIF,B12345678", "DNI,X1234567L",
            "NIE,12345678Z", "DNI,B12345674", "UNKNOWN,12345678Z", "PASAPORTE,'  '"})
    void rejectsWrongChecksumTypeAndEmptyIdentity(String type, String number) {
        assertThatThrownBy(() -> CustomerDocumentIdentity.validate(type, number))
                .isInstanceOfSatisfying(CustomerIdentityException.class,
                        error -> assertThat(error.getCode()).isEqualTo("CUSTOMER_DOCUMENT_INVALID"));
    }
}
