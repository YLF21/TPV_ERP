package com.tpverp.saas.customer;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/v1/customer-identities")
public class CustomerIdentityController {
    private final CustomerIdentityService service;

    public CustomerIdentityController(CustomerIdentityService service) { this.service = service; }

    @PostMapping("/reservations")
    public CustomerIdentityReservationResponse reserve(
            @RequestHeader(value = "X-TPV-Installation-Token", required = false) String token,
            @Valid @RequestBody CustomerIdentityReservationRequest request) {
        return service.reserve(request, token);
    }

    @PostMapping("/reservations/{operationId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(
            @PathVariable UUID operationId,
            @RequestHeader(value = "X-TPV-Installation-Token", required = false) String token,
            @Valid @RequestBody CustomerIdentityOwnerRequest request) {
        service.cancel(operationId, request, token);
    }
}
