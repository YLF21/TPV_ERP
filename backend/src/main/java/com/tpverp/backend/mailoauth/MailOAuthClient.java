package com.tpverp.backend.mailoauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

@Component
public class MailOAuthClient {
    private final HttpClient http;
    private final ObjectMapper mapper;

    @Autowired
    public MailOAuthClient(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    MailOAuthClient(ObjectMapper mapper, HttpClient http) { this.mapper = mapper; this.http = http; }

    /** Authenticates without submitting a message, so selecting a different account cannot create a false connection. */
    public void verifyIdentity(MailOAuthProvider provider, String email, String accessToken) {
        if (provider == MailOAuthProvider.GOOGLE) {
            try {
                var response = http.send(HttpRequest.newBuilder(URI.create("https://www.googleapis.com/oauth2/v2/userinfo"))
                        .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + accessToken).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200 || response.body().length() > 32768) throw unavailable();
                var identity = mapper.readTree(response.body());
                if (!identity.path("verified_email").asBoolean(false)
                        || !email.equalsIgnoreCase(identity.path("email").asText())) throw unavailable();
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw unavailable();
            } catch (Exception failure) { throw unavailable(); }
        }
        var sender = new JavaMailSenderImpl();
        sender.setHost(provider.host); sender.setPort(587);
        sender.setUsername(email); sender.setPassword(accessToken);
        var properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.auth.mechanisms", "XOAUTH2");
        properties.setProperty("mail.smtp.auth.xoauth2.disable", "false");
        properties.setProperty("mail.smtp.auth.login.disable", "true");
        properties.setProperty("mail.smtp.auth.plain.disable", "true");
        properties.setProperty("mail.smtp.starttls.enable", "true");
        properties.setProperty("mail.smtp.starttls.required", "true");
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.connectiontimeout", "10000");
        properties.setProperty("mail.smtp.timeout", "15000");
        properties.setProperty("mail.smtp.writetimeout", "15000");
        try { sender.testConnection(); }
        catch (Exception failure) { throw unavailable(); }
    }

    public void sendGoogle(MimeMessage message, String accessToken) {
        String body;
        try {
            message.saveChanges();
            var bytes = new ByteArrayOutputStream();
            message.writeTo(bytes);
            body = mapper.writeValueAsString(Map.of("raw", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())));
        } catch (Exception preparation) { throw new MailPreparationException("message.smtp.message_invalid"); }
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages/send"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new MailSendException("message.smtp.delivery_uncertain");
        } catch (java.io.IOException failure) {
            // No cause text (tokens/request fragments) is exposed. A timeout could occur after acceptance.
            throw new MailSendException("message.smtp.delivery_uncertain");
        }
        if (response.statusCode() == 401 || response.statusCode() == 403)
            throw new MailAuthenticationException("message.smtp.authentication_failed");
        if (response.statusCode() >= 400 && response.statusCode() < 500)
            throw new MailPreparationException("message.smtp.send_failed");
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new MailSendException("message.smtp.delivery_uncertain");
        // A successful response is provider acceptance, not proof of delivery or reading.
    }

    public Tokens exchange(MailOAuthProvider provider, Map<String, String> form) {
        try {
            var request = HttpRequest.newBuilder(URI.create(provider.tokenEndpoint))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encode(form))).build();
            // Neither request bodies nor provider error bodies may reach logs or API responses.
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 131072) throw unavailable();
            var json = mapper.readTree(response.body());
            String access = json.path("access_token").asText("");
            String refresh = json.path("refresh_token").asText("");
            long seconds = json.path("expires_in").asLong(0);
            if (access.isBlank() || access.length() > 32768 || refresh.length() > 32768
                    || seconds < 60 || seconds > 86400
                    || !"Bearer".equalsIgnoreCase(json.path("token_type").asText())) throw unavailable();
            return new Tokens(access, refresh, seconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    static IllegalStateException unavailable() {
        return new IllegalStateException("No se pudo autorizar el correo. Vuelve a conectar la cuenta.");
    }
    static String encode(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public record Tokens(String accessToken, String refreshToken, long expiresIn) {
        @Override public String toString() { return "OAuthTokens[redacted]"; }
    }
}
