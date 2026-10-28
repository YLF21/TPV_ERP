package com.tpverp.saas.customer;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customer-addresses")
public class CustomerAddressBookController {
    private final CustomerAddressBookService service;
    public CustomerAddressBookController(CustomerAddressBookService service) { this.service = service; }

    @PostMapping("/snapshot")
    public CustomerAddressBookApi.Snapshot snapshot(
            @RequestHeader(value = "X-TPV-Installation-Token", required = false) String token,
            @Valid @RequestBody CustomerAddressBookApi.Request request) {
        return service.snapshot(request, token);
    }
}
