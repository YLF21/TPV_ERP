package com.tpverp.backend.communications;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class CommunicationPublicLinks {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String baseUrl;
    private final byte[] signingKey;

    public CommunicationPublicLinks(JdbcTemplate jdbc, Clock clock,
            @Value("${tpv.communications.public-base-url:}") String baseUrl,
            @Value("${tpv.communications.unsubscribe-secret:}") String secret) {
        this.jdbc = jdbc; this.clock = clock;
        this.baseUrl = validPublicBase(baseUrl) ? baseUrl.replaceAll("/+$", "") : null;
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(secret); } catch (IllegalArgumentException ex) { decoded = new byte[0]; }
        this.signingKey = decoded.length >= 32 ? decoded.clone() : null;
    }

    public boolean configured() { return baseUrl != null && signingKey != null; }

    public String create(UUID companyId, UUID customerId, String email) {
        if (!configured()) throw new IllegalStateException("Configure un enlace público HTTPS de bajas y su clave antes de enviar campañas");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into communication_unsubscribe_token(id,empresa_id,customer_id,email,created_at) values(?,?,?,?,?)",
                id,companyId,customerId,email,Timestamp.from(clock.instant()));
        return baseUrl + "/api/public/communications/unsubscribe?token=" + id + "." + signature(id);
    }

    public void verify(String token) {
        UUID id = verifySignature(token);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from communication_unsubscribe_token where id=?)",Boolean.class,id)))
            throw invalid();
    }

    public boolean isCurrentLink(String url) {
        if (!configured() || url == null) return false;
        String prefix = baseUrl + "/api/public/communications/unsubscribe?token=";
        if (!url.startsWith(prefix)) return false;
        try { verify(url.substring(prefix.length())); return true; }
        catch (ResponseStatusException exception) { return false; }
    }

    @Transactional
    public void unsubscribe(String token) {
        UUID id = verifySignature(token);
        var tokens = jdbc.query("select empresa_id,customer_id,email from communication_unsubscribe_token where id=? for update",
                (rs,n) -> new Target(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3)),id);
        if (tokens.isEmpty()) throw invalid();
        Target target = tokens.getFirst();
        var now = Timestamp.from(clock.instant());
        jdbc.update("insert into communication_suppression(empresa_id,email,created_at,reason) values(?,?,?,'UNSUBSCRIBED') on conflict(empresa_id,email) do nothing",
                target.companyId(),target.email(),now);
        // Apply only to the same address: an old token must not revoke a replacement address's preferences.
        jdbc.update("update cliente set commercial_consent=false,preferred_commercial_channel_id=null,version=version+1 where id=? and empresa_id=? and lower(trim(email))=? and (commercial_consent or preferred_commercial_channel_id is not null)",
                target.customerId(),target.companyId(),target.email());
        jdbc.update("update communication_unsubscribe_token set used_at=coalesce(used_at,?) where id=?",now,id);
        jdbc.update("update communication_delivery set status='SUPPRESSED',error_message='Baja solicitada' where empresa_id=? and email=? and status='PENDING'",
                target.companyId(),target.email());
    }

    UUID verifySignature(String token) {
        if (signingKey == null || token == null || token.length() > 150) throw invalid();
        String[] parts = token.split("\\.",-1);
        if (parts.length != 2) throw invalid();
        UUID id;
        try { id = UUID.fromString(parts[0]); } catch (IllegalArgumentException ex) { throw invalid(); }
        if (!MessageDigest.isEqual(signature(id).getBytes(StandardCharsets.US_ASCII),parts[1].getBytes(StandardCharsets.US_ASCII))) throw invalid();
        return id;
    }
    private String signature(UUID id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey,"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("unsubscribe:"+id).getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("No se pudo preparar el enlace de baja",ex); }
    }
    static boolean validPublicBase(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme()) && host.contains(".")
                && !host.matches("[0-9.]+") && !host.contains(":") && !host.endsWith(".localhost")
                && !host.endsWith(".local") && !host.endsWith(".internal") && !host.endsWith(".test")
                && !host.endsWith(".invalid") && !host.equals("example.com") && !host.endsWith(".example.com")
                && !host.equals("example.org") && !host.endsWith(".example.org") && !host.equals("example.net") && !host.endsWith(".example.net")
                && !host.endsWith(".") && !host.endsWith(".example")
                && (uri.getPort()==-1 || (uri.getPort()>0 && uri.getPort()<=65535))
                && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException ex) { return false; }
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enlace de baja no válido"); }
    private record Target(UUID companyId, UUID customerId, String email) { }
}
