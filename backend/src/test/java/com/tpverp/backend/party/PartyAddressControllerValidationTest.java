package com.tpverp.backend.party;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PartyAddressControllerValidationTest {
    @Test
    void rejectsMissingOversizedAndInvalidParametersBeforeCallingRemoteService() throws Exception {
        var service = mock(PartyAddressService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PartyAddressController(service)).build();
        mvc.perform(get("/api/v1/party-addresses/suggestions")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/party-addresses/suggestions").param("q", "x".repeat(201)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/party-addresses/suggestions").param("q", "calle\n"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/party-addresses/resolve").param("id", "1").param("type", "Codpost"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/party-addresses/resolve").param("id", " ").param("type", "portal"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/party-addresses/resolve").param("id", "x".repeat(201)).param("type", "portal"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void additionalPostalCodeAndCityParametersDoNotConstrainTheSearch() throws Exception {
        var service = mock(PartyAddressService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PartyAddressController(service)).build();
        mvc.perform(get("/api/v1/party-addresses/suggestions").param("q", "Niño & Sol?#")
                .param("postalCode", "x".repeat(17)).param("city", "x".repeat(129) + "\n"))
                .andExpect(status().isOk());
        verify(service).suggestions("Niño & Sol?#");
    }
}
