package com.tpverp.backend.communications;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.tpverp.backend.communications.CommunicationModels.*;

/** Renders a company-owned welcome template for an immutable queued message snapshot. */
@Service
public class CommunicationWelcomeTemplateService {
    private final JdbcTemplate jdbc;
    private final CommunicationTemplateRenderer renderer;
    public CommunicationWelcomeTemplateService(JdbcTemplate jdbc,CommunicationTemplateRenderer renderer) {
        this.jdbc=jdbc;this.renderer=renderer;
    }
    public Optional<RenderedWelcome> renderWelcome(UUID companyId,String name,String memberId,String storeName) {
        var templates=jdbc.query("""
            select t.* from communication_welcome_template w
            join communication_template t on t.id=w.template_id and t.empresa_id=w.empresa_id
            where w.empresa_id=?
            """,(rs,n)->new TemplateInput(rs.getString("name"),rs.getString("subject"),rs.getString("heading"),rs.getString("body"),rs.getString("button_text"),rs.getString("button_url"),rs.getString("logo_url"),rs.getString("accent_color"),rs.getLong("version")),companyId);
        return templates.stream().findFirst().map(template->{
            var variables=Map.of("name",name==null?"":name,"memberId",memberId==null?"":memberId,"storeName",storeName==null?"":storeName);
            var rendered=renderer.render(template,variables,null);
            return new RenderedWelcome(renderer.subject(template,variables),rendered.text(),rendered.html());
        });
    }
    public record RenderedWelcome(String subject,String text,String html) { }
}
