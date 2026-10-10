package com.tpverp.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class StoreSaasInternalCodeTest {
    @ParameterizedTest
    @ValueSource(strings = {"0100001", "3500002", "5299999"})
    void keepsConfirmedCodeAndFiscalIdentity(String code) {
        var store = store();
        var id = store.getId();
        store.rememberSaasInternalCode(code);
        store.rememberSaasInternalCode(null);
        store.rememberSaasInternalCode("invalid");
        store.rememberSaasInternalCode("0200003");
        assertThat(store.getSaasInternalCode()).isEqualTo(code);
        assertThat(store.getId()).isEqualTo(id);
        assertThat(store.getCodigoTienda()).isEqualTo("001");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"001", "0000001", "5300001", "3500000", "35000021", "35abcde", "３５００００２", " 3500002"})
    void ignoresOptionalInvalidCodes(String code) {
        var store = store();
        store.rememberSaasInternalCode(code);
        assertThat(store.getSaasInternalCode()).isNull();
    }

    private Store store() {
        var address = Map.of("linea1", "Calle Uno", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES");
        return new Store(new Company("B12345674", "Empresa", address), "Tienda", address,
                "hash", "Atlantic/Canary", "EUR", "es-ES");
    }
}
