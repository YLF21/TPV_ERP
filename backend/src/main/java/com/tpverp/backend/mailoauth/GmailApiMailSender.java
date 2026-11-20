package com.tpverp.backend.mailoauth;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Reuses MIME composition (including card attachments) without requesting Gmail mailbox access. */
public final class GmailApiMailSender extends JavaMailSenderImpl {
    private final MailOAuthClient client;
    private final String accessToken;
    GmailApiMailSender(MailOAuthClient client, String accessToken) {
        this.client = client; this.accessToken = accessToken;
    }
    @Override protected void doSend(MimeMessage[] messages, Object[] originals) {
        for (var message : messages) client.sendGoogle(message, accessToken);
    }
}
