package com.tpverp.backend.communications;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Public capability endpoint: reading a link never changes a customer's preferences. */
@RestController
@RequestMapping("/api/public/communications/unsubscribe")
public class CommunicationUnsubscribeController {
    private final CommunicationPublicLinks links;
    public CommunicationUnsubscribeController(CommunicationPublicLinks links) { this.links = links; }

    @GetMapping(produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirm(@RequestParam String token) {
        links.verify(token);
        return page("<h1>Dejar de recibir publicidad / Unsubscribe / 退订</h1>"
            + "<p>Tu ficha de cliente y tu carnet se conservarán.<br>Your customer account and membership remain active.<br>您的客户资料和会员卡将保留。</p>"
            + "<form method=\"post\" action=\"unsubscribe\"><input type=\"hidden\" name=\"token\" value=\"" + CommunicationTemplateRenderer.escape(token)
            + "\"><button type=\"submit\">Confirmar baja / Unsubscribe / 确认退订</button></form>");
    }

    @PostMapping(produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribe(@RequestParam String token) {
        links.unsubscribe(token);
        return page("<h1>Baja confirmada / Unsubscribed / 已退订</h1><p>No recibirás nuevas campañas en esta dirección.<br>You will no longer receive campaigns at this address.<br>此邮箱将不再接收营销邮件。</p>");
    }

    private ResponseEntity<String> page(String body) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
            .header("Cache-Control","no-store").header("Referrer-Policy","no-referrer")
            .header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
            .header("X-Content-Type-Options","nosniff")
            .body("<!doctype html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>Preferencias de correo</title></head>"
                + "<body style=\"font-family:Arial,sans-serif;max-width:640px;margin:48px auto;padding:24px;line-height:1.6\">"+body+"</body></html>");
    }
}
