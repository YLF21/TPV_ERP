package com.tpverp.saas.mail;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tpverp.saas.admin.AdminAuditService;
import com.tpverp.saas.admin.IntegrationSecretCipher;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SmtpSettings {
    public enum Security { STARTTLS, SSL }
    public record Update(@NotNull Boolean enabled, @NotNull @Size(max=253) String host,
            @NotNull @Min(1) @Max(65535) Integer port, @NotNull Security security,
            @Size(max=254) String username,
            @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @Size(max=2048) String password, Boolean clearPassword,
            @NotNull @Size(max=254) String fromAddress, @Size(max=120) String fromName, @Size(max=254) String replyTo,
            @NotNull @Size(max=1024) String portalUrl, @NotNull @PositiveOrZero Long version) {
        @Override public String toString() { return "SMTP settings update"; }
    }
    public record View(boolean enabled, String host, int port, Security security, String username, boolean hasPassword,
            String fromAddress, String fromName, String replyTo, String portalUrl, long version,
            boolean cryptoReady, boolean configured, boolean testReady, MailOAuthProvider oauthProvider, UUID id, boolean defaultAccount) {}
    record Saved(View view, String encryptedPassword) {
        @Override public String toString() { return "Saved SMTP settings"; }
        boolean available() { return view.enabled() && view.configured(); }
    }

    private static final Pattern HOST = Pattern.compile("(?i)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private final JdbcTemplate jdbc;
    private final IntegrationSecretCipher cipher;
    private final AdminAuditService audit;
    private final Environment environment;

    public SmtpSettings(JdbcTemplate jdbc, IntegrationSecretCipher cipher, AdminAuditService audit, Environment environment) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.audit = audit;
        this.environment = environment;
    }

    public View get() { return load(null, "").view(); }
    public View get(UUID id) { return load(id, "").view(); }
    Saved lockForSending() { return load(null, " for share of s"); }
    Saved lockForSending(UUID id) { return load(id, " for share of s"); }

    public List<View> accounts() {
        return jdbc.query(SELECT + """
                where s.removed_at is null and (s.listed or s.host<>'' or s.from_address<>'' or s.username<>'')
                order by s.singleton nulls last,lower(s.from_address),s.id
                """, (rs,row) -> read(rs).view());
    }

    @Transactional
    public long reserveTest(UUID id) {
        var saved = load(id, "");
        if (!saved.view().testReady()) throw conflict("SMTP_NOT_READY", "Guarde el remitente y la conexión de correo antes de probar");
        if (jdbc.update("""
                update saas_communication_pacing set last_test_at=clock_timestamp() where singleton
                  and (last_test_at is null or last_test_at < clock_timestamp()-interval '30 seconds')
                """) == 0) {
            throw problem(HttpStatus.TOO_MANY_REQUESTS, "SMTP_TEST_RATE_LIMITED", "Espere unos segundos antes de otra prueba");
        }
        return saved.view().version();
    }

    private static final String SELECT = """
            select s.*, c.access_token,c.refresh_token from saas_smtp_settings s
            left join saas_mail_oauth_connection c on c.account_id=s.id
                and c.provider=s.oauth_provider and c.email=s.username
            """;

    private Saved load(UUID id, String lock) {
        return jdbc.query(SELECT + " where s.removed_at is null and " + (id == null ? "s.singleton" : "s.id=?") + lock,
                (rs,row) -> read(rs), id == null ? new Object[0] : new Object[]{id})
                .stream().findFirst().orElseThrow(SmtpSettings::missing);
    }

    static ResponseStatusException missing() {
        return problem(HttpStatus.NOT_FOUND, "SMTP_ACCOUNT_NOT_FOUND", "Cuenta de correo no disponible");
    }

    private Saved read(java.sql.ResultSet rs) throws java.sql.SQLException {
        String encrypted = rs.getString("encrypted_password");
        String username = rs.getString("username");
        String host = rs.getString("host");
        String from = rs.getString("from_address");
        String portal = rs.getString("portal_url");
        String providerName = rs.getString("oauth_provider");
        var provider = providerName == null ? null : MailOAuthProvider.valueOf(providerName);
        boolean testReady = provider == null ? transportReady(host, from, username, encrypted)
                : !from.isEmpty() && !username.isEmpty()
                    && oauthReady(provider, rs.getString("access_token"), rs.getString("refresh_token"));
        boolean configured = !portal.isEmpty() && testReady;
        return new Saved(new View(rs.getBoolean("enabled"), host, rs.getInt("port"),
                Security.valueOf(rs.getString("security")), username, encrypted != null, from,
                rs.getString("from_name"), rs.getString("reply_to"), portal, rs.getLong("version"),
                cipher.configured(), configured, testReady, provider, rs.getObject("id",UUID.class), rs.getBoolean("singleton")), encrypted);
    }

    @Transactional
    public View save(Update input) {
        return save(load(null, " for update of s"), input, true, false);
    }

    @Transactional
    public View create(Update input) {
        if (input.version() != 0) throw invalid();
        // Serialize selection of the empty default slot. A newly configured account never enables automatic notices.
        var base = load(null, " for update of s");
        UUID id = base.view().id();
        boolean unused = jdbc.queryForObject("""
                select not listed and host='' and from_address='' and username='' and encrypted_password is null
                    and not exists (select 1 from saas_mail_oauth_flow where account_id=s.id)
                  from saas_smtp_settings s where id=?
                """, Boolean.class,id);
        if (!unused) {
            id = jdbc.queryForObject("insert into saas_smtp_settings(listed) values(true) returning id",UUID.class);
            base = load(id, "");
        }
        return save(base,input,false,true);
    }

    @Transactional
    public View save(UUID id, Update input) {
        return save(load(id, " for update of s"), input, false, false);
    }

    private View save(Saved saved, Update input, boolean notices, boolean creating) {
        if (!notices && clean(input.fromAddress()).isEmpty()) throw invalid();
        boolean enabled = notices ? input.enabled() : saved.view().enabled();
        if (!creating && saved.view().version() != input.version()) throw conflict("SMTP_VERSION_CONFLICT", "La configuración SMTP ha cambiado");
        if (Stream.of(input.host(), input.username(), input.fromAddress(), input.fromName(), input.replyTo(), input.portalUrl())
                .anyMatch(value -> value != null && value.codePoints().anyMatch(Character::isISOControl))) throw invalid();
        String host = clean(input.host()).toLowerCase(Locale.ROOT);
        String username = clean(input.username());
        String from = clean(input.fromAddress());
        String reply = clean(input.replyTo());
        String portal = notices ? clean(input.portalUrl()) : saved.view().portalUrl();
        if (!host.isEmpty() && !HOST.matcher(host).matches()) throw invalid();
        if (!from.isEmpty()) validateAddress(from);
        if (saved.view().defaultAccount() && !saved.view().fromAddress().isEmpty()
                && !saved.view().fromAddress().equalsIgnoreCase(from)) requireNoPendingNotices(jdbc);
        if (!reply.isEmpty()) validateAddress(reply);
        if (!portal.isEmpty()) validatePortal(portal);
        String encrypted = saved.encryptedPassword();
        boolean replace = input.password() != null && !input.password().isEmpty();
        var provider = saved.view().oauthProvider();
        if (provider != null && (replace || Boolean.TRUE.equals(input.clearPassword())
                || !host.equals(provider.host) || !username.equals(saved.view().username()) || !from.equals(username)
                || input.port() != 587 || input.security() != Security.STARTTLS)) {
            throw conflict("MAIL_OAUTH_DISCONNECT_REQUIRED", "Desconecte la cuenta antes de cambiar el transporte");
        }
        if (Boolean.TRUE.equals(input.clearPassword())) {
            if (replace) throw invalid();
            encrypted = null;
        }
        if (replace) {
            if (username.isEmpty() || input.password().indexOf(0) >= 0) throw invalid();
            if (!cipher.configured()) throw conflict("SMTP_ENCRYPTION_UNAVAILABLE", "Falta la clave de cifrado de integraciones");
            encrypted = cipher.encryptExact(input.password());
        } else if (encrypted != null && (!host.equals(saved.view().host()) || !username.equals(saved.view().username())
                || input.port() != saved.view().port() || input.security() != saved.view().security())) {
            throw conflict("SMTP_PASSWORD_REQUIRED", "Introduzca de nuevo la contraseña al cambiar el servidor o el usuario SMTP");
        }
        if (enabled && provider == null && !complete(host, from, portal, username, encrypted)) throw invalid();
        try {
            jdbc.update("""
                    update saas_smtp_settings set enabled=?,host=?,port=?,security=?,username=?,encrypted_password=?,
                    from_address=?,from_name=?,reply_to=?,portal_url=?,version=version+1,listed=listed or ? where id=?
                    """, enabled, host, input.port(), input.security().name(), username, encrypted,
                    from, clean(input.fromName()), reply, portal, !notices, saved.view().id());
        } catch (DuplicateKeyException duplicate) {
            throw conflict("SMTP_ACCOUNT_DUPLICATE","Ya existe una cuenta con ese correo");
        }
        jdbc.update("delete from saas_mail_oauth_flow where account_id=?",saved.view().id());
        var updated = get(saved.view().id());
        if (enabled && !updated.configured()) throw invalid();
        audit.log(creating ? "CREATE_SMTP_ACCOUNT" : "UPDATE_SMTP_SETTINGS", "SMTP_SETTINGS", updated.id().toString());
        return updated;
    }

    public record Notices(@NotNull UUID accountId, @NotNull Boolean enabled, @NotNull @Size(max=1024) String portalUrl,
                          @NotNull @PositiveOrZero Long version, @NotNull UUID defaultAccountId,
                          @NotNull @PositiveOrZero Long defaultVersion, boolean confirmPending) {}

    @Transactional
    public View saveNotices(Notices input) {
        // All default changes lock the current default first, then the replacement account.
        var prior = load(null, " for update of s").view();
        if (!prior.id().equals(input.defaultAccountId()) || prior.version() != input.defaultVersion()) throw staleSettings();
        boolean switching = !prior.id().equals(input.accountId());
        var selected = switching ? load(input.accountId(), " for update of s").view() : prior;
        if (selected.version() != input.version()) throw staleSettings();
        String portal = clean(input.portalUrl());
        if (input.enabled() || !portal.isEmpty()) validatePortal(portal);
        if (input.enabled() && !selected.testReady()) throw conflict("SMTP_NOT_READY","Configure la cuenta antes de activar avisos");
        if (switching) {
            if (!input.confirmPending()) requireNoPendingNotices(jdbc);
            jdbc.update("""
                    update saas_smtp_settings set singleton=null,enabled=false,portal_url='',version=version+1 where id=?
                    """,prior.id());
        }
        jdbc.update("""
                update saas_smtp_settings set singleton=true,enabled=?,portal_url=?,version=? where id=?
                """,input.enabled(),portal,Math.max(prior.version(),selected.version())+1,selected.id());
        audit.log("UPDATE_SMTP_NOTICES","SMTP_SETTINGS",selected.id().toString());
        return get(selected.id());
    }

    static void requireNoPendingNotices(JdbcTemplate jdbc) {
        // Serialize the identity change with concurrent inserts into the decision outbox.
        jdbc.execute("lock table saas_client_decision_email in share mode");
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from saas_client_decision_email where status in ('PENDING','PROCESSING'))
                """,Boolean.class)))
            throw conflict("SMTP_NOTICES_PENDING","Seleccione y confirme la cuenta de los avisos pendientes");
    }

    private static ResponseStatusException staleSettings() {
        return conflict("SMTP_VERSION_CONFLICT","La configuración SMTP ha cambiado");
    }

    @Transactional
    public void remove(UUID id, long version) {
        var account = load(id, " for update of s").view();
        if (account.version() != version) throw conflict("SMTP_VERSION_CONFLICT","La configuración SMTP ha cambiado");
        boolean busy = jdbc.queryForObject("""
                select exists(select 1 from saas_communication c join saas_communication_recipient r on r.communication_id=c.id
                              where c.sender_account_id=? and r.status in ('PENDING','SENDING'))
                """,Boolean.class,id);
        if (busy) throw conflict("SMTP_ACCOUNT_IN_USE","Hay envíos pendientes con esta cuenta");
        if (account.defaultAccount()) requireNoPendingNotices(jdbc);
        jdbc.update("delete from saas_mail_oauth_flow where account_id=?",id);
        jdbc.update("delete from saas_mail_oauth_connection where account_id=?",id);
        jdbc.update("""
                update saas_smtp_settings set singleton=null,removed_at=clock_timestamp(),enabled=false,
                    encrypted_password=null,oauth_provider=null,version=version+1 where id=?
                """,id);
        if (account.defaultAccount()) {
            jdbc.update("insert into saas_smtp_settings(singleton,version,portal_url) values(true,?,?)",
                    version+1,account.portalUrl());
        }
        audit.log("DELETE_SMTP_ACCOUNT","SMTP_SETTINGS",id.toString());
    }

    private boolean oauthReady(MailOAuthProvider provider, String access, String refresh) {
        if (!provider.configuration(environment).available() || !cipher.configured()) return false;
        try {
            return cipher.decrypt(access) != null && cipher.decrypt(refresh) != null;
        } catch (IllegalStateException unreadable) {
            return false;
        }
    }

    private boolean complete(String host, String from, String portal, String username, String encrypted) {
        return !portal.isEmpty() && transportReady(host, from, username, encrypted);
    }

    private boolean transportReady(String host, String from, String username, String encrypted) {
        if (host.isEmpty() || from.isEmpty()) return false;
        if (username.isEmpty()) return encrypted == null;
        if (encrypted == null || !cipher.configured()) return false;
        try {
            // A restored database may contain a password sealed with a different installation key.
            return cipher.decrypt(encrypted) != null;
        } catch (IllegalStateException unavailable) {
            return false;
        }
    }

    static void validateAddress(String address) {
        try {
            if (address == null || address.isBlank() || address.codePoints().anyMatch(Character::isISOControl)) throw invalid();
            var parsed = new InternetAddress(address, true);
            parsed.validate();
            if (parsed.isGroup() || !address.equals(parsed.getAddress()) || parsed.getPersonal() != null) throw invalid();
        } catch (AddressException ex) { throw invalid(); }
    }

    static void validatePortal(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            // Public portal links must be safe to embed in messages sent outside the operator network.
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || !HOST.matcher(host).matches() || !host.matches("(?i).+\\.[a-z][a-z0-9-]+")
                    || host.toLowerCase(Locale.ROOT).matches(".*\\.(?:localhost|local|internal|lan|home|invalid)")) throw invalid();
        } catch (IllegalArgumentException ex) { throw invalid(); }
    }

    private static String clean(String input) { return input == null ? "" : input.strip(); }
    static ResponseStatusException invalid() { return problem(HttpStatus.BAD_REQUEST, "SMTP_INVALID", "Datos SMTP no válidos"); }
    static ResponseStatusException conflict(String code, String reason) { return problem(HttpStatus.CONFLICT, code, reason); }
    static ResponseStatusException problem(HttpStatus status, String code, String reason) {
        var ex = new ResponseStatusException(status, reason);
        ex.getBody().setProperty("code", code);
        return ex;
    }
}
