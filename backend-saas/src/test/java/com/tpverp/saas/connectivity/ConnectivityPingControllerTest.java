package com.tpverp.saas.connectivity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConnectivityPingControllerTest {
    @Test
    void publicPingReturnsOnlyServiceIdentityAndAvailabilityWithoutCaching() throws Exception {
        MockMvcBuilders.standaloneSetup(new ConnectivityPingController()).build()
                .perform(get("/api/v1/connectivity/ping"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"service\":\"tpv-erp-saas\",\"status\":\"UP\"}"));
    }
}
