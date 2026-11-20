package com.tpverp.backend.mailoauth;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.MailSecretCipher;
import com.tpverp.backend.party.MemberSmtpSettings;
import com.tpverp.backend.party.MemberSmtpSettingsCommand;
import com.tpverp.backend.party.MemberSmtpSettingsRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

@Service
public class MailOAuthService {
    private final JdbcTemplate jdbc;
    private final CurrentOrganization organization;
    private final Environment environment;
    private final MailSecretCipher cipher;
    private final MailOAuthClient client;
    private final MemberSmtpSettingsRepository smtp;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate credentialsTransaction;
    private final SecureRandom random = new SecureRandom();

    public MailOAuthService(JdbcTemplate jdbc, CurrentOrganization organization, Environment environment,
            MailSecretCipher cipher, MailOAuthClient client, MemberSmtpSettingsRepository smtp,
            Clock clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.organization = organization; this.environment = environment;
        this.cipher = cipher; this.client = client; this.smtp = smtp; this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.credentialsTransaction = new TransactionTemplate(transactions);
        // A provider can revoke the old refresh token immediately. Persist its replacement
        // independently of any caller transaction that may later fail while submitting mail.
        this.credentialsTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Status status() {
        UUID company = organization.currentCompany().getId();
        var connection = jdbc.query("select provider,email from mail_oauth_connection where empresa_id=?",
                (rs, row) -> new Connection(MailOAuthProvider.valueOf(rs.getString(1)), rs.getString(2)), company)
                .stream().findFirst().orElse(null);
        return new Status(Arrays.stream(MailOAuthProvider.values())
                .map(provider -> new ProviderStatus(provider,
                        cipher.isConfigured() && provider.configuration(environment).available())).toList(), connection);
    }

    public Authorization connect(MailOAuthProvider provider, String email) {
        var config = requireConfiguration(provider);
        UUID company = organization.currentCompany().getId();
        String state = randomToken();
        String verifier = randomToken();
        transaction.executeWithoutResult(tx -> {
            lockCompany(company);
            jdbc.update("delete from mail_oauth_flow where empresa_id=? or expires_at<?", company, timestamp(clock.instant()));
            jdbc.update("""
                    insert into mail_oauth_flow(empresa_id,state_hash,provider,email,verifier,expires_at)
                    values (?,?,?,?,?,?)
                    """, company, hash(state), provider.name(), email.trim(), cipher.encrypt(verifier, company),
                    timestamp(clock.instant().plusSeconds(600)));
        });
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("client_id", config.clientId());
        parameters.put("redirect_uri", config.redirectUri());
        parameters.put("response_type", "code");
        parameters.put("scope", provider.scope);
        parameters.put("state", state);
        parameters.put("code_challenge", challenge(verifier));
        parameters.put("code_challenge_method", "S256");
        parameters.put("login_hint", email.trim());
        parameters.put("prompt", "consent");
        if (provider == MailOAuthProvider.GOOGLE) parameters.put("access_type", "offline");
        return new Authorization(provider.authorizationEndpoint + "?" + MailOAuthClient.encode(parameters));
    }

    public void callback(String state, String code, String error) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}")) throw invalidFlow();
        // Consuming state is committed BEFORE exchanging the code, so failures and duplicate callbacks cannot replay it.
        Flow flow = transaction.execute(tx -> {
            var flows = jdbc.query("""
                    update mail_oauth_flow set consumed=true where state_hash=? and expires_at>?
                    and consumed=false returning *
                    """, (rs, row) -> new Flow(rs.getObject("empresa_id", UUID.class),
                    MailOAuthProvider.valueOf(rs.getString("provider")), rs.getString("email"),
                    rs.getString("verifier")), hash(state), timestamp(clock.instant()));
            return flows.stream().findFirst().orElseThrow(MailOAuthService::invalidFlow);
        });
        if (error != null || code == null || code.isBlank() || code.length() > 8192) throw invalidFlow();
        var config = requireConfiguration(flow.provider());
        var form = baseForm(config);
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", config.redirectUri());
        form.put("code_verifier", cipher.decrypt(flow.verifier(), flow.company()));
        var tokens = client.exchange(flow.provider(), form);
        if (tokens.refreshToken().isBlank()) throw MailOAuthClient.unavailable();
        client.verifyIdentity(flow.provider(), flow.email(), tokens.accessToken());
        transaction.executeWithoutResult(tx -> {
            lockCompany(flow.company());
            // A disconnect/new attempt during the network exchange invalidates this authorization.
            int completed = jdbc.update("delete from mail_oauth_flow where empresa_id=? and state_hash=? and consumed=true",
                    flow.company(), hash(state));
            if (completed != 1) throw invalidFlow();
            storeTokens(flow.company(), flow.provider(), flow.email(), tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn());
            var existing = smtp.findById(flow.company());
            var command = new MemberSmtpSettingsCommand(true, flow.provider().host, 587, flow.email(), null,
                    flow.email(), existing.map(MemberSmtpSettings::getFromName).orElse(null), true, false);
            var settings = existing.orElseGet(() -> new MemberSmtpSettings(flow.company(), command));
            settings.update(command);
            settings.clearPassword();
            smtp.save(settings);
        });
    }

    public void disconnect() {
        UUID company = organization.currentCompany().getId();
        transaction.executeWithoutResult(tx -> {
            lockCompany(company);
            jdbc.update("delete from mail_oauth_connection where empresa_id=?", company);
            jdbc.update("delete from mail_oauth_flow where empresa_id=?", company);
            // Never silently fall back to an older stored SMTP password after disconnecting OAuth.
            smtp.findById(company).ifPresent(settings -> {
                settings.update(new MemberSmtpSettingsCommand(false, settings.getHost(), settings.getPort(),
                        settings.getUsername(), null, settings.getFromEmail(), settings.getFromName(),
                        settings.isStartTls(), settings.isSslEnabled()));
                smtp.save(settings);
            });
        });
    }

    public Optional<SmtpCredentials> smtpCredentials(UUID company) {
        return credentialsTransaction.execute(tx -> {
            var connections = jdbc.query("select * from mail_oauth_connection where empresa_id=? for update",
                    (rs, row) -> new StoredConnection(MailOAuthProvider.valueOf(rs.getString("provider")),
                            rs.getString("email"), rs.getString("access_token"), rs.getString("refresh_token"),
                            rs.getTimestamp("expires_at").toInstant()), company);
            if (connections.isEmpty()) return Optional.empty();
            var saved = connections.getFirst();
            String access = cipher.decrypt(saved.accessToken(), company);
            if (!saved.expiresAt().isAfter(clock.instant().plusSeconds(90))) {
                var config = requireConfiguration(saved.provider());
                String refresh = cipher.decrypt(saved.refreshToken(), company);
                var form = baseForm(config);
                form.put("grant_type", "refresh_token");
                form.put("refresh_token", refresh);
                var tokens = client.exchange(saved.provider(), form);
                access = tokens.accessToken();
                storeTokens(company, saved.provider(), saved.email(), access,
                        tokens.refreshToken().isBlank() ? refresh : tokens.refreshToken(), tokens.expiresIn());
            }
            return Optional.of(new SmtpCredentials(saved.email(), access, saved.provider().host, 587, saved.provider()));
        });
    }

    public org.springframework.mail.javamail.JavaMailSender googleSender(SmtpCredentials credentials) {
        if (credentials.provider() != MailOAuthProvider.GOOGLE) throw new IllegalArgumentException("Google account required");
        return new GmailApiMailSender(client, credentials.accessToken());
    }

    private void storeTokens(UUID company, MailOAuthProvider provider, String email, String access, String refresh, long expiresIn) {
        jdbc.update("""
                insert into mail_oauth_connection(empresa_id,provider,email,access_token,refresh_token,expires_at)
                values(?,?,?,?,?,?) on conflict(empresa_id) do update set provider=excluded.provider,
                email=excluded.email,access_token=excluded.access_token,refresh_token=excluded.refresh_token,
                expires_at=excluded.expires_at
                """, company, provider.name(), email, cipher.encrypt(access, company), cipher.encrypt(refresh, company),
                timestamp(clock.instant().plusSeconds(expiresIn)));
    }
    private void lockCompany(UUID company) {
        jdbc.queryForObject("select id from empresa where id=? for update", UUID.class, company);
    }
    private MailOAuthProvider.Configuration requireConfiguration(MailOAuthProvider provider) {
        var config = provider.configuration(environment);
        if (!cipher.isConfigured() || !config.available())
            throw new IllegalStateException("La conexión con este proveedor aún no está configurada.");
        return config;
    }
    private static Map<String, String> baseForm(MailOAuthProvider.Configuration config) {
        var form = new LinkedHashMap<String, String>();
        form.put("client_id", config.clientId()); form.put("client_secret", config.clientSecret());
        return form;
    }
    private String randomToken() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String hash(String value) { return HexFormat.of().formatHex(digest(value)); }
    static String challenge(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(value)); }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
    private static IllegalArgumentException invalidFlow() {
        return new IllegalArgumentException("La autorización ha caducado o fue cancelada. Inicia la conexión de nuevo.");
    }
    public record Authorization(String authorizationUrl) { }
    public record ProviderStatus(MailOAuthProvider provider, boolean available) { }
    public record Connection(MailOAuthProvider provider, String email) { }
    public record Status(List<ProviderStatus> providers, Connection connection) { }
    public record SmtpCredentials(String username, String accessToken, String host, int port, MailOAuthProvider provider) {
        @Override public String toString() { return "SmtpOAuthCredentials[redacted]"; }
    }
    private record Flow(UUID company, MailOAuthProvider provider, String email, String verifier) {
        @Override public String toString() { return "OAuthFlow[redacted]"; }
    }
    private record StoredConnection(MailOAuthProvider provider, String email, String accessToken, String refreshToken, Instant expiresAt) {
        @Override public String toString() { return "OAuthStoredConnection[redacted]"; }
    }
}
