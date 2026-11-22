package com.tpverp.saas.mail;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPTransport;

/** A single decision or test message per connection. */
class DecisionSmtpConnection extends SMTPTransport {
    private boolean dataTerminatorStarted;

    DecisionSmtpConnection(Session session) {
        super(session, new URLName("smtp", null, -1, null, null, null));
    }

    void connectMicrosoft(String mailbox, String token) throws MessagingException {
        connect(MailOAuthProvider.MICROSOFT.host, 587, mailbox, token);
        // Angus accepts a connection without authenticating if AUTH was absent in EHLO after STARTTLS.
        // When AUTH is advertised our session permits XOAUTH2 as its only mechanism.
        if (!supportsExtension("AUTH")) throw new AuthenticationFailedException("SMTP_AUTH_FAILED");
    }

    @Override protected void sendCommand(String command) throws MessagingException {
        if (".".equals(command)) dataTerminatorStarted = true;
        super.sendCommand(command);
    }

    void deliver(MimeMessage message) throws MessagingException {
        try {
            sendMessage(message, message.getAllRecipients());
        } catch (MessagingException failure) {
            // Once the terminating dot has been written the relay may already have accepted the message.
            // A 4xx/5xx reply at this stage is an explicit rejection and remains safe to retry.
            boolean rejected = failure instanceof SMTPSendFailedException smtp
                    && smtp.getReturnCode() >= 400 && smtp.getReturnCode() < 600;
            if (dataTerminatorStarted && !rejected) throw new SmtpTransport.Failure("SMTP_DELIVERY_UNCERTAIN");
            throw failure;
        }
    }

    void closeQuietly() {
        try { close(); } catch (MessagingException ignored) { }
    }
}
