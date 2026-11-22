package com.tpverp.saas.mail;

import java.net.URI;
import java.util.Locale;

/** Renders the localized decision notice sent when a SaaS client request is reviewed. */
public final class ClientDecisionEmail {
    private static final String NAVY = "#0b2c4f";
    private static final String BLUE = "#1466d8";
    private static final String PAGE = "#eef2f7";

    private ClientDecisionEmail() { }

    public static Content render(boolean approved, String locale, String fullName, String username,
                                 String reason, String portalUrl, boolean hasStores) {
        Copy copy = Copy.forLocale(locale);
        String name = safe(fullName);
        String user = safe(username);
        String explanation = safe(reason);
        String safePortalUrl = validPortalUrl(portalUrl);

        String subject = approved ? copy.approvedSubject : copy.rejectedSubject;
        String heading = approved ? copy.approvedHeading : copy.rejectedHeading;
        StringBuilder text = new StringBuilder();
        StringBuilder body = new StringBuilder();

        if (!name.isBlank()) {
            text.append(copy.greeting).append(' ').append(name).append(".\n\n");
            body.append("<p style=\"margin:0 0 16px;color:#24364b;font-size:15px;line-height:1.5\">")
                    .append(escape(copy.greeting)).append(' ').append(escape(name)).append(".</p>");
        }
        text.append(heading).append("\n\n");
        body.append("<h1 style=\"margin:0 0 16px;color:").append(NAVY)
                .append(";font-size:21px;line-height:1.3;font-weight:700\">")
                .append(escape(heading)).append("</h1>");

        if (approved) {
            text.append(copy.usernameLabel).append(": ").append(user).append("\n")
                    .append(copy.passwordNote).append("\n\n");
            body.append(paragraph(copy.usernameLabel + ": <strong>" + escape(user) + "</strong>"))
                    .append(paragraph(escape(copy.passwordNote)));
            if (safePortalUrl != null) {
                text.append(copy.portalLabel).append(": ").append(safePortalUrl).append("\n\n");
                body.append("<p style=\"margin:0 0 18px\"><a href=\"").append(escape(safePortalUrl))
                        .append("\" style=\"display:inline-block;background:").append(BLUE)
                        .append(";color:#ffffff;text-decoration:none;font-weight:700;padding:11px 16px;border-radius:4px\">")
                        .append(escape(copy.portalLabel)).append("</a></p>");
            }
            if (!hasStores) {
                text.append(copy.noStores).append("\n\n");
                body.append(paragraph(escape(copy.noStores)));
            }
        } else {
            text.append(copy.reasonLabel).append(":\n").append(explanation).append("\n\n");
            body.append("<p style=\"margin:0 0 8px;color:#24364b;font-size:15px;line-height:1.5;font-weight:700\">")
                    .append(escape(copy.reasonLabel)).append("</p>")
                    .append("<div style=\"margin:0 0 18px;padding:12px 14px;background:#f7f9fc;border-left:4px solid ")
                    .append(BLUE).append(";color:#24364b;font-size:15px;line-height:1.5;white-space:pre-wrap\">")
                    .append(escape(explanation)).append("</div>");
        }

        text.append(copy.footer);
        body.append("<p style=\"margin:20px 0 0;padding-top:14px;border-top:1px solid #d9e1eb;color:#526174;font-size:12px;line-height:1.5\">")
                .append(escape(copy.footer)).append("</p>");

        String html = "<!doctype html><html lang=\"" + copy.htmlLocale + "\"><body style=\"margin:0;padding:24px 10px;background:"
                + PAGE + ";font-family:Arial,Helvetica,sans-serif;color:#24364b\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr><td align=\"center\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"max-width:600px;background:#ffffff;border:1px solid #d9e1eb;border-collapse:collapse\"><tr><td style=\"padding:18px 22px;background:"
                + NAVY + ";color:#ffffff\"><div style=\"font-size:19px;line-height:1.25;font-weight:700\">esPOS</div><div style=\"margin-top:3px;font-size:13px;line-height:1.4\">"
                + escape(copy.serverCentral) + "</div></td></tr><tr><td style=\"padding:24px 22px 20px\">" + body
                + "</td></tr></table></td></tr></table></body></html>";
        return new Content(subject, text.toString(), html);
    }

    private static String paragraph(String content) {
        return "<p style=\"margin:0 0 14px;color:#24364b;font-size:15px;line-height:1.5\">" + content + "</p>";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String validPortalUrl(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || uri.getHost().isBlank()
                    || !(scheme.toLowerCase(Locale.ROOT).equals("http")
                    || scheme.toLowerCase(Locale.ROOT).equals("https"))) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String escape(String value) {
        return safe(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public record Content(String subject, String text, String html) { }

    private record Copy(String htmlLocale, String serverCentral, String greeting,
                        String approvedSubject, String rejectedSubject, String approvedHeading,
                        String rejectedHeading, String usernameLabel, String passwordNote,
                        String portalLabel, String noStores, String reasonLabel, String footer) {
        static Copy forLocale(String locale) {
            String normalized = locale == null ? "" : locale.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("en")) return new Copy("en", "Central Server", "Hello",
                    "Your esPOS account has been approved", "Your esPOS account request was not approved",
                    "Your account has been approved", "Your account request was not approved",
                    "Username", "Sign in using the password you chose.", "Access the portal",
                    "Your account is active, but no stores are linked yet. SaaS staff can link them.",
                    "Reason", "This is an automatic esPOS notification.");
            if (normalized.startsWith("zh")) return new Copy("zh", "中央服务器", "您好",
                    "您的 esPOS 账户已获批准", "您的 esPOS 账户申请未获批准",
                    "您的账户已获批准", "您的账户申请未获批准", "用户名",
                    "请使用您自己设置的密码登录。", "访问门户",
                    "您的账户已启用，但尚未关联门店。SaaS 工作人员可以为您关联门店。",
                    "原因", "此邮件由 esPOS 自动发送。");
            return new Copy("es", "Servidor Central", "Hola",
                    "Tu cuenta de esPOS ha sido aprobada", "Tu solicitud de cuenta esPOS no ha sido aprobada",
                    "Tu cuenta ha sido aprobada", "Tu solicitud de cuenta no ha sido aprobada",
                    "Usuario", "Accede con la contraseña que elegiste.", "Acceder al portal",
                    "Tu cuenta está activa, pero todavía no tiene tiendas vinculadas. El personal de SaaS puede vincularlas.",
                    "Motivo", "Esta es una notificación automática de esPOS.");
        }
    }
}
