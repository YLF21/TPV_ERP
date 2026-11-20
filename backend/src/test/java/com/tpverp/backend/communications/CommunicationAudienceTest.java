package com.tpverp.backend.communications;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CommunicationAudienceTest {
    @Test void countsEveryExclusionAndDeduplicatesNormalizedEmail() {
        var audience=CommunicationService.classify(List.of(
            candidate("CLIENTE@example.org",true,true,true,false),candidate("cliente@example.org",true,true,true,false),
            candidate(null,true,true,true,false),candidate("invalid",true,true,true,false),
            candidate("no@example.org",true,false,true,false),candidate("channel@example.org",true,true,false,false),
            candidate("optout@example.org",true,true,true,true),candidate("inactive@example.org",false,true,true,false)));
        assertThat(audience.total()).isEqualTo(8);
        assertThat(audience.recipients()).hasSize(1);
        assertThat(audience.recipients().getFirst().email()).isEqualTo("cliente@example.org");
        assertThat(audience.noEmail()).isEqualTo(2);
        assertThat(audience.noConsent()).isEqualTo(1);
        assertThat(audience.channel()).isEqualTo(1);
        assertThat(audience.suppressed()).isEqualTo(1);
        assertThat(audience.inactive()).isEqualTo(1);
        assertThat(audience.duplicate()).isEqualTo(1);
    }
    @Test void rejectsHeaderInjectionDisplayNamesAndMultipleRecipients() {
        for(String email:List.of("a@example.org\r\nBcc:b@example.org","Name <a@example.org>","a@example.org,b@example.org"))
            assertThat(CommunicationService.validEmail(email)).isFalse();
    }
    private CommunicationService.Candidate candidate(String email,boolean active,boolean consent,boolean channel,boolean suppressed) {
        return new CommunicationService.Candidate(UUID.randomUUID(),"Cliente",email,active,consent,channel,suppressed);
    }
}
