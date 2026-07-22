package com.tpverp.backend.document;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Single provider-neutral quote endpoint used before any payment flow is opened. */
@RestController
@RequestMapping("/api/v1/pos/sales")
public class AuthoritativeSaleQuoteController {

    private final PosCashService sales;

    public AuthoritativeSaleQuoteController(PosCashService sales) {
        this.sales = sales;
    }

    @PostMapping("/quote")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','TICKETS_CREATE')")
    public PosCashService.Quote quote(
            @Valid @RequestBody PosCashController.SaleRequest request,
            Authentication authentication) {
        return sales.quote(request, authentication);
    }
}
