package com.tpverp.backend.verifactu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("fiscal-dev")
@RequestMapping("/api/v1/dev/fiscal-sandbox")
@PreAuthorize("hasRole('ADMIN')")
public class FiscalSandboxController {

    private final FiscalRuntimeProperties runtime;
    private final SimulatedAeatTransport simulator;
    private final VerifactuSubmissionWorker worker;

    public FiscalSandboxController(
            FiscalRuntimeProperties runtime,
            SimulatedAeatTransport simulator,
            VerifactuSubmissionWorker worker) {
        this.runtime = runtime;
        this.simulator = simulator;
        this.worker = worker;
    }

    @GetMapping("/status")
    public FiscalSandboxStatusView status() {
        requireSandbox();
        return new FiscalSandboxStatusView(
                runtime.sandboxEnabled(), runtime.runtimeClass(),
                runtime.endpointEnvironment(), runtime.transportMode(),
                simulator.nextOutcome());
    }

    @PutMapping("/scenario")
    public FiscalSandboxStatusView scenario(@Valid @RequestBody ScenarioRequest request) {
        requireSandbox();
        simulator.setNextOutcome(request.outcome());
        return status();
    }

    @PostMapping("/dispatch-next")
    public VerifactuWorkerResult dispatchNext() {
        requireSandbox();
        return worker.processNext();
    }

    private void requireSandbox() {
        if (!runtime.isSandbox() || runtime.transportMode() != FiscalTransportMode.SIMULATED) {
            throw new IllegalStateException("El laboratorio fiscal no esta activo");
        }
    }

    public record ScenarioRequest(@NotNull SimulatedAeatOutcome outcome) {
    }
}
