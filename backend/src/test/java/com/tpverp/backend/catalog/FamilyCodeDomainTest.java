package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FamilyCodeDomainTest {

    @Test
    void generalUsesStableZeroCode() {
        Family family = Family.general(UUID.randomUUID());

        assertThat(family.getFamilyCode()).isEqualTo("000");
    }

    @Test
    void assignedSubfamilyCodeCombinesFamilyAndThreeDigitSuffix() {
        Subfamily subfamily = new Subfamily(UUID.randomUUID(), "Cafe");

        subfamily.assignCode("007", "012");

        assertThat(subfamily.getSubfamilySuffix()).isEqualTo("012");
        assertThat(subfamily.getSubfamilyCode()).isEqualTo("007012");
    }
}
