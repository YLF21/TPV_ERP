package com.tpverp.saas.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MemberWalletReservationV2ControllerTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private MemberBalanceReservationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(MemberBalanceReservationService.class);
        ObjectMapper mapper = new ObjectMapper();
        mvc = MockMvcBuilders.standaloneSetup(new MemberWalletReservationV2Controller(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
        doReturn(null).when(service).reserveWallet(any(), eq("legacy-token"));
    }

    @Test
    void acceptsLegacyReservePayloadAndNormalizesMissingRetentionFields() throws Exception {
        mvc.perform(post("/api/v2/loyalty/member-wallet/reservations")
                        .header("X-TPV-Installation-Token", "legacy-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacyPayload("")))
                .andExpect(status().isCreated());

        LoyaltyApiModels.ReserveRequest request = capturedRequest();
        assertThat(request.retentionRevision()).isZero();
        assertThat(request.retentionFingerprint()).isEmpty();
        assertThat(request.retentionClaims()).isEmpty();
        assertThat(request.attributedAmount()).isNull();
    }

    @Test
    void acceptsExplicitNullRetentionFieldsAsLegacyDefaults() throws Exception {
        mvc.perform(post("/api/v2/loyalty/member-wallet/reservations")
                        .header("X-TPV-Installation-Token", "legacy-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacyPayload(",\"attributedAmount\":null,\"retentionClaims\":null,\"retentionRevision\":null,\"retentionFingerprint\":null")))
                .andExpect(status().isCreated());

        LoyaltyApiModels.ReserveRequest request = capturedRequest();
        assertThat(request.retentionRevision()).isZero();
        assertThat(request.retentionFingerprint()).isEmpty();
        assertThat(request.retentionClaims()).isEmpty();
        assertThat(request.attributedAmount()).isNull();
    }

    private LoyaltyApiModels.ReserveRequest capturedRequest() {
        ArgumentCaptor<LoyaltyApiModels.ReserveRequest> captor =
                ArgumentCaptor.forClass(LoyaltyApiModels.ReserveRequest.class);
        verify(service).reserveWallet(captor.capture(), eq("legacy-token"));
        return captor.getValue();
    }

    private String legacyPayload(String optionalFields) {
        return "{"
                + "\"companyId\":\"" + COMPANY_ID + "\","
                + "\"storeId\":\"" + STORE_ID + "\","
                + "\"memberId\":\"" + MEMBER_ID + "\","
                + "\"terminalId\":\"CAJA-1\","
                + "\"saleId\":\"VENTA-1\""
                + optionalFields
                + "}";
    }
}
