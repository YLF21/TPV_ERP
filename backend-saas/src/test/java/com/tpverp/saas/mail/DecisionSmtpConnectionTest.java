package com.tpverp.saas.mail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DecisionSmtpConnectionTest {
    @Test void requiresServerAuthenticationAfterMicrosoftConnect() throws Exception {
        var props = SmtpTransport.microsoftProperties();
        assertThat(props).containsEntry("mail.smtp.auth.mechanisms","XOAUTH2")
                .containsEntry("mail.smtp.auth.login.disable","true").containsEntry("mail.smtp.auth.plain.disable","true")
                .containsEntry("mail.smtp.starttls.required","true").containsEntry("mail.smtp.ssl.checkserveridentity","true");
        var smtp = spy(new DecisionSmtpConnection(Session.getInstance(props)));
        doNothing().when(smtp).connect("smtp.office365.com",587,"test@example.test","test-token");
        doReturn(false).when(smtp).supportsExtension("AUTH");
        assertThatThrownBy(() -> smtp.connectMicrosoft("test@example.test","test-token"))
                .isInstanceOf(AuthenticationFailedException.class);
        doReturn(true).when(smtp).supportsExtension("AUTH");
        smtp.connectMicrosoft("test@example.test","test-token");
        verify(smtp,times(2)).connect("smtp.office365.com",587,"test@example.test","test-token");
    }

    @Test void lostFinalReplyRequiresManualRetryButExplicitRejectionRemainsRetryable() throws Exception {
        exerciseFinalReply(null);
        exerciseFinalReply("451 Try later");
    }

    /** An in-process loopback relay discards the synthetic message and controls the response to the DATA terminator. */
    private void exerciseFinalReply(String finalReply) throws Exception {
        var props = new Properties();
        props.setProperty("mail.host","localhost");
        props.setProperty("mail.smtp.localhost","localhost");
        props.setProperty("mail.smtp.timeout","10000");
        var session = Session.getInstance(props);
        var mail = new MimeMessage(session);
        mail.setFrom("sender@example.test");
        mail.setRecipients(jakarta.mail.Message.RecipientType.TO,"receiver@example.test");
        mail.setText("Synthetic fixture");
        mail.saveChanges();
        try (var server = new ServerSocket(0,1,InetAddress.getLoopbackAddress());
             var relay = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(20000);
            var result = relay.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(20000);
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    var output = new PrintWriter(socket.getOutputStream(),true,StandardCharsets.US_ASCII);
                    output.println("220 loopback");
                    boolean data = false;
                    for (String line; (line = input.readLine()) != null; ) {
                        if (data && line.equals(".")) {
                            if (finalReply == null) return true;
                            data = false;
                            output.println(finalReply);
                        } else if (!data) {
                            if (line.equals("DATA")) {
                                data = true;
                                output.println("354 Send message");
                            } else if (line.equals("QUIT")) {
                                output.println("221 Closing");
                                return true;
                            } else output.println("250 OK");
                        }
                    }
                    return true;
                }
            });
            var transport = new DecisionSmtpConnection(session);
            try {
                transport.connect(server.getInetAddress().getHostAddress(),server.getLocalPort(),null,null);
                if (finalReply == null) {
                    assertThatThrownBy(() -> transport.deliver(mail)).isInstanceOf(SmtpTransport.Failure.class)
                            .hasMessage("SMTP_DELIVERY_UNCERTAIN");
                } else assertThatThrownBy(() -> transport.deliver(mail)).isInstanceOf(MessagingException.class);
            } finally { transport.closeQuietly(); }
            assertThat(result.get(6,TimeUnit.SECONDS)).isTrue();
        }
    }
}
