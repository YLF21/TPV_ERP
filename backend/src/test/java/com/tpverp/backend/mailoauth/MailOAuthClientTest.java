package com.tpverp.backend.mailoauth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;

class MailOAuthClientTest {
    private final HttpClient http = mock(HttpClient.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MailOAuthClient client = new MailOAuthClient(mapper, http);

    @SuppressWarnings("unchecked")
    private void response(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
    }
    private MimeMessage message() throws Exception {
        var message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom("store@example.org"); message.setRecipients(jakarta.mail.Message.RecipientType.TO,"own@example.org");
        message.setSubject("Bienvenido 中文", "UTF-8"); message.setText("Contenido de prueba", "UTF-8");
        return message;
    }
    @Test void googleIdentityMustBeVerifiedAndMatchRequestedMailbox() throws Exception {
        response(200,"{\"email\":\"store@example.org\",\"verified_email\":true}");
        client.verifyIdentity(MailOAuthProvider.GOOGLE,"store@example.org","token");
        assertThatThrownBy(()->client.verifyIdentity(MailOAuthProvider.GOOGLE,"different@example.org","token"))
                .isInstanceOf(IllegalStateException.class);
        response(200,"{\"email\":\"store@example.org\",\"verified_email\":false}");
        assertThatThrownBy(()->client.verifyIdentity(MailOAuthProvider.GOOGLE,"store@example.org","token"))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void gmailUsesMimeBase64UrlAndBearerHeaderWithoutPuttingSecretsInUrl() throws Exception {
        response(200,"{\"id\":\"accepted\"}");
        new GmailApiMailSender(client,"access-secret").send(message());
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(request.getValue().uri().toString()).isEqualTo("https://gmail.googleapis.com/gmail/v1/users/me/messages/send");
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("Bearer access-secret");
        var body = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        request.getValue().bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { body.onSubscribe(subscription); }
            @Override public void onNext(java.nio.ByteBuffer buffer) { body.onNext(java.util.List.of(buffer)); }
            @Override public void onError(Throwable error) { body.onError(error); }
            @Override public void onComplete() { body.onComplete(); }
        });
        var json = mapper.readTree(body.getBody().toCompletableFuture().join());
        String mime = new String(Base64.getUrlDecoder().decode(json.path("raw").asText()),StandardCharsets.UTF_8);
        assertThat(mime).contains("To: own@example.org", "From: store@example.org", "MIME-Version:")
                .doesNotContain("access-secret");
    }
    @Test void rejectedAuthorizationIsDefiniteButServerFailureIsUncertain() throws Exception {
        response(401,"sensitive provider response");
        assertThatThrownBy(()->client.sendGoogle(message(),"token")).isInstanceOf(MailAuthenticationException.class)
                .hasMessage("message.smtp.authentication_failed");
        response(429,"sensitive provider response");
        assertThatThrownBy(()->client.sendGoogle(message(),"token")).isInstanceOf(MailPreparationException.class);
        response(503,"sensitive provider response");
        assertThatThrownBy(()->client.sendGoogle(message(),"token")).isInstanceOf(MailSendException.class)
                .hasMessage("message.smtp.delivery_uncertain");
    }
    @Test void requestTimeoutIsNeverTreatedAsSafeToAutomaticallyResend() throws Exception {
        when(http.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new HttpTimeoutException("possibly accepted"));
        assertThatThrownBy(()->client.sendGoogle(message(),"token")).isInstanceOf(MailSendException.class)
                .hasMessage("message.smtp.delivery_uncertain").hasNoCause();
    }
    @Test void googleRequestsSendAndEmailIdentityScopesOnly() {
        assertThat(MailOAuthProvider.GOOGLE.scope).contains("gmail.send", "userinfo.email")
                .doesNotContain("https://mail.google.com/", "gmail.readonly", "gmail.modify");
    }

    @Test void tokenExchangeFormEncodesReservedCharactersAndNeverExposesSecretsInUrl() throws Exception {
        response(200, "{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        var tokens = client.exchange(MailOAuthProvider.GOOGLE,
                Map.of("client_secret", "secret+&=中文", "code", "code+/="));
        assertThat(tokens.accessToken()).isEqualTo("access");
        assertThat(tokens.toString()).doesNotContain("refresh", "access");
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(request.getValue().uri().toString()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(request.getValue().method()).isEqualTo("POST");
        assertThat(request.getValue().timeout()).contains(java.time.Duration.ofSeconds(20));
        assertThat(MailOAuthClient.encode(Map.of("client_secret", "secret+&=中文")))
                .isEqualTo("client_secret=secret%2B%26%3D%E4%B8%AD%E6%96%87");
    }

    @Test void invalidProviderTokenResponsesAreRejectedWithoutSensitiveDetails() throws Exception {
        String[] invalid = {
                "not JSON: provider-secret",
                "{\"access_token\":\"provider-secret\",\"token_type\":\"Basic\",\"expires_in\":3600}",
                "{\"access_token\":\"provider-secret\",\"token_type\":\"Bearer\",\"expires_in\":0}",
                "{\"access_token\":\"provider-secret\",\"token_type\":\"Bearer\",\"expires_in\":86401}",
                "{\"token_type\":\"Bearer\",\"expires_in\":3600}",
                "x".repeat(131073)
        };
        for (var body : invalid) {
            response(200, body);
            assertThatThrownBy(() -> client.exchange(MailOAuthProvider.GOOGLE, Map.of()))
                    .isInstanceOf(IllegalStateException.class).hasNoCause()
                    .hasMessage("No se pudo autorizar el correo. Vuelve a conectar la cuenta.");
        }
        response(400, "provider-secret invalid_grant");
        assertThatThrownBy(() -> client.exchange(MailOAuthProvider.MICROSOFT, Map.of()))
                .isInstanceOf(IllegalStateException.class).hasNoCause()
                .hasMessage("No se pudo autorizar el correo. Vuelve a conectar la cuenta.");
    }

    @Test void interruptedExchangePreservesInterruptAndHidesProviderDetails() throws Exception {
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new InterruptedException("provider-secret"));
        try {
            assertThatThrownBy(() -> client.exchange(MailOAuthProvider.GOOGLE, Map.of()))
                    .isInstanceOf(IllegalStateException.class).hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void gmailMimeIncludesHtmlAndInlineAttachmentWithoutChangingEncoding() throws Exception {
        response(200, "{\"id\":\"accepted\"}");
        var message = message();
        var helper = new org.springframework.mail.javamail.MimeMessageHelper(message, true, "UTF-8");
        helper.setText("Tu carnet 中文", "<h1>Tu carnet 中文</h1><img src=\"cid:card\">");
        helper.addInline("card", new org.springframework.core.io.ByteArrayResource(new byte[]{1,2,3,4}), "image/png");
        helper.addAttachment("carnet.png", new org.springframework.core.io.ByteArrayResource(new byte[]{1,2,3,4}), "image/png");
        new GmailApiMailSender(client, "token").send(message);
        assertThat(message.getSubject()).isEqualTo("Bienvenido 中文");
        var mixed = (jakarta.mail.Multipart) message.getContent();
        assertThat(mixed.getCount()).isGreaterThanOrEqualTo(2);
        assertThat(mixed.getBodyPart(1).getFileName()).isEqualTo("carnet.png");
        assertThat(mixed.getBodyPart(1).getInputStream().readAllBytes()).containsExactly(1,2,3,4);
    }
}
