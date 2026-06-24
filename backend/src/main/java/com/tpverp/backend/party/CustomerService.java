package com.tpverp.backend.party;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final MemberBalanceMovementRepository movements;
    private final PartyContext context;
    private final PartyCodeAllocator codes;
    private final Clock clock;

    public CustomerService(
            CustomerRepository customers,
            MemberBalanceMovementRepository movements,
            PartyContext context,
            PartyCodeAllocator codes,
            Clock clock) {
        this.customers = customers;
        this.movements = movements;
        this.context = context;
        this.codes = codes;
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
        var store = context.currentStore();
        ensureUnique(company.getId(), command.documentType(), command.documentNumber(), null);
        ensureUniqueMemberNumber(company.getId(), command.numMember(), null);
        var customer = new Customer(
                company, command.fiscalName(), command.documentType(), command.documentNumber(),
                command.address(), command.phone(), command.email(), command.notes(),
                CustomerRate.VENTA, command.discount());
        customer.assignClientCode(store.getId(), codes.nextClient(store));
        customer.setNumMember(command.numMember());
        if (command.member()) {
            customer.activateMember(codes.nextMember(store), LocalDate.now(clock));
            customer.assignMemberStore(store.getId());
        }
        return CustomerView.from(customers.save(customer));
    }

    @Transactional
    public List<CustomerView> createBatch(List<CustomerCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        var company = context.currentCompany();
        var store = context.currentStore();
        List<CustomerCommand> ordered = commands.stream()
                .sorted(Comparator.comparing(
                        command -> PartyValues.document(command.documentNumber())))
                .toList();
        ordered.forEach(command -> {
            ensureUnique(company.getId(), command.documentType(), command.documentNumber(), null);
            ensureUniqueMemberNumber(company.getId(), command.numMember(), null);
        });
        List<String> reservedCodes = codes.nextClients(store, ordered.size());
        var pending = new java.util.ArrayList<Customer>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            CustomerCommand command = ordered.get(index);
            var customer = new Customer(
                    company, command.fiscalName(), command.documentType(),
                    command.documentNumber(), command.address(), command.phone(),
                    command.email(), command.notes(), CustomerRate.VENTA, command.discount());
            customer.assignClientCode(store.getId(), reservedCodes.get(index));
            customer.setNumMember(command.numMember());
            if (command.member()) {
                customer.activateMember(codes.nextMember(store), LocalDate.now(clock));
                customer.assignMemberStore(store.getId());
            }
            pending.add(customer);
        }
        return customers.saveAll(pending).stream().map(CustomerView::from).toList();
    }

    @Transactional
    public CustomerView update(UUID id, CustomerCommand command) {
        Customer customer = customer(id);
        ensureUnique(context.currentCompany().getId(), command.documentType(),
                command.documentNumber(), id);
        ensureUniqueMemberNumber(context.currentCompany().getId(), command.numMember(), id);
        customer.update(
                command.fiscalName(), command.documentType(), command.documentNumber(),
                command.address(), command.phone(), command.email(), command.notes(),
                customer.isMember() ? CustomerRate.MEMBER : CustomerRate.VENTA,
                command.discount());
        customer.setNumMember(command.numMember());
        if (command.member() && !customer.isMember()) {
            if (customer.getCodeMember() == null) {
                var store = context.currentStore();
                customer.activateMember(codes.nextMember(store), LocalDate.now(clock));
                customer.assignMemberStore(store.getId());
            } else {
                customer.activateMember(customer.getCodeMember(), customer.getMemberSince());
            }
        } else if (!command.member() && customer.isMember()) {
            customer.deactivateMember();
        }
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

    private void ensureUniqueMemberNumber(UUID companyId, String number, UUID currentId) {
        String normalized = PartyValues.optional(number);
        if (normalized == null) {
            return;
        }
        customers.findByCompanyIdAndNumMember(companyId, normalized)
                .filter(value -> !value.getId().equals(currentId))
                .ifPresent(value -> {
                    throw new IllegalArgumentException("Ya existe ese numero de member");
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
            BigDecimal discount,
            boolean member,
            String numMember) {
    }

    public record CustomerView(
            UUID id,
            String codeClient,
            String fiscalName,
            DocumentType documentType,
            String documentNumber,
            FiscalAddress address,
            String phone,
            String email,
            String notes,
            CustomerRate rate,
            BigDecimal discount,
            boolean isMember,
            String codeMember,
            String numMember,
            LocalDate memberSince,
            BigDecimal balance,
            boolean active,
            boolean fiscalDataComplete) {

        static CustomerView from(Customer customer) {
            return new CustomerView(
                    customer.getId(), customer.getCodeClient(), customer.getFiscalName(),
                    customer.getDocumentType(),
                    customer.getDocumentNumber(), customer.getFiscalAddress(),
                    customer.getPhone(), customer.getEmail(), customer.getNotes(),
                    customer.getRate(), customer.getDiscount(), customer.isMember(),
                    customer.getCodeMember(), customer.getNumMember(),
                    customer.getMemberSince(), customer.getMemberBalance(),
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
