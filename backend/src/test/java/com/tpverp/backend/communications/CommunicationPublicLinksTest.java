package com.tpverp.backend.communications;

import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommunicationPublicLinksTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final String secret=Base64.getEncoder().encodeToString(new byte[32]);
    @Test void signsOpaqueTokensAndRejectsAlterationWithoutDatabaseLookup() {
        var links=new CommunicationPublicLinks(jdbc,Clock.systemUTC(),"https://correo.tienda.es",secret);
        String url=links.create(UUID.randomUUID(),UUID.randomUUID(),"private@example.org");
        String token=url.substring(url.indexOf("token=")+6);
        assertThat(url).doesNotContain("private", "@");
        assertThat(links.verifySignature(token)).isNotNull();
        assertThatThrownBy(()->links.verifySignature(token+"X")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->links.verifySignature(UUID.randomUUID()+token.substring(36))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void failsClosedWithoutStableSigningKeyOrPublicHttps() {
        for(String base: new String[]{"http://mail.tienda.es","https://localhost","https://127.0.0.1","https://10.0.0.2","https://shop.local","https://example.com","https://user:password@mail.tienda.es","https://localhost.","https://mail.example.org","https://mail.tienda.es:0","https://mail.tienda.es:65536"}) {
            var links=new CommunicationPublicLinks(jdbc,Clock.systemUTC(),base,secret);
            assertThat(links.configured()).as(base).isFalse();
            assertThatThrownBy(()->links.create(UUID.randomUUID(),UUID.randomUUID(),"a@example.org")).isInstanceOf(IllegalStateException.class);
        }
        assertThat(new CommunicationPublicLinks(jdbc,Clock.systemUTC(),"https://mail.tienda.es","bad").configured()).isFalse();
    }
    @Test void changingSigningKeyOrPublicAddressBlocksPreviouslyQueuedLinks() {
        var links=new CommunicationPublicLinks(jdbc,Clock.systemUTC(),"https://mail.tienda.es",secret);
        String url=links.create(UUID.randomUUID(),UUID.randomUUID(),"a@example.org");
        byte[] newKey=new byte[32];newKey[0]=1;
        assertThat(new CommunicationPublicLinks(jdbc,Clock.systemUTC(),"https://mail.tienda.es",Base64.getEncoder().encodeToString(newKey)).isCurrentLink(url)).isFalse();
        assertThat(new CommunicationPublicLinks(jdbc,Clock.systemUTC(),"https://other.tienda.es",secret).isCurrentLink(url)).isFalse();
    }
}
