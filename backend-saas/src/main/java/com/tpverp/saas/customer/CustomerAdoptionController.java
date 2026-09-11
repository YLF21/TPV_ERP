package com.tpverp.saas.customer;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customer-identities")
public class CustomerAdoptionController {
    private final CustomerAdoptionService service;

    public CustomerAdoptionController(CustomerAdoptionService service) { this.service = service; }

    @PostMapping("/lookup")
    public CustomerAdoptionApi.Profile lookup(
            @RequestHeader(value = "X-TPV-Installation-Token", required = false) String token,
            @Valid @RequestBody CustomerAdoptionApi.Lookup request) {
        return service.lookup(request, token);
    }

    @PostMapping("/adoptions")
    public CustomerAdoptionApi.Reservation reserve(
            @RequestHeader(value = "X-TPV-Installation-Token", required = false) String token,
            @Valid @RequestBody CustomerAdoptionApi.Reserve request) {
        return service.reserve(request, token);
    }

    @PostMapping("/adoptions/{operationId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID operationId,
            @RequestHeader(value = "X-TPV-Installation-Token", required = false) String token,
            @Valid @RequestBody CustomerIdentityOwnerRequest request) {
        service.cancel(operationId, request, token);
    }
}
