package com.tpverp.backend.communications;

import java.net.URI;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import static com.tpverp.backend.communications.CommunicationModels.*;

/** Only structured, escaped content is accepted; arbitrary HTML never enters the email document. */
@Component
public class CommunicationTemplateRenderer {
    public Rendered render(TemplateInput template, Map<String,String> variables, String unsubscribeUrl) {
        validate(template);
        String title = replace(template.heading(),variables), body = replace(template.body(),variables);
        String button = template.buttonText() == null ? "" : replace(template.buttonText(),variables);
        String link = template.buttonUrl() == null ? "" : template.buttonUrl().trim();
        String html = "<!doctype html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width\"></head>"
            + "<body style=\"margin:0;background:#f3f6fa;font-family:Arial,sans-serif;color:#18324d\">"
            + "<main style=\"max-width:600px;margin:24px auto;background:white;padding:28px;border-top:6px solid " + template.accentColor() + "\">"
            + (template.logoUrl()==null||template.logoUrl().isBlank()?"":"<img src=\""+escape(template.logoUrl().trim())+"\" alt=\"Logo\" referrerpolicy=\"no-referrer\" style=\"max-width:180px;max-height:100px\">")
            + "<h1 style=\"font-size:24px\">" + escape(title) + "</h1><div style=\"font-size:16px;line-height:1.6\">"
            + escape(body).replace("\n","<br>") + "</div>";
        String text = title + "\n\n" + body;
        if (!link.isBlank() && !button.isBlank()) {
            html += "<p><a style=\"display:inline-block;padding:12px 20px;color:white;background:" + template.accentColor()
                + "\" href=\"" + escape(link) + "\">" + escape(button) + "</a></p>";
            text += "\n\n" + button + ": " + link;
        }
        if (unsubscribeUrl != null) {
            html += "<hr><p style=\"font-size:12px\">Comunicación comercial / Marketing email / 商业邮件<br>"
                + "<a href=\"" + escape(unsubscribeUrl) + "\">Dar de baja / Unsubscribe / 退订</a></p>";
            text += "\n\nDar de baja / Unsubscribe / 退订: " + unsubscribeUrl;
        }
        return new Rendered(html + "</main></body></html>",text);
    }

    public void validate(TemplateInput input) {
        if (input.subject().contains("\r") || input.subject().contains("\n")) throw new IllegalArgumentException("El asunto debe ocupar una línea");
        if (input.accentColor() == null || !input.accentColor().matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Color no válido");
        for (String field : new String[]{input.subject(), input.heading(), input.body(), input.buttonText()}) {
            if (field == null) continue;
            var tokens = java.util.regex.Pattern.compile("\\{([^{}]+)\\}").matcher(field);
            while (tokens.find()) {
                if (!tokens.group(1).equals("name") && !tokens.group(1).equals("storeName") && !tokens.group(1).equals("memberId"))
                    throw new IllegalArgumentException("Campos disponibles: {name}, {storeName} y {memberId}");
            }
        }
        validateUrl(input.buttonUrl());
        validateUrl(input.logoUrl());
    }
    private void validateUrl(String value) {
        if (value != null && !value.isBlank()) {
            URI uri;
            try { uri = URI.create(value.trim()); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Enlace no válido"); }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("El botón debe usar un enlace HTTPS");
        }
    }

    public String subject(TemplateInput template, Map<String,String> variables) {
        String result = replace(template.subject(),variables).replace('\r',' ').replace('\n',' ');
        if (result.length() > 250) result = result.substring(0,250);
        return result;
    }
    private String replace(String value, Map<String,String> variables) {
        var matcher = java.util.regex.Pattern.compile("\\{(name|storeName|memberId)\\}").matcher(value);
        return matcher.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(variables.getOrDefault(match.group(1),"")));
    }
    static String escape(String value) { return HtmlUtils.htmlEscape(value,"UTF-8"); }
}
