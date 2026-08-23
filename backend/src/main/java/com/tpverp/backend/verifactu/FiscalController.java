package com.tpverp.backend.verifactu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fiscal")
public class FiscalController {
    private final FiscalModeTransitionService modes;
    private final FiscalModeTransitionRepository transitions;

    public FiscalController(FiscalModeTransitionService modes,
            FiscalModeTransitionRepository transitions) {
        this.modes = modes;
        this.transitions = transitions;
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('VERIFACTU_READ')")
    public FiscalStatusView status() { return modes.status(); }

    @PostMapping("/mode-transitions")
    @PreAuthorize("hasRole('ADMIN')")
    public FiscalStatusView transition(@Valid @RequestBody ModeTransitionRequest request) {
        return modes.transition(request.targetMode(), request.expectedVersion(),
                request.reason(), request.confirmation());
    }

    @GetMapping("/events")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('VERIFACTU_READ')")
    public List<FiscalModeTransition> events() {
        var company = modes.status().companyId();
        return transitions.findTop50ByCompanyIdOrderByEffectiveAtDesc(company);
    }

    public record ModeTransitionRequest(
            @NotNull FiscalMode targetMode,
            long expectedVersion,
            @NotBlank String reason,
            boolean confirmation) {}
}
