package com.tpverp.backend.communications;

import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.groups.Default;
import org.springframework.web.bind.annotation.*;
import static com.tpverp.backend.communications.CommunicationModels.*;

@RestController
@RequestMapping("/api/v1/communications")
@RequireGestionGroup(GestionGroup.CONFIGURACION)
@PreAuthorize("hasRole('ADMIN')")
public class CommunicationController {
    private final CommunicationService service;
    public CommunicationController(CommunicationService service) { this.service=service; }
    @GetMapping("/status") public StatusView status() { return service.status(); }
    @GetMapping("/templates") public List<TemplateView> templates() { return service.templates(); }
    @GetMapping("/welcome-template") public WelcomeTemplateSelection welcomeTemplate() { return service.welcomeTemplate(); }
    @PutMapping("/welcome-template") public WelcomeTemplateSelection selectWelcomeTemplate(@RequestBody WelcomeTemplateSelection selection) { return service.selectWelcomeTemplate(selection); }
    @PostMapping("/templates") public TemplateView create(@Valid @RequestBody TemplateInput input) { return service.createTemplate(input); }
    @PutMapping("/templates/{id}") public TemplateView update(@PathVariable UUID id,@Validated({Default.class,TemplateUpdate.class}) @RequestBody TemplateInput input) { return service.updateTemplate(id,input); }
    @PostMapping("/templates/preview") public Rendered preview(@Valid @RequestBody TemplateInput input) { return service.previewTemplate(input); }
    @PostMapping("/templates/test") public ResponseEntity<Void> test(@Valid @RequestBody TestInput input) { service.testTemplate(input.template(),input.toEmail()); return ResponseEntity.noContent().build(); }
    @GetMapping("/campaigns") public List<CampaignView> campaigns() { return service.campaigns(); }
    @PostMapping("/campaigns") public CampaignView createCampaign(@Valid @RequestBody CampaignInput input) { return service.createCampaign(input); }
    @PostMapping("/campaigns/{id}/preview") public AudiencePreview previewCampaign(@PathVariable UUID id) { return service.previewCampaign(id); }
    @PostMapping("/campaigns/{id}/schedule") public CampaignView schedule(@PathVariable UUID id,@Valid @RequestBody ScheduleInput input) { return service.schedule(id,input); }
    @PostMapping("/campaigns/{id}/cancel") public CampaignView cancel(@PathVariable UUID id) { return service.cancel(id); }
    @GetMapping("/history") public List<DeliveryView> history() { return service.history(); }
    @PostMapping("/history/{id}/retry") public DeliveryView retry(@PathVariable UUID id) { return service.retry(id); }
    public record TestInput(@NotNull @Valid TemplateInput template,@NotBlank @Email String toEmail) { }
}
