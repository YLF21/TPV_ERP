package com.tpverp.saas.mail;

import com.tpverp.saas.admin.AdminAuditService;
import com.tpverp.saas.admin.IntegrationSecretCipher;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Stream;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MailOAuthService {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final IntegrationSecretCipher cipher;
    private final MailOAuthClient client;
    private final AdminAuditService audit;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate credentialTransaction;
    private final SecureRandom random = new SecureRandom();

    public MailOAuthService(JdbcTemplate jdbc, Environment environment, IntegrationSecretCipher cipher,
            MailOAuthClient client, AdminAuditService audit, Clock clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.environment = environment;
        this.cipher = cipher;
        this.client = client;
        this.audit = audit;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.credentialTransaction = new TransactionTemplate(transactions);
        this.credentialTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Connect(@NotNull MailOAuthProvider provider, @NotBlank @Size(max=254) String email,
            @NotNull @PositiveOrZero Long version, @NotNull Boolean enabled, @Size(max=120) String fromName,
            @Size(max=254) String replyTo, @NotNull @Size(max=1024) String portalUrl, UUID accountId) {}
    public record ProviderStatus(MailOAuthProvider provider, boolean available) {}
    public record Connection(MailOAuthProvider provider, String email) {}
    public record Status(List<ProviderStatus> providers, Connection connection) {}
    public record Authorization(String authorizationUrl) {
        @Override public String toString() { return "Mail authorization"; }
    }
    private record Flow(UUID staffId, Connect snapshot, String verifier) {
        @Override public String toString() { return "Mail authorization flow"; }
    }

    public Status status(UUID accountId) {
        var providers = Arrays.stream(MailOAuthProvider.values()).map(provider -> new ProviderStatus(provider,
                cipher.configured() && provider.configuration(environment).available())).toList();
        var connection = jdbc.query("""
                select provider,email from saas_mail_oauth_connection c join saas_smtp_settings s on s.id=c.account_id
                where s.oauth_provider=c.provider and s.username=c.email and s.removed_at is null
                  and (s.id=? or (?::uuid is null and s.singleton))
                """, (rs,row) -> new Connection(MailOAuthProvider.valueOf(rs.getString(1)), rs.getString(2)),accountId,accountId);
        return new Status(providers, connection.stream().findFirst().orElse(null));
    }

    public Authorization connect(Connect input, String username) {
        var config = requireConfiguration(input.provider());
        if (Stream.of(input.email(), input.fromName(), input.replyTo(), input.portalUrl())
                .anyMatch(s -> s != null && s.codePoints().anyMatch(Character::isISOControl))) throw SmtpSettings.invalid();
        String email = input.email().strip();
        String fromName = clean(input.fromName());
        String reply = clean(input.replyTo());
        String portal = clean(input.portalUrl());
        SmtpSettings.validateAddress(email);
        if (!reply.isEmpty()) SmtpSettings.validateAddress(reply);
        if (input.accountId() == null && (input.enabled() || !portal.isEmpty())) SmtpSettings.validatePortal(portal);

        String state = randomToken();
        String verifier = randomToken();
        transaction.executeWithoutResult(tx -> {
            UUID staffId = jdbc.query("""
                    select id from saas_admin_user where username=? and active and not must_change_password for share
                    """, (rs,row) -> rs.getObject(1,UUID.class), username).stream().findFirst().orElseThrow(MailOAuthService::invalidFlow);
            var account = lockSettings(input.accountId(),input.version());
            jdbc.update("delete from saas_mail_oauth_flow where account_id=?",account.id());
            jdbc.update("""
                    insert into saas_mail_oauth_flow(account_id,state_hash,verifier,provider,email,staff_id,
                        settings_version,enabled,from_name,reply_to,portal_url,expires_at)
                    values(?,?,?,?,?,?,?,?,?,?,?,?)
                    """, account.id(),hash(state), cipher.encryptExact(verifier), input.provider().name(), email, staffId,
                    input.version(), input.accountId()==null ? input.enabled() : account.enabled(), fromName, reply,
                    input.accountId()==null ? portal : account.portal(), Timestamp.from(clock.instant().plusSeconds(600)));
        });
        var parameters = new LinkedHashMap<String,String>();
        parameters.put("client_id",config.clientId());
        parameters.put("redirect_uri",config.redirectUri());
        parameters.put("response_type","code");
        parameters.put("scope",input.provider().scope);
        parameters.put("state",state);
        parameters.put("code_challenge",challenge(verifier));
        parameters.put("code_challenge_method","S256");
        parameters.put("login_hint",email);
        parameters.put("prompt","consent");
        if (input.provider() == MailOAuthProvider.GOOGLE) parameters.put("access_type","offline");
        return new Authorization(input.provider().authorizationEndpoint + "?" + MailOAuthClient.encode(parameters));
    }

    public void callback(String state, String code, String error) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}")) throw invalidFlow();
        String stateHash = hash(state);
        // Commit the claim before contacting the provider. Even a failed exchange spends the state.
        var flow = transaction.execute(tx -> jdbc.query("""
                update saas_mail_oauth_flow set consumed=true
                where state_hash=? and not consumed and expires_at>? returning *
                """, (rs,row) -> new Flow(rs.getObject("staff_id",UUID.class),
                new Connect(MailOAuthProvider.valueOf(rs.getString("provider")), rs.getString("email"),
                        rs.getLong("settings_version"), rs.getBoolean("enabled"), rs.getString("from_name"),
                        rs.getString("reply_to"), rs.getString("portal_url"),rs.getObject("account_id",UUID.class)), rs.getString("verifier")),
                stateHash, Timestamp.from(clock.instant())).stream().findFirst().orElseThrow(MailOAuthService::invalidFlow));
        if (error != null || code == null || code.isBlank() || code.length()>8192) throw invalidFlow();
        var snapshot = flow.snapshot();
        var config = requireConfiguration(snapshot.provider());
        var form = baseForm(config);
        form.put("grant_type","authorization_code");
        form.put("code",code);
        form.put("redirect_uri",config.redirectUri());
        form.put("code_verifier",cipher.decrypt(flow.verifier()));
        var tokens = client.exchange(snapshot.provider(), form);
        if (tokens.refreshToken().isBlank()) throw invalidFlow();
        client.verifyIdentity(snapshot.provider(),snapshot.email(),tokens.accessToken());

        transaction.executeWithoutResult(tx -> {
            String staff = jdbc.query("""
                    select username from saas_admin_user where id=? and active and not must_change_password for share
                    """, (rs,row) -> rs.getString(1), flow.staffId()).stream().findFirst().orElseThrow(MailOAuthService::invalidFlow);
            var account = lockSettings(snapshot.accountId(),snapshot.version());
            if (account.defaultAccount() && !account.fromAddress().isEmpty()
                    && !account.fromAddress().equalsIgnoreCase(snapshot.email())) SmtpSettings.requireNoPendingNotices(jdbc);
            if (jdbc.update("delete from saas_mail_oauth_flow where state_hash=? and consumed and expires_at>?",
                    stateHash, Timestamp.from(clock.instant())) != 1) throw invalidFlow();
            jdbc.update("""
                    insert into saas_mail_oauth_connection(account_id,provider,email,access_token,refresh_token,expires_at)
                    values(?,?,?,?,?,?) on conflict(account_id) do update set provider=excluded.provider,email=excluded.email,
                    access_token=excluded.access_token,refresh_token=excluded.refresh_token,expires_at=excluded.expires_at
                    """, snapshot.accountId(),snapshot.provider().name(),snapshot.email(),cipher.encryptExact(tokens.accessToken()),
                    cipher.encryptExact(tokens.refreshToken()),expires(tokens));
            jdbc.update("""
                    update saas_smtp_settings set oauth_provider=?,enabled=?,host=?,port=587,security='STARTTLS',
                    username=?,encrypted_password=null,from_address=?,from_name=?,reply_to=?,portal_url=?,version=version+1
                    where id=?
                    """, snapshot.provider().name(),snapshot.enabled(),snapshot.provider().host,snapshot.email(),
                    snapshot.email(),snapshot.fromName(),snapshot.replyTo(),snapshot.portalUrl(),snapshot.accountId());
            audit.logMailConnection(staff,snapshot.provider().name(),snapshot.accountId());
        });
    }

    public void disconnect(UUID accountId, long version) {
        transaction.executeWithoutResult(tx -> {
            var account = lockSettings(accountId,version);
            jdbc.update("delete from saas_mail_oauth_flow where account_id=?",account.id());
            String provider = jdbc.queryForObject("select oauth_provider from saas_smtp_settings where id=?",String.class,account.id());
            if (provider == null) return;
            jdbc.update("delete from saas_mail_oauth_connection where account_id=?",account.id());
            jdbc.update("""
                    update saas_smtp_settings set oauth_provider=null,enabled=false,version=version+1 where id=?
                    """,account.id());
            audit.log("DISCONNECT_MAIL_OAUTH","SMTP_SETTINGS",account.id().toString(),provider);
        });
    }

    /** The caller holds a share lock on settings. This independent transaction locks only the token row.
     * Replacement and disconnect acquire the settings write lock before touching that row. */
    String accessToken(UUID accountId, MailOAuthProvider provider, String email) {
        return credentialTransaction.execute(tx -> jdbc.query("""
                select access_token,refresh_token,expires_at from saas_mail_oauth_connection
                where account_id=? and provider=? and email=? for update
                """, rs -> {
                    if (!rs.next()) throw MailOAuthClient.unavailable();
                    var config = requireConfiguration(provider);
                    if (rs.getTimestamp("expires_at").toInstant().isAfter(clock.instant().plusSeconds(90))) {
                        return cipher.decrypt(rs.getString("access_token"));
                    }
                    String refresh = cipher.decrypt(rs.getString("refresh_token"));
                    var form = baseForm(config);
                    form.put("grant_type","refresh_token");
                    form.put("refresh_token",refresh);
                    var tokens = client.exchange(provider,form);
                    String rotated = tokens.refreshToken().isBlank() ? refresh : tokens.refreshToken();
                    jdbc.update("""
                            update saas_mail_oauth_connection set access_token=?,refresh_token=?,expires_at=? where account_id=?
                            """, cipher.encryptExact(tokens.accessToken()),cipher.encryptExact(rotated),expires(tokens),accountId);
                    return tokens.accessToken();
                },accountId,provider.name(),email));
    }

    private record Account(UUID id, long version, boolean enabled, String portal, boolean defaultAccount, String fromAddress) {}
    private Account lockSettings(UUID id, long expectedVersion) {
        var account = jdbc.query("""
                select id,version,enabled,portal_url,singleton,from_address from saas_smtp_settings
                 where removed_at is null and (id=? or (?::uuid is null and singleton)) for update
                """,(rs,row) -> new Account(rs.getObject("id",UUID.class),rs.getLong("version"),
                rs.getBoolean("enabled"),rs.getString("portal_url"),rs.getBoolean("singleton"),rs.getString("from_address")),id,id)
                .stream().findFirst().orElseThrow(SmtpSettings::missing);
        if (expectedVersion != account.version())
            throw SmtpSettings.conflict("SMTP_VERSION_CONFLICT","La configuración SMTP ha cambiado");
        return account;
    }
    private MailOAuthProvider.Configuration requireConfiguration(MailOAuthProvider provider) {
        var config = provider.configuration(environment);
        if (!cipher.configured() || !config.available()) throw SmtpSettings.conflict("MAIL_OAUTH_UNAVAILABLE",
                "La conexión con este proveedor aún no está configurada");
        return config;
    }
    private static Map<String,String> baseForm(MailOAuthProvider.Configuration config) {
        var form = new LinkedHashMap<String,String>();
        form.put("client_id",config.clientId());
        form.put("client_secret",config.clientSecret());
        return form;
    }
    private Timestamp expires(MailOAuthClient.Tokens tokens) { return Timestamp.from(clock.instant().plusSeconds(tokens.expiresIn())); }
    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String hash(String value) { return HexFormat.of().formatHex(digest(value)); }
    static String challenge(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(value)); }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String clean(String value) { return value == null ? "" : value.strip(); }
    private static IllegalArgumentException invalidFlow() { return new IllegalArgumentException("Autorización no válida"); }
}
