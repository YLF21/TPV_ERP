package com.tpverp.backend.party;

import java.util.Properties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
public class SmtpMailSenderFactory {

    public JavaMailSender create(MemberSmtpSettings settings) {
        var sender = new JavaMailSenderImpl();
        sender.setHost(settings.getHost());
        sender.setPort(settings.getPort());
        sender.setUsername(settings.getUsername());
        sender.setPassword(settings.getPassword());
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", String.valueOf(settings.getUsername() != null));
        properties.put("mail.smtp.starttls.enable", String.valueOf(settings.isStartTls()));
        properties.put("mail.smtp.ssl.enable", String.valueOf(settings.isSslEnabled()));
        return sender;
    }
}
