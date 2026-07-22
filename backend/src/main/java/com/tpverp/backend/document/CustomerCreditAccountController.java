package com.tpverp.backend.document;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer-credit-accounts")
public class CustomerCreditAccountController {

    private static final String READ_PERMISSION =
            "hasRole('ADMIN') or hasAuthority('CUSTOMER_RECEIVABLES_READ')";

    private final CustomerCreditAccountService service;

    public CustomerCreditAccountController(CustomerCreditAccountService service) {
        this.service = service;
    }

    @GetMapping("/{customerId}")
    @PreAuthorize(READ_PERMISSION)
    public CustomerCreditAccountService.AccountView account(
            @PathVariable UUID customerId, Authentication authentication) {
        return service.account(customerId, authentication);
    }
}
