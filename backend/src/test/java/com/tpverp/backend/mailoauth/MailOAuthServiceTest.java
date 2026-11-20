package com.tpverp.backend.mailoauth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.MailSecretCipher;
import com.tpverp.backend.party.MemberSmtpSettingsRepository;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MailOAuthServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final MailOAuthClient client = mock(MailOAuthClient.class);
    private final MemberSmtpSettingsRepository smtp = mock(MemberSmtpSettingsRepository.class);
    private final MockEnvironment env = new MockEnvironment();
    private final MailSecretCipher cipher = new MailSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final Company company = new Company("B00000000", "Test", Map.of(
            "linea1", "Calle de prueba 1", "ciudad", "Madrid", "codigoPostal", "28001",
            "provincia", "Madrid", "pais", "ES"));

    private MailOAuthService service() {
        when(organization.currentCompany()).thenReturn(company);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new MailOAuthService(jdbc, organization, env, cipher, client, smtp, Clock.systemUTC(), transactions);
    }
    private void configure() {
        env.withProperty("tpv.mail.oauth.google.client-id", "id")
                .withProperty("tpv.mail.oauth.google.client-secret", "secret")
                .withProperty("tpv.mail.oauth.redirect-uri", "http://127.0.0.1:8080/api/v1/mail-oauth/callback");
    }
    @Test void disabledProviderDoesNotStartNetworkOrPersistFlow() {
        assertThatThrownBy(() -> service().connect(MailOAuthProvider.GOOGLE, "test@example.com"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc, client, smtp);
    }
    @Test void eachAttemptHasRandomStateAndPkceWithoutExposingSecret() {
        configure(); var service = service();
        String first = service.connect(MailOAuthProvider.GOOGLE, "test@example.com").authorizationUrl();
        String second = service.connect(MailOAuthProvider.GOOGLE, "test@example.com").authorizationUrl();
        assertThat(first).startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("code_challenge_method=S256", "access_type=offline").doesNotContain("client_secret");
        assertThat(first).isNotEqualTo(second);
        var query = new HashMap<String,String>();
        for (String entry : URI.create(first).getRawQuery().split("&")) {
            String[] pair = entry.split("=",2);
            query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        assertThat(query.get("state")).matches("[A-Za-z0-9_-]{43}");
        assertThat(query.get("code_challenge")).matches("[A-Za-z0-9_-]{43}");
        verifyNoInteractions(client);
    }
    @Test void malformedOrReplayedStateNeverReachesProvider() {
        var service = service();
        assertThatThrownBy(() -> service.callback("bad", "code", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.callback("a".repeat(43), "code", null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client, smtp);
    }
    @Test void disconnectOnlyDeletesCurrentCompanyAndPendingConsent() {
        service().disconnect();
        verify(jdbc).update("delete from mail_oauth_connection where empresa_id=?", company.getId());
        verify(jdbc).update("delete from mail_oauth_flow where empresa_id=?", company.getId());
        verify(smtp).findById(company.getId());
    }
}
