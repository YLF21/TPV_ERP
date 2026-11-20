package com.tpverp.backend.communications;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.MemberSmtpSettingsRepository;
import com.tpverp.backend.party.CompanyMailService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.tpverp.backend.communications.CommunicationModels.*;

@Service
public class CommunicationService {
    private final JdbcTemplate jdbc;
    private final CurrentOrganization organization;
    private final CommunicationTemplateRenderer renderer;
    private final CommunicationPublicLinks links;
    private final MemberSmtpSettingsRepository smtp;
    private final CompanyMailService mail;
    private final Clock clock;
    private final boolean workerEnabled;

    public CommunicationService(JdbcTemplate jdbc, CurrentOrganization organization, CommunicationTemplateRenderer renderer,
            CommunicationPublicLinks links, MemberSmtpSettingsRepository smtp, CompanyMailService mail, Clock clock,
            @Value("${tpv.communications.delivery-enabled:false}") boolean workerEnabled) {
        this.jdbc=jdbc; this.organization=organization; this.renderer=renderer; this.links=links;
        this.smtp=smtp; this.mail=mail; this.clock=clock; this.workerEnabled=workerEnabled;
    }

    public StatusView status() {
        boolean ready = smtp.findById(companyId()).filter(value -> value.isEnabled()).isPresent();
        return new StatusView(ready && links.configured() && workerEnabled,links.configured(),workerEnabled);
    }
    public List<TemplateView> templates() {
        return jdbc.query("select * from communication_template where empresa_id=? order by name",CommunicationService::template,companyId());
    }
    public WelcomeTemplateSelection welcomeTemplate() {
        return new WelcomeTemplateSelection(jdbc.query("select template_id from communication_welcome_template where empresa_id=?",(rs,n)->rs.getObject(1,UUID.class),companyId()).stream().findFirst().orElse(null));
    }
    @Transactional
    public WelcomeTemplateSelection selectWelcomeTemplate(WelcomeTemplateSelection selection) {
        UUID company=companyId();
        if(selection.templateId()==null) jdbc.update("delete from communication_welcome_template where empresa_id=?",company);
        else {
            template(selection.templateId(),company);
            jdbc.update("insert into communication_welcome_template(empresa_id,template_id) values(?,?) on conflict(empresa_id) do update set template_id=excluded.template_id",company,selection.templateId());
        }
        return welcomeTemplate();
    }
    @Transactional
    public TemplateView createTemplate(TemplateInput input) {
        renderer.validate(input);
        UUID id=UUID.randomUUID(), company=companyId();
        jdbc.update("insert into communication_template(id,empresa_id,name,subject,heading,body,button_text,button_url,logo_url,accent_color) values(?,?,?,?,?,?,?,?,?,?)",
            id,company,input.name().trim(),input.subject(),input.heading(),input.body(),input.buttonText(),input.buttonUrl(),input.logoUrl(),input.accentColor());
        return template(id,company);
    }
    @Transactional
    public TemplateView updateTemplate(UUID id, TemplateInput input) {
        renderer.validate(input);
        if(input.version()==null || input.version()<0) throw new IllegalArgumentException("La versión de la plantilla es obligatoria para actualizarla");
        UUID company=companyId();
        if (jdbc.update("update communication_template set name=?,subject=?,heading=?,body=?,button_text=?,button_url=?,logo_url=?,accent_color=?,version=version+1 where id=? and empresa_id=? and version=?",
                input.name().trim(),input.subject(),input.heading(),input.body(),input.buttonText(),input.buttonUrl(),input.logoUrl(),input.accentColor(),id,company,input.version()) != 1)
            throw conflict("La plantilla cambió. Recarga antes de guardar");
        return template(id,company);
    }
    public Rendered previewTemplate(TemplateInput input) { return renderer.render(input,sampleVariables(),null); }
    public void testTemplate(TemplateInput input, String toEmail) {
        renderer.validate(input);
        if (!validEmail(toEmail)) throw new IllegalArgumentException("Correo de prueba no válido");
        var variables=sampleVariables(); var rendered=renderer.render(input,variables,null);
        mail.send(companyId(),toEmail,renderer.subject(input,variables),rendered.text(),rendered.html());
    }
    public List<CampaignView> campaigns() {
        return jdbc.query("select * from communication_campaign where empresa_id=? order by created_at desc limit 200",CommunicationService::campaign,companyId());
    }
    @Transactional
    public CampaignView createCampaign(CampaignInput input) {
        UUID company=companyId(), id=UUID.randomUUID();
        template(input.templateId(),company);
        var selection=input.customerIds()==null?List.<UUID>of():input.customerIds().stream().distinct().toList();
        if (selection.size()>10000) throw new IllegalArgumentException("Selecciona como máximo 10000 clientes");
        // Verify every selected ID belongs to this company; never silently include foreign IDs.
        if (!selection.isEmpty()) {
            var arguments=new ArrayList<Object>();arguments.add(company);arguments.addAll(selection);
            var owned=new HashSet<>(jdbc.query("select id from cliente where empresa_id=? and id in ("+placeholders(selection.size())+")",(rs,n)->rs.getObject(1,UUID.class),arguments.toArray()));
            if (!owned.containsAll(selection)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Cliente no encontrado");
        }
        jdbc.update("insert into communication_campaign(id,empresa_id,name,template_id,selected_customer_ids,created_at) values(?,?,?,?,?,?)",
            id,company,input.name().trim(),input.templateId(),joinIds(selection),Timestamp.from(clock.instant()));
        return campaign(id,company,false);
    }
    public AudiencePreview previewCampaign(UUID id) {
        UUID company=companyId(); var campaign=campaign(id,company,false);
        var snapshot=snapshot(campaign,company);
        var rendered=renderer.render(snapshot.template(),Map.of("name","Cliente de prueba","storeName",snapshot.storeName(),"memberId","001234"),null);
        return snapshot.audience().preview(rendered,snapshot.fingerprint());
    }
    @Transactional
    public CampaignView schedule(UUID id, ScheduleInput input) {
        UUID company=companyId(); var campaign=campaign(id,company,true);
        if (!campaign.status().equals("DRAFT")) throw conflict("Solo se puede programar un borrador");
        if (!status().campaignsEnabled()) throw conflict("Configura SMTP, el servicio de bajas público y el envío de campañas antes de continuar");
        Instant scheduled=input.scheduledAt()==null?clock.instant():input.scheduledAt();
        if (scheduled.isBefore(clock.instant().minusSeconds(60)) || scheduled.isAfter(clock.instant().plusSeconds(366L*86400)))
            throw new IllegalArgumentException("Selecciona una fecha entre ahora y los próximos 12 meses");
        var snapshot=snapshot(campaign,company);
        var audience=snapshot.audience();
        if (audience.recipients().isEmpty()) throw conflict("No hay destinatarios autorizados para correo");
        if (input.expectedRecipients()!=audience.recipients().size()) throw conflict("Los destinatarios han cambiado. Revisa la vista previa de nuevo");
        if(!snapshot.fingerprint().equals(input.previewToken()))
            throw conflict("El contenido o los destinatarios han cambiado. Revisa la vista previa de nuevo");
        var template=snapshot.template();
        var now=Timestamp.from(clock.instant());
        for (Recipient recipient:audience.recipients()) {
            var variables=Map.of("name",recipient.name(),"storeName",snapshot.storeName(),"memberId",snapshot.memberCodes().getOrDefault(recipient.id(),""));
            String unsubscribe=links.create(company,recipient.id(),recipient.email());
            var rendered=renderer.render(template,variables,unsubscribe);
            jdbc.update("insert into communication_delivery(id,empresa_id,campaign_id,customer_id,email,subject,text_body,html_body,unsubscribe_url,created_at) values(?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),company,id,recipient.id(),recipient.email(),renderer.subject(template,variables),rendered.text(),rendered.html(),unsubscribe,now);
        }
        jdbc.update("update communication_campaign set status='SCHEDULED',scheduled_at=?,recipient_count=?,version=version+1 where id=? and empresa_id=?",
            Timestamp.from(scheduled),audience.recipients().size(),id,company);
        return campaign(id,company,false);
    }
    @Transactional
    public CampaignView cancel(UUID id) {
        UUID company=companyId(); var campaign=campaign(id,company,true);
        if (campaign.status().equals("COMPLETED")) throw conflict("Esta campaña ya finalizó");
        if (campaign.status().equals("CANCELLED")) return campaign;
        jdbc.update("update communication_campaign set status='CANCELLED',version=version+1 where id=? and empresa_id=?",id,company);
        jdbc.update("update communication_delivery set status='CANCELLED' where campaign_id=? and empresa_id=? and status='PENDING'",id,company);
        return campaign(id,company,false);
    }
    public List<DeliveryView> history() {
        return jdbc.query("select d.*,c.name campaign_name from communication_delivery d join communication_campaign c on c.id=d.campaign_id and c.empresa_id=d.empresa_id where d.empresa_id=? order by d.created_at desc,d.id limit 200",
            (rs,n)->new DeliveryView(rs.getObject("id",UUID.class),rs.getObject("campaign_id",UUID.class),rs.getString("campaign_name"),rs.getString("email"),rs.getString("status"),instant(rs,"created_at"),instant(rs,"sent_at"),rs.getString("error_message")),companyId());
    }
    @Transactional
    public DeliveryView retry(UUID deliveryId) {
        UUID company=companyId();
        UUID campaignId=jdbc.query("select campaign_id from communication_delivery where id=? and empresa_id=?",(rs,n)->rs.getObject(1,UUID.class),deliveryId,company)
            .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Envío no encontrado"));
        var campaign=campaign(campaignId,company,true);
        var row=jdbc.queryForMap("select status,unsubscribe_url from communication_delivery where id=? and empresa_id=? for update",deliveryId,company);
        if(campaign.status().equals("CANCELLED")) throw conflict("La campaña está cancelada");
        String deliveryStatus=(String)row.get("status");
        if(!deliveryStatus.equals("PENDING")) {
            if(!deliveryStatus.equals("FAILED")) throw conflict("Solo se puede reintentar un error confirmado. Los resultados inciertos requieren revisión del proveedor");
            if(!status().campaignsEnabled() || !links.isCurrentLink((String)row.get("unsubscribe_url")))
                throw conflict("Revisa la conexión y el enlace público de bajas antes de reintentar");
            jdbc.update("update communication_delivery set status='PENDING',attempts=0,next_attempt_at=?,started_at=null,sent_at=null,error_message=null where id=? and empresa_id=?",
                Timestamp.from(clock.instant()),deliveryId,company);
            jdbc.update("update communication_campaign set status='SCHEDULED',scheduled_at=?,version=version+1 where id=? and empresa_id=?",
                Timestamp.from(clock.instant()),campaignId,company);
        }
        return jdbc.query("select d.*,c.name campaign_name from communication_delivery d join communication_campaign c on c.id=d.campaign_id and c.empresa_id=d.empresa_id where d.id=? and d.empresa_id=?",
            (rs,n)->new DeliveryView(rs.getObject("id",UUID.class),rs.getObject("campaign_id",UUID.class),rs.getString("campaign_name"),rs.getString("email"),rs.getString("status"),instant(rs,"created_at"),instant(rs,"sent_at"),rs.getString("error_message")),deliveryId,company).getFirst();
    }

    Audience audience(UUID campaignId, UUID company) {
        String selection=jdbc.queryForObject("select selected_customer_ids from communication_campaign where id=? and empresa_id=?",String.class,campaignId,company);
        Set<UUID> selected=selection==null||selection.isBlank()?Set.of():new HashSet<>(Arrays.stream(selection.split(",")).map(UUID::fromString).toList());
        var arguments=new ArrayList<Object>();arguments.add(company);arguments.addAll(selected);
        String selectedClause=selected.isEmpty()?"":" and c.id in ("+placeholders(selected.size())+")";
        var customers=jdbc.query("""
            select c.id,c.nombre_fiscal,c.email,c.activo,c.commercial_consent,
              (ch.active and upper(ch.code)='EMAIL') email_channel,
              exists(select 1 from communication_suppression s where s.empresa_id=c.empresa_id and s.email=lower(trim(c.email))) suppressed
            from cliente c left join commercial_contact_channel ch on ch.id=c.preferred_commercial_channel_id and ch.empresa_id=c.empresa_id
            where c.empresa_id=?
            """+selectedClause+" order by c.id limit 10001",(rs,n)->new Candidate(rs.getObject("id",UUID.class),rs.getString("nombre_fiscal"),rs.getString("email"),rs.getBoolean("activo"),rs.getBoolean("commercial_consent"),rs.getBoolean("email_channel"),rs.getBoolean("suppressed")),arguments.toArray());
        return classify(customers);
    }
    private CampaignSnapshot snapshot(CampaignView campaign,UUID company) {
        var audience=audience(campaign.id(),company);
        var template=template(campaign.templateId(),company).input();
        String storeName=organization.currentCompany().getRazonSocial();
        Map<UUID,String> memberCodes=new HashMap<>();
        if(!audience.recipients().isEmpty()) {
            var arguments=new ArrayList<Object>();arguments.add(company);
            arguments.addAll(audience.recipients().stream().map(Recipient::id).toList());
            jdbc.query("select cliente_id,member_id from miembro where empresa_id=? and cliente_id in ("+placeholders(audience.recipients().size())+")",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->memberCodes.put(rs.getObject(1,UUID.class),rs.getString(2)),arguments.toArray());
        }
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            for(String value:new String[]{company.toString(),campaign.id().toString(),campaign.templateId().toString(),
                    template.subject(),template.heading(),template.body(),template.buttonText(),template.buttonUrl(),template.logoUrl(),template.accentColor(),storeName})
                fingerprintValue(digest,value);
            for(var recipient:audience.recipients()) {
                fingerprintValue(digest,recipient.id().toString());fingerprintValue(digest,recipient.email());fingerprintValue(digest,recipient.name());
                fingerprintValue(digest,memberCodes.getOrDefault(recipient.id(),""));
            }
            return new CampaignSnapshot(audience,template,storeName,Map.copyOf(memberCodes),HexFormat.of().formatHex(digest.digest()));
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void fingerprintValue(java.security.MessageDigest digest,String value) {
        byte[] bytes=value==null?new byte[0]:value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(value==null?-1:bytes.length).array());
        digest.update(bytes);
    }
    static Audience classify(List<Candidate> candidates) {
        if (candidates.size()>10000) throw new IllegalArgumentException("Selecciona un grupo de hasta 10000 clientes para esta campaña");
        int consent=0,email=0,channel=0,suppressed=0,inactive=0,duplicate=0;
        var recipients=new ArrayList<Recipient>(); var seen=new HashSet<String>();
        for(var c:candidates) {
            if(!c.active()) { inactive++; continue; }
            if(!validEmail(c.email())) { email++; continue; }
            if(!c.consent()) { consent++; continue; }
            if(!c.emailChannel()) { channel++; continue; }
            if(c.suppressed()) { suppressed++; continue; }
            String normalized=c.email().trim().toLowerCase(Locale.ROOT);
            if(!seen.add(normalized)) { duplicate++; continue; }
            recipients.add(new Recipient(c.id(),c.name(),normalized));
        }
        return new Audience(List.copyOf(recipients),candidates.size(),consent,email,channel,suppressed,inactive,duplicate);
    }
    static boolean validEmail(String email) {
        if(email==null||email.isBlank()||email.length()>320||email.contains("\r")||email.contains("\n")) return false;
        try { var address=new jakarta.mail.internet.InternetAddress(email.trim(),true); address.validate(); return address.getAddress().equals(email.trim()) && email.contains("@"); }
        catch(jakarta.mail.internet.AddressException ex) { return false; }
    }
    private Map<String,String> sampleVariables() { return Map.of("name","Cliente de prueba","storeName",organization.currentCompany().getRazonSocial(),"memberId","001234"); }
    private UUID companyId() { return organization.currentCompany().getId(); }
    private TemplateView template(UUID id,UUID company) {
        return jdbc.query("select * from communication_template where id=? and empresa_id=?",CommunicationService::template,id,company).stream().findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Plantilla no encontrada"));
    }
    private CampaignView campaign(UUID id,UUID company,boolean lock) {
        return jdbc.query("select * from communication_campaign where id=? and empresa_id=?"+(lock?" for update":""),CommunicationService::campaign,id,company).stream().findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Campaña no encontrada"));
    }
    private static TemplateView template(ResultSet rs,int n)throws SQLException {
        return new TemplateView(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("subject"),rs.getString("heading"),rs.getString("body"),rs.getString("button_text"),rs.getString("button_url"),rs.getString("logo_url"),rs.getString("accent_color"),rs.getLong("version"));
    }
    private static CampaignView campaign(ResultSet rs,int n)throws SQLException {
        return new CampaignView(rs.getObject("id",UUID.class),rs.getString("name"),rs.getObject("template_id",UUID.class),rs.getString("status"),instant(rs,"scheduled_at"),instant(rs,"created_at"),rs.getLong("version"),rs.getInt("recipient_count"));
    }
    private static Instant instant(ResultSet rs,String field)throws SQLException { Timestamp value=rs.getTimestamp(field); return value==null?null:value.toInstant(); }
    private static String joinIds(List<UUID> ids) { return String.join(",",ids.stream().map(UUID::toString).toList()); }
    private static String placeholders(int size) { return String.join(",",Collections.nCopies(size,"?")); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    record Candidate(UUID id,String name,String email,boolean active,boolean consent,boolean emailChannel,boolean suppressed) { }
    record Recipient(UUID id,String name,String email) { }
    private record CampaignSnapshot(Audience audience,TemplateInput template,String storeName,Map<UUID,String> memberCodes,String fingerprint) { }
    record Audience(List<Recipient> recipients,int total,int noConsent,int noEmail,int channel,int suppressed,int inactive,int duplicate) {
        AudiencePreview preview(Rendered rendered,String previewToken) { return new AudiencePreview(recipients.size(),total,noConsent,noEmail,channel,suppressed,inactive,duplicate,rendered.html(),rendered.text(),previewToken); }
    }
}
