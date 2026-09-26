package com.tpverp.saas.supervision;

import static com.tpverp.saas.supervision.StoreRepairModels.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sync/repairs")
public class StoreRepairInstallationController {
    private final StoreRepairService service;
    public StoreRepairInstallationController(StoreRepairService service) { this.service = service; }
    @PostMapping("/claim")
    public List<ClaimedCommand> claim(@RequestHeader(value = "X-TPV-Installation-Token", required = false) String token,
            @Valid @RequestBody ClaimRequest request) {
        return service.claim(request.installationId(), token);
    }
    @PostMapping("/{commandId}/result")
    public RepairCommandView result(@PathVariable UUID commandId,
            @RequestHeader(value = "X-TPV-Installation-Token", required = false) String token,
            @Valid @RequestBody ResultRequest request) {
        return service.result(commandId, request, token);
    }
}
