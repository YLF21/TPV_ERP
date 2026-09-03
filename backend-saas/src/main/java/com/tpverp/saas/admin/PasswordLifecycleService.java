package com.tpverp.saas.admin;

import com.tpverp.saas.tenant.SaasTenantUser;
import com.tpverp.saas.tenant.SaasTenantUserRepository;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class PasswordLifecycleService {

    private final SecureRandom random = new SecureRandom();
    private final SaasAdminUserRepository admins;
    private final SaasTenantUserRepository tenants;
    private final AdminPasswordHasher passwords;
    private final SaasSessionTokenStore sessions;
    private final JdbcTemplate jdbc;
    private final IntegrationSecretCipher cipher;
    private final Clock clock;
    private final Duration resetLifetime;

    public PasswordLifecycleService(
            SaasAdminUserRepository admins,
            SaasTenantUserRepository tenants,
            AdminPasswordHasher passwords,
            SaasSessionTokenStore sessions,
            JdbcTemplate jdbc,
            IntegrationSecretCipher cipher,
            Clock clock,
            @Value("${tpv.saas.password-reset.lifetime:PT30M}") Duration resetLifetime) {
        this.admins = admins;
        this.tenants = tenants;
        this.passwords = passwords;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.clock = clock;
        if (resetLifetime == null || resetLifetime.isZero() || resetLifetime.isNegative()) {
            throw new IllegalArgumentException("La duracion del token de recuperacion debe ser positiva");
        }
        this.resetLifetime = resetLifetime;
    }

    @Transactional
    public void changeAuthenticated(String bearerToken, String currentPassword, String newPassword) {
        var identity = sessions.session(bearerToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida o caducada"));
        if (currentPassword.equals(newPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La nueva password debe ser diferente");
        }
        if ("admin".equals(identity.realm())) {
            SaasAdminUser user = admins.findByUsernameIgnoreCase(identity.username())
                    .filter(SaasAdminUser::isActive)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida o caducada"));
            verifyCurrent(currentPassword, user.getPasswordHash());
            user.changePasswordHash(passwords.hash(newPassword));
            user.passwordChanged();
            admins.save(user);
        } else if ("tenant".equals(identity.realm())) {
            SaasTenantUser user = tenants.findByUsernameIgnoreCase(identity.username())
                    .filter(SaasTenantUser::isActive)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida o caducada"));
            verifyCurrent(currentPassword, user.getPasswordHash());
            user.changePasswordHash(passwords.hash(newPassword));
            user.passwordChanged();
            tenants.save(user);
        } else {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesion invalida o caducada");
        }
        invalidateResetTokens(identity.realm(), identity.username(), clock.instant());
        sessions.revokeByUser(identity.realm(), identity.username());
    }

    @Transactional
    public void requestReset(String username, String remoteAddress) {
        if (!cipher.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El canal seguro de recuperacion no esta configurado");
        }
        String normalized = normalize(username);
        var admin = admins.findByUsernameIgnoreCase(normalized).filter(SaasAdminUser::isActive).orElse(null);
        var tenant = tenants.findByUsernameIgnoreCase(normalized).filter(SaasTenantUser::isActive).orElse(null);
        if ((admin == null) == (tenant == null)) {
            return;
        }
        lockReset(normalized);
        String realm = admin != null ? "admin" : "tenant";
        String canonicalUsername = admin != null ? admin.getUsername() : tenant.getUsername();
        String token = newToken();
        Instant now = clock.instant();
        jdbc.update("delete from saas_password_reset_token where realm = ? and lower(username_key) = lower(?) and consumed_at is null",
                realm, canonicalUsername);
        jdbc.update("""
                insert into saas_password_reset_token
                (id, realm, username_key, token_hash, requested_at, expires_at, requested_address)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), realm, canonicalUsername, SaasSessionTokenStore.hash(token), timestamp(now),
                timestamp(now.plus(resetLifetime)), safeAddress(remoteAddress));
        UUID notificationId = UUID.randomUUID();
        jdbc.update("""
                insert into saas_security_notification_outbox
                (id, idempotency_key, event_type, realm, username_key, encrypted_payload, status, created_at)
                values (?, ?, 'PASSWORD_RESET_REQUESTED', ?, ?, ?, 'PENDING', ?)
                """, notificationId, notificationId.toString(), realm, canonicalUsername, cipher.encrypt(token), timestamp(now));
    }

    @Transactional
    public void confirmReset(String token, String newPassword) {
        Instant now = clock.instant();
        ResetIdentity identity = jdbc.query("""
                select realm, username_key
                from saas_password_reset_token
                where token_hash = ? and consumed_at is null and expires_at > ?
                for update
                """, rs -> rs.next() ? new ResetIdentity(rs.getString(1), rs.getString(2)) : null,
                SaasSessionTokenStore.hash(token), timestamp(now));
        if (identity == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token de recuperacion invalido o caducado");
        }
        int consumed = jdbc.update("""
                update saas_password_reset_token set consumed_at = ?
                where token_hash = ? and consumed_at is null
                """, timestamp(now), SaasSessionTokenStore.hash(token));
        if (consumed != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token de recuperacion invalido o caducado");
        }
        if ("admin".equals(identity.realm())) {
            SaasAdminUser user = admins.findByUsernameIgnoreCase(identity.username())
                    .filter(SaasAdminUser::isActive)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token de recuperacion invalido o caducado"));
            user.changePasswordHash(passwords.hash(newPassword));
            user.passwordChanged();
            admins.save(user);
        } else if ("tenant".equals(identity.realm())) {
            SaasTenantUser user = tenants.findByUsernameIgnoreCase(identity.username())
                    .filter(SaasTenantUser::isActive)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token de recuperacion invalido o caducado"));
            user.changePasswordHash(passwords.hash(newPassword));
            user.passwordChanged();
            tenants.save(user);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token de recuperacion invalido o caducado");
        }
        invalidateResetTokens(identity.realm(), identity.username(), now);
        sessions.revokeByUser(identity.realm(), identity.username());
    }


    private void lockReset(String username) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?::text, 1))")) {
                statement.setString(1, username);
                statement.execute();
            }
            return null;
        });
    }

    private void invalidateResetTokens(String realm, String username, Instant now) {
        jdbc.update("""
                update saas_password_reset_token set consumed_at = ?
                where realm = ? and lower(username_key) = lower(?) and consumed_at is null
                """, timestamp(now), realm, username);
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private void verifyCurrent(String password, String hash) {
        if (!passwords.matches(password, hash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Password actual invalida");
        }
    }

    private String newToken() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeAddress(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        return address.length() <= 128 ? address : address.substring(0, 128);
    }

    private record ResetIdentity(String realm, String username) {
    }
}
