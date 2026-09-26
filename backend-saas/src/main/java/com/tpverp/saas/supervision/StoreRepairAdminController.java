package com.tpverp.saas.supervision;

import static com.tpverp.saas.supervision.StoreRepairModels.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/supervision/failures/{failureKey}")
public class StoreRepairAdminController {
    private final StoreRepairService service;
    public StoreRepairAdminController(StoreRepairService service) { this.service = service; }
    @GetMapping("/repairs")
    public RepairState state(@PathVariable String failureKey) { return service.state(failureKey); }
    @PostMapping("/repairs")
    public RepairCommandView create(@PathVariable String failureKey, @Valid @RequestBody CreateRepairRequest request) {
        return service.create(failureKey, request);
    }
    @PostMapping("/manual")
    public ManualResponse manual(@PathVariable String failureKey, @Valid @RequestBody ManualRequest request) {
        return service.manual(failureKey, request);
    }
}
