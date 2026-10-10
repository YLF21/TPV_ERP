package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LicenseSaasPresentationContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsResponsesFromOlderSaasWithoutThePresentationField() throws Exception {
        assertThat(mapper.readValue("{}", LicenseSaasLinkResponse.class).storeInternalCode()).isNull();
        assertThat(mapper.readValue("{}", LicenseSaasValidationResponse.class).storeInternalCode()).isNull();
    }

    @Test
    void preservesLeadingZeroesInBothSaasResponseContracts() throws Exception {
        var json = "{\"storeInternalCode\":\"0100001\"}";
        assertThat(mapper.readValue(json, LicenseSaasLinkResponse.class).storeInternalCode()).isEqualTo("0100001");
        assertThat(mapper.readValue(json, LicenseSaasValidationResponse.class).storeInternalCode()).isEqualTo("0100001");
    }
}
