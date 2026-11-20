package com.tpverp.backend.mailoauth;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MailOAuthProviderTest {
    @Test void requiresRegistrationAndExplicitSafeCallback() {
        var env = new MockEnvironment();
        assertThat(MailOAuthProvider.GOOGLE.configuration(env).available()).isFalse();
        env.withProperty("tpv.mail.oauth.google.client-id", "client")
                .withProperty("tpv.mail.oauth.google.client-secret", "secret");
        for (String uri : new String[]{"http://public.example/api/v1/mail-oauth/callback",
                "https://example.com/other", "https://user:password@example.com/api/v1/mail-oauth/callback",
                "https://example.com/api/v1/mail-oauth/callback?next=evil", "not a URI"}) {
            env.withProperty("tpv.mail.oauth.redirect-uri", uri);
            assertThat(MailOAuthProvider.GOOGLE.configuration(env).available()).as(uri).isFalse();
        }
        for (String uri : new String[]{"https://mail.example.com/api/v1/mail-oauth/callback",
                "http://127.0.0.1:8080/api/v1/mail-oauth/callback"}) {
            env.withProperty("tpv.mail.oauth.redirect-uri", uri);
            assertThat(MailOAuthProvider.GOOGLE.configuration(env).available()).as(uri).isTrue();
        }
    }
    @Test void pkceMatchesPublishedRfc7636Vector() {
        assertThat(MailOAuthService.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }
    @Test void formEncodingCannotInjectExtraParameters() {
        assertThat(MailOAuthClient.encode(Map.of("code", "a&client_secret=exposed+中文")))
                .isEqualTo("code=a%26client_secret%3Dexposed%2B%E4%B8%AD%E6%96%87");
    }
    @Test void tokenRecordsNeverPrintSecrets() {
        assertThat(new MailOAuthClient.Tokens("access", "refresh", 3600).toString()).doesNotContain("access", "refresh");
        assertThat(new MailOAuthService.SmtpCredentials("email", "secret", "smtp", 587, MailOAuthProvider.MICROSOFT).toString()).doesNotContain("secret");
    }
}
