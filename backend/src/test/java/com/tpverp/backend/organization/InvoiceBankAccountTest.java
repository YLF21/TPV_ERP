package com.tpverp.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoiceBankAccountTest {

    @Test
    void normalizesAndValidatesAnIban() {
        var account = new InvoiceBankAccount(
                UUID.randomUUID(), "Banco Atlántico", "es91 2100 0418 4502 0005 1332", 0);

        assertThat(account.getBankName()).isEqualTo("Banco Atlántico");
        assertThat(account.getIban()).isEqualTo("ES9121000418450200051332");
        assertThat(account.isActive()).isTrue();
    }

    @Test
    void rejectsAnIbanWithInvalidChecksum() {
        assertThatThrownBy(() -> new InvoiceBankAccount(
                UUID.randomUUID(), "Banco", "ES9121000418450200051333", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invoice_bank_iban_invalid");
    }
}
