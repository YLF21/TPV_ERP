package com.tpverp.backend.party;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customers/{id}/addresses")
public class CustomerAddressBookController {
    private final CustomerAddressBookService books;
    private final CustomerService customers;
    public CustomerAddressBookController(CustomerAddressBookService books, CustomerService customers) {
        this.books = books; this.customers = customers;
    }
    @PostMapping("/refresh")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_READ','CUSTOMERS_WRITE','GESTION_CLIENTE_PROVEEDOR')")
    public CustomerService.CustomerView refresh(@PathVariable UUID id) {
        books.refresh(id, false, null); return customers.get(id);
    }
    @PostMapping("/use-central")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_WRITE','GESTION_CLIENTE_PROVEEDOR')")
    public CustomerService.CustomerView useCentral(@PathVariable UUID id, @Valid @RequestBody UseCentral request) {
        books.refresh(id, true, request.expectedVersion()); return customers.get(id);
    }
    public record UseCentral(@NotNull @PositiveOrZero Long expectedVersion) { }
}
