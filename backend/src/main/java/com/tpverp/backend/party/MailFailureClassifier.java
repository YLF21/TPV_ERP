package com.tpverp.backend.party;

import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;

/** Only definite pre-submission connection failures may be retried automatically. */
public final class MailFailureClassifier {
    private MailFailureClassifier() {}
    private enum Outcome { RETRYABLE, FAILED, UNCERTAIN }

    public static boolean canRetry(Throwable failure) { return classify(failure) == Outcome.RETRYABLE; }
    public static boolean isUncertain(Throwable failure) { return classify(failure) == Outcome.UNCERTAIN; }

    private static Outcome classify(Throwable failure) {
        var causes = causes(failure);
        // A message accepted for any recipient must never be resent automatically, even if another failed.
        if (causes.stream().anyMatch(cause -> cause instanceof SendFailedException sent && present(sent.getValidSentAddresses())))
            return Outcome.UNCERTAIN;
        if (causes.stream().anyMatch(cause -> cause instanceof AuthenticationFailedException
                || cause instanceof MailAuthenticationException || cause instanceof AddressException
                || cause instanceof SSLHandshakeException
                || cause instanceof SendFailedException sent && present(sent.getInvalidAddresses())))
            return Outcome.FAILED;
        if (causes.stream().anyMatch(cause -> cause instanceof ConnectException || cause instanceof UnknownHostException))
            return Outcome.RETRYABLE;
        if (failure instanceof MailParseException || failure instanceof MailPreparationException
                || failure instanceof IllegalArgumentException
                || failure instanceof IllegalStateException && isMessageKey(failure.getMessage())) return Outcome.FAILED;
        // Includes connection loss/timeouts after DATA and unexpected exceptions after a send attempt.
        return Outcome.UNCERTAIN;
    }

    private static boolean present(Address[] addresses) { return addresses != null && addresses.length > 0; }

    private static List<Throwable> causes(Throwable failure) {
        var result = new ArrayList<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        var queue = new ArrayDeque<Throwable>();
        if (failure != null) queue.add(failure);
        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            if (!seen.add(current)) continue;
            result.add(current);
            if (current.getCause() != null) queue.add(current.getCause());
            if (current instanceof MessagingException mail && mail.getNextException() != null) queue.add(mail.getNextException());
            if (current instanceof MailSendException mail) {
                for (var nested : mail.getMessageExceptions()) if (nested != null) queue.add(nested);
            }
        }
        return result;
    }

    public static String message(Throwable failure) {
        if (causes(failure).stream().anyMatch(cause -> cause instanceof MailAuthenticationException || cause instanceof AuthenticationFailedException))
            return "message.smtp.authentication_failed";
        if (failure instanceof MailException) return "message.smtp.send_failed";
        String message = failure.getMessage();
        return isMessageKey(message) ? message : "message.smtp.configuration_failed";
    }
    private static boolean isMessageKey(String value) { return value != null && value.matches("message\\.[a-zA-Z0-9_.]+"); }
}
