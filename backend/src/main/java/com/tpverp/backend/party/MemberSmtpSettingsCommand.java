package com.tpverp.backend.party;

public record MemberSmtpSettingsCommand(
        boolean enabled,
        String host,
        int port,
        String username,
        String password,
        String fromEmail,
        String fromName,
        boolean startTls,
        boolean sslEnabled) {
}
