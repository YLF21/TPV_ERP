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
    private final MemberRepository members;
    private final MemberLoyaltyService memberLoyalty;
    private final Clock clock;

    public CustomerService(
            CustomerRepository customers,
            MemberBalanceMovementRepository movements,
            PartyContext context,
            PartyCodeAllocator codes,
            MemberRepository members,
            MemberLoyaltyService memberLoyalty,
            Clock clock) {
        this.customers = customers;
        this.movements = movements;
        this.context = context;
        this.codes = codes;
        this.members = members;
        this.memberLoyalty = memberLoyalty;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CustomerView> list() {
        return customers.findByCompanyIdOrderByFiscalName(context.currentCompany().getId())
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public CustomerView get(UUID id) {
        return view(customer(id));
    }

    @Transactional
    public CustomerView create(CustomerCommand command) {
        var company = context.currentCompany();
        var store = context.currentStore();
        rejectDirectMemberCreation(command);
        ensureUnique(company.getId(), command.documentType(), command.documentNumber(), null);
        var customer = new Customer(
                company, command.fiscalName(), command.documentType(), command.documentNumber(),
                command.address(), command.phone(), command.email(), command.notes(),
                CustomerRate.VENTA, command.discount());
        customer.updateProfile(
                command.birthday(), command.gender(), command.commercialConsent(),
                command.preferredCommercialChannelId());
        applyCreditConfiguration(customer, command, true);
        customer.assignClientCode(store.getId(), codes.nextClient(store));
        customer = customers.save(customer);
        return view(customer, null);
    }

    @Transactional
    public List<CustomerView> createBatch(List<CustomerCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        var company = context.currentCompany();
        var store = context.currentStore();
        commands.forEach(this::rejectDirectMemberCreation);
        List<CustomerCommand> ordered = commands.stream()
                .sorted(Comparator.comparing(
                        command -> PartyValues.document(command.documentNumber())))
                .toList();
        ordered.forEach(command -> {
            ensureUnique(company.getId(), command.documentType(), command.documentNumber(), null);
        });
        List<String> reservedCodes = codes.nextClients(store, ordered.size());
        var pending = new java.util.ArrayList<Customer>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            CustomerCommand command = ordered.get(index);
            var customer = new Customer(
                    company, command.fiscalName(), command.documentType(),
                    command.documentNumber(), command.address(), command.phone(),
                    command.email(), command.notes(), CustomerRate.VENTA, command.discount());
            customer.updateProfile(
                    command.birthday(), command.gender(), command.commercialConsent(),
                    command.preferredCommercialChannelId());
            applyCreditConfiguration(customer, command, true);
            customer.assignClientCode(store.getId(), reservedCodes.get(index));
            pending.add(customer);
        }
        List<Customer> saved = customers.saveAll(pending);
        return saved.stream().map(this::view).toList();
    }

    @Transactional
    public CustomerView update(UUID id, CustomerCommand command) {
        Customer customer = customer(id);
        ensureUnique(context.currentCompany().getId(), command.documentType(),
                command.documentNumber(), id);
        ensureUniqueMemberNumber(context.currentCompany().getId(), command.numMember(), id);
        Member member = members.findByCustomerIdAndCompanyId(id, context.currentCompany().getId())
                .orElse(null);
        customer.update(
                command.fiscalName(), command.documentType(), command.documentNumber(),
                command.address(), command.phone(), command.email(), command.notes(),
                CustomerRate.VENTA, command.discount());
        customer.updateProfile(
                command.birthday(), command.gender(), command.commercialConsent(),
                command.preferredCommercialChannelId());
        applyCreditConfiguration(customer, command, false);
        if (command.member()) {
            if (member == null) {
                var store = context.currentStore();
                member = new Member(customer, codes.nextMember(store), LocalDate.now(clock));
                member.assignMemberStore(store.getId());
                members.save(member);
                memberLoyalty.activateMember(member);
            } else {
                if (!member.isActive()) {
                    memberLoyalty.activateMember(member);
                }
            }
            member.setNumMember(command.numMember());
        } else if (member != null && member.isActive()) {
            memberLoyalty.deactivateMember(member);
        }
        return view(customer, member);
    }

    @Transactional
    public void deactivate(UUID id) {
        customer(id).deactivate();
    }

    @Transactional
    public void activate(UUID id) {
        customer(id).activate();
    }

    @Transactional
    public CustomerView activateMember(UUID id) {
        Customer customer = customer(id);
        if (!customer.isActive()) {
            throw new IllegalStateException("message.member.customer_inactive");
        }
        Member member = members.findByCustomerIdAndCompanyId(id, context.currentCompany().getId())
                .orElseGet(() -> {
                    var store = context.currentStore();
                    var created = new Member(customer, codes.nextMember(store), LocalDate.now(clock));
                    created.assignMemberStore(store.getId());
                    return members.save(created);
                });
        if (!member.isActive()) {
            memberLoyalty.activateMember(member);
        } else if (member.getMemberCategory() == null) {
            memberLoyalty.activateMember(member);
        }
        return view(customer, member);
    }

    @Transactional
    public CustomerView deactivateMember(UUID id) {
        Customer customer = customer(id);
        members.findByCustomerIdAndCompanyId(id, context.currentCompany().getId())
                .filter(Member::isActive)
                .ifPresent(memberLoyalty::deactivateMember);
        return view(customer);
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
        return view(customer);
    }

    @Transactional
    public BalanceView moveBalance(UUID id, BigDecimal amount, String reason) {
        Customer customer = customer(id);
        Member member = members.findByCustomerIdAndCompanyId(id, context.currentCompany().getId())
                .orElseThrow(() -> new IllegalStateException("Solo los clientes MEMBER tienen saldo"));
        member.applyBalance(amount);
        var movement = movements.save(new MemberBalanceMovement(
                customer, context.currentUser(), null, amount, reason,
                Instant.now(clock), null));
        return new BalanceView(
                movement.getId(), movement.getAmount(), movement.getReason(),
                movement.getCreatedAt(), member.getMemberBalance());
    }

    @Transactional(readOnly = true)
    public List<BalanceView> balanceMovements(UUID id) {
        Customer customer = customer(id);
        Member member = members.findByCustomerIdAndCompanyId(id, context.currentCompany().getId())
                .orElseThrow(() -> new IllegalStateException("Solo los clientes MEMBER tienen saldo"));
        return movements.findByCustomerIdOrderByCreatedAtDesc(id).stream()
                .map(value -> new BalanceView(
                        value.getId(), value.getAmount(), value.getReason(),
                        value.getCreatedAt(), member.getMemberBalance()))
                .toList();
    }

    private Customer customer(UUID id) {
        return customers.findByIdAndCompanyId(id, context.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado"));
    }

    private void rejectDirectMemberCreation(CustomerCommand command) {
        if (command.member()) {
            throw new IllegalArgumentException("message.member.customer_must_exist");
        }
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
        members.findByCompanyIdAndNumMember(companyId, normalized)
                .filter(value -> !value.getCustomer().getId().equals(currentId))
                .ifPresent(value -> {
                    throw new IllegalArgumentException("Ya existe ese numero de member");
                });
    }

    private CustomerView view(Customer customer) {
        return view(customer, members.findByCustomerId(customer.getId()).orElse(null));
    }

    private CustomerView view(Customer customer, Member member) {
        var credit = creditSummary(customer);
        return CustomerView.from(customer, member, credit);
    }

    private CreditSummary creditSummary(Customer customer) {
        var outstanding = money(customers.outstandingDebt(customer.getId()));
        var overdue = money(customers.overdueDebt(customer.getId(), LocalDate.now(clock)));
        var available = customer.getCreditLimit() == null ? null
                : money(customer.getCreditLimit().subtract(outstanding));
        return new CreditSummary(outstanding, overdue, available);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static void applyCreditConfiguration(
            Customer customer, CustomerCommand command, boolean creating) {
        customer.configureCredit(
                command.creditEnabled() == null
                        ? creating || customer.isCreditEnabled() : command.creditEnabled(),
                command.creditLimitSpecified() ? command.creditLimit() : customer.getCreditLimit(),
                command.paymentTermDays() == null
                        ? (creating ? 30 : customer.getPaymentTermDays()) : command.paymentTermDays(),
                command.creditBlocked() == null
                        ? (!creating && customer.isCreditBlocked()) : command.creditBlocked(),
                command.blockOnOverdue() == null
                        ? (!creating && customer.isBlockOnOverdue()) : command.blockOnOverdue());
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
            String numMember,
            LocalDate birthday,
            CustomerGender gender,
            boolean commercialConsent,
            UUID preferredCommercialChannelId,
            Boolean creditEnabled,
            BigDecimal creditLimit,
            boolean creditLimitSpecified,
            Integer paymentTermDays,
            Boolean creditBlocked,
            Boolean blockOnOverdue) {

        public CustomerCommand(
                String fiscalName,
                DocumentType documentType,
                String documentNumber,
                FiscalAddress address,
                String phone,
                String email,
                String notes,
                BigDecimal discount,
                boolean member,
                String numMember,
                LocalDate birthday,
                CustomerGender gender,
                boolean commercialConsent,
                UUID preferredCommercialChannelId) {
            this(fiscalName, documentType, documentNumber, address, phone, email, notes,
                    discount, member, numMember, birthday, gender, commercialConsent,
                    preferredCommercialChannelId, null, null, false, null, null, null);
        }

        public CustomerCommand(
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
            this(fiscalName, documentType, documentNumber, address, phone, email, notes,
                    discount, member, numMember, null, null, false, null,
                    null, null, false, null, null, null);
        }
    }

    public record CustomerView(
            UUID id,
            String clientId,
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
            String memberCategoryName,
            BigDecimal memberDiscountPercent,
            UUID memberUuid,
            String memberId,
            String numMember,
            LocalDate memberSince,
            BigDecimal balance,
            LocalDate birthday,
            CustomerGender gender,
            boolean commercialConsent,
            UUID preferredCommercialChannelId,
            boolean active,
            boolean fiscalDataComplete,
            boolean creditEnabled,
            BigDecimal creditLimit,
            int paymentTermDays,
            boolean creditBlocked,
            boolean blockOnOverdue,
            BigDecimal outstandingDebt,
            BigDecimal overdueDebt,
            BigDecimal availableCredit) {

        static CustomerView from(Customer customer, Member member, CreditSummary credit) {
            boolean activeMember = member != null && member.isActive();
            var category = activeMember ? member.getMemberCategory() : null;
            var memberDiscount = category != null && category.isActive() && category.isDiscountEnabled()
                    ? category.getDiscountPercent()
                    : BigDecimal.ZERO.setScale(2);
            return new CustomerView(
                    customer.getId(), customer.getClientId(), customer.getFiscalName(),
                    customer.getDocumentType(),
                    customer.getDocumentNumber(), customer.getFiscalAddress(),
                    customer.getPhone(), customer.getEmail(), customer.getNotes(),
                    activeMember ? CustomerRate.MEMBER : customer.getRate(),
                    customer.getDiscount(), activeMember,
                    category == null ? null : category.getName(), memberDiscount,
                    member == null ? null : member.getId(),
                    member == null ? null : member.getMemberId(),
                    member == null ? null : member.getNumMember(),
                    member == null ? null : member.getMemberSince(),
                    member == null ? BigDecimal.ZERO.setScale(2) : member.getMemberBalance(),
                    customer.getBirthday(), customer.getGender(),
                    customer.hasCommercialConsent(), customer.getPreferredCommercialChannelId(),
                    customer.isActive(), customer.hasCompleteFiscalData(),
                    customer.isCreditEnabled(), customer.getCreditLimit(),
                    customer.getPaymentTermDays(), customer.isCreditBlocked(),
                    customer.isBlockOnOverdue(), credit.outstandingDebt(),
                    credit.overdueDebt(), credit.availableCredit());
        }
    }

    private record CreditSummary(
            BigDecimal outstandingDebt,
            BigDecimal overdueDebt,
            BigDecimal availableCredit) {}

    public record BalanceView(
            UUID id,
            BigDecimal amount,
            String reason,
            Instant createdAt,
            BigDecimal balance) {
    }
}
