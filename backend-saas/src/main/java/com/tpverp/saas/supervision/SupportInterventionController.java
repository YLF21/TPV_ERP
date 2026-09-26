package com.tpverp.saas.supervision;

import static com.tpverp.saas.supervision.SupportInterventionModels.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tickets/{ticketId}/interventions")
public class SupportInterventionController {
    private final SupportInterventionService service;
    public SupportInterventionController(SupportInterventionService service) { this.service = service; }
    @GetMapping public State state(@PathVariable UUID ticketId) { return service.state(ticketId); }
    @PostMapping public State apply(@PathVariable UUID ticketId, @Valid @RequestBody Request request) {
        return service.apply(ticketId, request);
    }
}