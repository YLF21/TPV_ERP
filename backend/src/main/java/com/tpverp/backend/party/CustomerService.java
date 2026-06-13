package com.tpverp.backend.party;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final MemberBalanceMovementRepository movements;
    private final PartyContext context;
    private final Clock clock;

    public CustomerService(
            CustomerRepository customers,
            MemberBalanceMovementRepository movements,
            PartyContext context,
            Clock clock) {
        this.customers = customers;
        this.movements = movements;
        this.context = context;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CustomerView> list() {
        return customers.findByCompanyIdOrderByFiscalName(context.currentCompany().getId())
                .stream().map(CustomerView::from).toList();
    }

    @Transactional(readOnly = true)
    public CustomerView get(UUID id) {
        return CustomerView.from(customer(id));
    }

    @Transactional
    public CustomerView create(CustomerCommand command) {
        var company = context.currentCompany();
        ensureUnique(company.getId(), command.documentType(), command.documentNumber(), null);
        var customer = new Customer(
                company, command.fiscalName(), command.documentType(), command.documentNumber(),
                command.address(), command.phone(), command.email(), command.notes(),
                command.rate(), command.discount());
        return CustomerView.from(customers.save(customer));
    }

    @Transactional
    public CustomerView update(UUID id, CustomerCommand command) {
        Customer customer = customer(id);
        ensureUnique(context.currentCompany().getId(), command.documentType(),
                command.documentNumber(), id);
        customer.update(
                command.fiscalName(), command.documentType(), command.documentNumber(),
                command.address(), command.phone(), command.email(), command.notes(),
                command.rate(), command.discount());
        return CustomerView.from(customer);
    }

    @Transactional
    public void deactivate(UUID id) {
        customer(id).deactivate();
    }

    @Transactional
    public void delete(UUID id) {
        Customer customer = customer(id);
        if (movements.existsByCustomerId(id) || customers.hasDocumentHistory(id)) {
            throw new IllegalStateException("El cliente tiene historial y no se puede eliminar");
        }
        customers.delete(customer);
    }

    @Transactional(readOnly = true)
    public CustomerView validateFiscalData(UUID id) {
        Customer customer = customer(id);
        if (!customer.hasCompleteFiscalData()) {
            throw new IllegalStateException("El cliente no tiene datos fiscales completos");
        }
        return CustomerView.from(customer);
    }

    @Transactional
    public BalanceView moveBalance(UUID id, BigDecimal amount, String reason) {
        Customer customer = customer(id);
        customer.applyBalance(amount);
        var movement = movements.save(new MemberBalanceMovement(
                customer, context.currentUser(), null, amount, reason,
                Instant.now(clock), null));
        return new BalanceView(
                movement.getId(), movement.getAmount(), movement.getReason(),
                movement.getCreatedAt(), customer.getMemberBalance());
    }

    @Transactional(readOnly = true)
    public List<BalanceView> balanceMovements(UUID id) {
        Customer customer = customer(id);
        return movements.findByCustomerIdOrderByCreatedAtDesc(id).stream()
                .map(value -> new BalanceView(
                        value.getId(), value.getAmount(), value.getReason(),
                        value.getCreatedAt(), customer.getMemberBalance()))
                .toList();
    }

    private Customer customer(UUID id) {
        return customers.findByIdAndCompanyId(id, context.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado"));
    }

    private void ensureUnique(
            UUID companyId, DocumentType type, String number, UUID currentId) {
        String normalized = PartyValues.document(number);
        customers.findByCompanyIdAndDocumentTypeAndDocumentNumber(companyId, type, normalized)
                .filter(value -> !value.getId().equals(currentId))
                .ifPresent(value -> {
                    throw new IllegalArgumentException("Ya existe ese documento de cliente");
                });
    }

    public record CustomerCommand(
            String fiscalName,
            DocumentType documentType,
            String documentNumber,
            FiscalAddress address,
            String phone,
            String email,
            String notes,
            CustomerRate rate,
            BigDecimal discount) {
    }

    public record CustomerView(
            UUID id,
            String fiscalName,
            DocumentType documentType,
            String documentNumber,
            FiscalAddress address,
            String phone,
            String email,
            String notes,
            CustomerRate rate,
            BigDecimal discount,
            BigDecimal balance,
            boolean active,
            boolean fiscalDataComplete) {

        static CustomerView from(Customer customer) {
            return new CustomerView(
                    customer.getId(), customer.getFiscalName(), customer.getDocumentType(),
                    customer.getDocumentNumber(), customer.getFiscalAddress(),
                    customer.getPhone(), customer.getEmail(), customer.getNotes(),
                    customer.getRate(), customer.getDiscount(), customer.getMemberBalance(),
                    customer.isActive(), customer.hasCompleteFiscalData());
        }
    }

    public record BalanceView(
            UUID id,
            BigDecimal amount,
            String reason,
            Instant createdAt,
            BigDecimal balance) {
    }
}
