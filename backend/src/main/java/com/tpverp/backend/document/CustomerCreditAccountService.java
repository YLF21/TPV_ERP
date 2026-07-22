package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.CustomerRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read model for a customer's current account and credit exposure. */
@Service
public class CustomerCreditAccountService {

    private final CurrentOrganization organization;
    private final CustomerRepository customers;
    private final CommercialDocumentRepository documents;
    private final Clock clock;

    public CustomerCreditAccountService(
            CurrentOrganization organization,
            CustomerRepository customers,
            CommercialDocumentRepository documents,
            Clock clock) {
        this.organization = organization;
        this.customers = customers;
        this.documents = documents;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AccountView account(UUID customerId, Authentication authentication) {
        Objects.requireNonNull(authentication, "authentication");
        var companyId = organization.currentCompany().getId();
        var store = organization.currentStore();
        var customer = customers.findByIdAndCompanyId(
                        Objects.requireNonNull(customerId, "customerId"), companyId)
                .orElseThrow(() -> new IllegalArgumentException("customer_credit_account_not_found"));
        var businessDate = LocalDate.now(clock.withZone(ZoneId.of(store.getTimezone())));
        var scopedDocuments = documents.findCustomerAccountDocuments(store.getId(), customerId);

        var companyOutstanding = money(customers.outstandingDebt(customerId));
        var companyOverdue = money(customers.overdueDebt(customerId, businessDate));
        var storeOutstanding = scopedDocuments.stream()
                .map(CommercialDocument::getPendingTotal)
                .map(CustomerCreditAccountService::money)
                .reduce(zero(), BigDecimal::add);
        var storeOverdue = scopedDocuments.stream()
                .filter(document -> money(document.getPendingTotal()).signum() > 0)
                .filter(document -> document.getDueDate() != null
                        && document.getDueDate().isBefore(businessDate))
                .map(CommercialDocument::getPendingTotal)
                .map(CustomerCreditAccountService::money)
                .reduce(zero(), BigDecimal::add);
        var available = customer.getCreditLimit() == null ? null
                : money(customer.getCreditLimit().subtract(companyOutstanding));
        var entries = statement(scopedDocuments, store.getTimezone());

        return new AccountView(
                customer.getId(), customer.getClientId(), customer.getFiscalName(),
                customer.isCreditEnabled(), customer.getCreditLimit(),
                customer.getPaymentTermDays(), customer.isCreditBlocked(),
                customer.isBlockOnOverdue(), companyOutstanding, companyOverdue,
                available, storeOutstanding, storeOverdue,
                scopedDocuments.stream().filter(value -> money(value.getPendingTotal()).signum() > 0).count(),
                scopedDocuments.stream().filter(value -> money(value.getPendingTotal()).signum() > 0)
                        .filter(value -> value.getDueDate() != null
                                && value.getDueDate().isBefore(businessDate)).count(),
                entries);
    }

    private static List<AccountEntry> statement(
            List<CommercialDocument> documents, String timezone) {
        var zone = ZoneId.of(timezone);
        var pending = new ArrayList<PendingEntry>();
        for (var document : documents) {
            var issuedAt = document.getConfirmadoEn() == null
                    ? document.getFecha().atStartOfDay(zone).toInstant()
                    : document.getConfirmadoEn();
            pending.add(new PendingEntry(
                    document.getId(), EntryKind.SALE, issuedAt, document.getId(),
                    document.getNumero(), document.getTipo(), null, null, null,
                    money(document.getTotal()), zero()));
            for (var payment : document.getPagos()) {
                pending.add(new PendingEntry(
                        payment.getRequestId() == null ? payment.getId() : payment.getRequestId(),
                        EntryKind.PAYMENT, payment.getCreadoEn(), document.getId(),
                        document.getNumero(), document.getTipo(), payment.getId(),
                        payment.getMetodoPago().getNombre(), payment.getReferencia(), zero(),
                        money(payment.getImporte())));
            }
        }
        pending.sort(Comparator.comparing(PendingEntry::occurredAt)
                .thenComparing(entry -> entry.kind() == EntryKind.SALE ? 0 : 1)
                .thenComparing(PendingEntry::id));
        var balance = zero();
        var result = new ArrayList<AccountEntry>(pending.size());
        for (var entry : pending) {
            balance = money(balance.add(entry.debit()).subtract(entry.credit()));
            result.add(new AccountEntry(
                    entry.id(), entry.kind(), entry.occurredAt(), entry.documentId(),
                    entry.documentNumber(), entry.documentType(), entry.paymentId(),
                    entry.paymentMethod(), entry.reference(), entry.debit(), entry.credit(),
                    balance));
        }
        return List.copyOf(result.reversed());
    }

    private static BigDecimal zero() {
        return Money.euros(BigDecimal.ZERO);
    }

    private static BigDecimal money(BigDecimal value) {
        return Money.euros(value == null ? BigDecimal.ZERO : value);
    }

    public enum EntryKind { SALE, PAYMENT }

    private record PendingEntry(
            UUID id, EntryKind kind, Instant occurredAt, UUID documentId,
            String documentNumber, CommercialDocumentType documentType, UUID paymentId,
            String paymentMethod, String reference, BigDecimal debit, BigDecimal credit) {}

    public record AccountEntry(
            UUID id, EntryKind kind, Instant occurredAt, UUID documentId,
            String documentNumber, CommercialDocumentType documentType, UUID paymentId,
            String paymentMethod, String reference, BigDecimal debit, BigDecimal credit,
            BigDecimal balance) {}

    public record AccountView(
            UUID customerId, String customerCode, String customerName,
            boolean creditEnabled, BigDecimal creditLimit, int paymentTermDays,
            boolean creditBlocked, boolean blockOnOverdue,
            BigDecimal outstandingDebt, BigDecimal overdueDebt, BigDecimal availableCredit,
            BigDecimal storeOutstandingDebt, BigDecimal storeOverdueDebt,
            long openDocumentCount, long overdueDocumentCount,
            List<AccountEntry> entries) {}
}
