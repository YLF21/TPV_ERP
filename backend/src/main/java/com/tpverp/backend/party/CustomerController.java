package com.tpverp.backend.party;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_READ')")
    public List<CustomerService.CustomerView> list() {
        return service.list();
    }

    @GetMapping("/sale-options")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('CUSTOMERS_READ','VENTA')")
    public List<SaleCustomerOption> saleOptions() {
        return service.list().stream()
                .map(SaleCustomerOption::from)
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_READ')")
    public CustomerService.CustomerView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
    public CustomerService.CustomerView create(@Valid @RequestBody CustomerRequest request) {
        return service.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
    public CustomerService.CustomerView update(
            @PathVariable UUID id,
            @Valid @RequestBody CustomerRequest request) {
        return service.update(id, request.command());
    }

    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/member/activate")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
    public CustomerService.CustomerView activateMember(@PathVariable UUID id) {
        return service.activateMember(id);
    }

    @PostMapping("/{id}/member/deactivate")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
    public CustomerService.CustomerView deactivateMember(@PathVariable UUID id) {
        return service.deactivateMember(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/validate-fiscal")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_READ')")
    public CustomerService.CustomerView validateFiscalData(@PathVariable UUID id) {
        return service.validateFiscalData(id);
    }

    @GetMapping("/{id}/balance-movements")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_READ')")
    public List<CustomerService.BalanceView> balanceMovements(@PathVariable UUID id) {
        return service.balanceMovements(id);
    }

    @PostMapping("/{id}/balance-movements")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('CUSTOMERS_WRITE')")
    public CustomerService.BalanceView moveBalance(
            @PathVariable UUID id,
            @Valid @RequestBody BalanceRequest request) {
        return service.moveBalance(id, request.amount(), request.reason());
    }

    public record CustomerRequest(
            @NotBlank String fiscalName,
            @NotNull DocumentType documentType,
            @NotBlank String documentNumber,
            FiscalAddress address,
            String phone,
            String email,
            String notes,
            @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal discount,
            boolean isMember,
            String numMember,
            LocalDate birthday,
            CustomerGender gender,
            boolean commercialConsent,
            UUID preferredCommercialChannelId) {

        CustomerService.CustomerCommand command() {
            return new CustomerService.CustomerCommand(
                    fiscalName, documentType, documentNumber, address,
                    phone, email, notes, discount, isMember, numMember,
                    birthday, gender, commercialConsent, preferredCommercialChannelId);
        }
    }

    public record BalanceRequest(
            @NotNull BigDecimal amount,
            @NotBlank String reason) {
    }

    public record SaleCustomerOption(
            UUID id,
            String clientId,
            String fiscalName,
            String documentNumber,
            boolean activeMember,
            String memberCategoryName,
            BigDecimal memberDiscountPercent) {

        static SaleCustomerOption from(CustomerService.CustomerView customer) {
            return new SaleCustomerOption(
                    customer.id(), customer.clientId(), customer.fiscalName(), customer.documentNumber(),
                    customer.isMember(), customer.memberCategoryName(), customer.memberDiscountPercent());
        }
    }
}
