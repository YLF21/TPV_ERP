package com.tpverp.backend.communications;

import java.util.UUID;
import static com.tpverp.backend.communications.CommunicationModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

/** HTTP binding/validation and public form behavior; authorization is covered by MailPublicEndpointSecurityTest. */
class CommunicationHttpValidationTest {
    private CommunicationService service;
    private CommunicationPublicLinks links;
    private MockMvc mvc;

    @BeforeEach void setup() {
        service=mock(CommunicationService.class);links=mock(CommunicationPublicLinks.class);
        mvc=MockMvcBuilders.standaloneSetup(new CommunicationController(service),new CommunicationUnsubscribeController(links)).build();
    }

    @Test void malformedTemplateIsRejectedBeforeAnyBusinessCall() throws Exception {
        mvc.perform(post("/api/v1/communications/templates").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void createsAndPreviewsTheExactUiTemplateDraftWithoutVersion() throws Exception {
        var id=UUID.randomUUID();
        when(service.createTemplate(any())).thenReturn(new TemplateView(id,"Oferta","Hola {name}","Oferta especial","Texto de la oferta","Ver oferta","https://tienda.es/oferta",null,"#123456",0));
        when(service.previewTemplate(any())).thenReturn(new Rendered("<p>Oferta</p>","Oferta"));
        mvc.perform(post("/api/v1/communications/templates").contentType(MediaType.APPLICATION_JSON).content(templateDraft()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.version").value(0));
        mvc.perform(post("/api/v1/communications/templates/preview").contentType(MediaType.APPLICATION_JSON).content(templateDraft()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("Oferta"));
        verify(service).createTemplate(any());verify(service).previewTemplate(any());
    }

    @Test void sendsExplicitNestedTemplateTestWithoutVersion() throws Exception {
        mvc.perform(post("/api/v1/communications/templates/test").contentType(MediaType.APPLICATION_JSON)
            .content("{\"toEmail\":\"admin@tienda.es\",\"template\":"+templateDraft()+"}"))
            .andExpect(status().isNoContent());
        verify(service).testTemplate(any(),eq("admin@tienda.es"));
    }

    @Test void updatingRequiresVersionAndAcceptsTheUiPutPayloadWithVersionZero() throws Exception {
        UUID id=UUID.randomUUID();String endpoint="/api/v1/communications/templates/"+id;
        mvc.perform(put(endpoint).contentType(MediaType.APPLICATION_JSON).content(templateDraft()))
            .andExpect(status().isBadRequest());
        verify(service,never()).updateTemplate(any(),any());
        when(service.updateTemplate(eq(id),any())).thenReturn(new TemplateView(id,"Oferta","Hola {name}","Oferta especial","Texto de la oferta","Ver oferta","https://tienda.es/oferta","","#123456",1));
        String payload=templateDraft().stripTrailing();
        payload=payload.substring(0,payload.length()-1)+",\"version\":0}";
        mvc.perform(put(endpoint).contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        verify(service).updateTemplate(eq(id),argThat(input->Long.valueOf(0).equals(input.version())));
    }

    @Test void schedulesImmediatelyWhenUiOmitsDateOrPassesNull() throws Exception {
        var id=UUID.randomUUID();var template=UUID.randomUUID();
        when(service.schedule(eq(id),any())).thenReturn(new CampaignView(id,"Campaña",template,"SCHEDULED",null,java.time.Instant.EPOCH,1,2));
        String token="a".repeat(64),endpoint="/api/v1/communications/campaigns/"+id+"/schedule";
        for(String prefix:new String[]{"","\"scheduledAt\":null,"})
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                .content("{"+prefix+"\"expectedRecipients\":2,\"previewToken\":\""+token+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED"));
        verify(service,times(2)).schedule(eq(id),argThat(input->input.scheduledAt()==null&&input.expectedRecipients()==2&&token.equals(input.previewToken())));
    }

    @Test void createsCampaignForAllCustomersWhenUiPassesNullOrEmptyList() throws Exception {
        UUID id=UUID.randomUUID(),template=UUID.randomUUID();
        when(service.createCampaign(any())).thenReturn(new CampaignView(id,"Campaña",template,"DRAFT",null,java.time.Instant.EPOCH,0,0));
        for(String customers:new String[]{"null","[]"})
            mvc.perform(post("/api/v1/communications/campaigns").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Campaña\",\"templateId\":\""+template+"\",\"customerIds\":"+customers+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        verify(service,times(2)).createCampaign(any());
    }

    private String templateDraft() {
        return """
            {"name":"Oferta","subject":"Hola {name}","heading":"Oferta especial","body":"Texto de la oferta",
             "buttonText":"Ver oferta","buttonUrl":"https://tienda.es/oferta","logoUrl":"","accentColor":"#123456"}
            """;
    }

    @Test void scheduleRequiresAValidPreviewFingerprint() throws Exception {
        String endpoint="/api/v1/communications/campaigns/"+UUID.randomUUID()+"/schedule";
        for(String body:new String[]{"{\"expectedRecipients\":1}","{\"expectedRecipients\":0,\"previewToken\":\""+"a".repeat(64)+"\"}","{\"expectedRecipients\":1,\"previewToken\":\"tampered\"}"})
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void rejectsNullCustomerSelectionsAndInvalidTestRecipients() throws Exception {
        mvc.perform(post("/api/v1/communications/campaigns").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Campaña\",\"templateId\":\""+UUID.randomUUID()+"\",\"customerIds\":[null]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/communications/templates/test").contentType(MediaType.APPLICATION_JSON)
            .content("{\"toEmail\":\"not-an-email\",\"template\":null}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void confirmationGetDoesNotOptOutAndFormPostsWithoutDuplicatingQueryToken() throws Exception {
        mvc.perform(get("/api/public/communications/unsubscribe").queryParam("token","signed-token"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Referrer-Policy","no-referrer"))
            .andExpect(header().string("Content-Security-Policy",containsString("form-action 'self'")))
            .andExpect(content().string(containsString("method=\"post\" action=\"unsubscribe\"")));
        verify(links).verify("signed-token");verify(links,never()).unsubscribe(anyString());
        mvc.perform(post("/api/public/communications/unsubscribe").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("token","signed-token"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Baja confirmada")));
        verify(links).unsubscribe("signed-token");
    }

    @Test void oneClickPostUsesSignedQueryAndInvalidLinkDoesNotExposeIdentity() throws Exception {
        mvc.perform(post("/api/public/communications/unsubscribe").queryParam("token","signed-token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("List-Unsubscribe=One-Click"))
            .andExpect(status().isOk());
        verify(links).unsubscribe("signed-token");
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enlace de baja no válido")).when(links).verify("invalid");
        mvc.perform(get("/api/public/communications/unsubscribe").queryParam("token","invalid"))
            .andExpect(status().isBadRequest());
        verify(links,never()).unsubscribe("invalid");
    }
}
