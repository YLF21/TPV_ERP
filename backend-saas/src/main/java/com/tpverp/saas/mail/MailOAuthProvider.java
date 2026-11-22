package com.tpverp.saas.mail;

import java.net.URI;
import java.util.Locale;
import org.springframework.core.env.Environment;

/** Provider endpoints are fixed; account configuration cannot redirect secret-bearing requests. */
public enum MailOAuthProvider {
    GOOGLE("https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
            "https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/userinfo.email", "smtp.gmail.com"),
    MICROSOFT("https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            "offline_access https://outlook.office.com/SMTP.Send", "smtp.office365.com");

    final String authorizationEndpoint;
    final String tokenEndpoint;
    final String scope;
    final String host;

    MailOAuthProvider(String authorizationEndpoint, String tokenEndpoint, String scope, String host) {
        this.authorizationEndpoint = authorizationEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.scope = scope;
        this.host = host;
    }

    Configuration configuration(Environment environment) {
        String prefix = "tpv.saas.mail.oauth." + name().toLowerCase(Locale.ROOT) + ".";
        return new Configuration(environment.getProperty(prefix + "client-id", ""),
                environment.getProperty(prefix + "client-secret", ""),
                environment.getProperty("tpv.saas.mail.oauth.redirect-uri", ""));
    }

    record Configuration(String clientId, String clientSecret, String redirectUri) {
        boolean available() {
            if (clientId.isBlank() || clientSecret.isBlank() || redirectUri.isBlank()) return false;
            try {
                URI uri = URI.create(redirectUri);
                boolean secure = "https".equals(uri.getScheme());
                boolean local = "http".equals(uri.getScheme())
                        && ("127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()));
                return (secure || local) && uri.getHost() != null && uri.getUserInfo() == null
                        && uri.getQuery() == null && uri.getFragment() == null
                        && uri.getPort() != 0 && uri.getPort() <= 65535
                        && "/api/v1/mail-oauth/callback".equals(uri.getRawPath());
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
        @Override public String toString() { return "OAuthConfiguration[redacted]"; }
    }
}
