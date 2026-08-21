package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class DocumentMemberBalanceResolver {

    private final SalePaymentSessionRepository paymentSessions;
    private final CurrentOrganization organization;

    public DocumentMemberBalanceResolver(
            SalePaymentSessionRepository paymentSessions,
            CurrentOrganization organization) {
        this.paymentSessions = paymentSessions;
        this.organization = organization;
    }

    public Resolution resolve(Collection<CommercialDocument> documents) {
        if (documents.isEmpty()) {
            return Resolution.empty();
        }
        var storeId = organization.currentStore().getId();
        var documentIds = documents.stream()
                .map(CommercialDocument::getId)
                .toList();
        var byDocumentId = paymentSessions
                .findFinalizedMemberBalanceTotalsByTicketIds(storeId, documentIds)
                .stream()
                .collect(Collectors.toMap(
                        SalePaymentSessionRepository.MemberBalanceReportTotal::getTicketId,
                        SalePaymentSessionRepository.MemberBalanceReportTotal::getAmount,
                        BigDecimal::add));
        var ticketNumbers = documents.stream()
                .map(CommercialDocument::getNumTicket)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        var byTicketNumber = ticketNumbers.isEmpty()
                ? Map.<String, BigDecimal>of()
                : paymentSessions
                        .findFinalizedMemberBalanceTotalsByTicketNumbers(storeId, ticketNumbers)
                        .stream()
                        .collect(Collectors.toMap(
                                SalePaymentSessionRepository.MemberBalanceReportTotal::getTicketNumber,
                                SalePaymentSessionRepository.MemberBalanceReportTotal::getAmount,
                                BigDecimal::add));
        return new Resolution(byDocumentId, byTicketNumber);
    }

    public record Resolution(
            Map<UUID, BigDecimal> byDocumentId,
            Map<String, BigDecimal> byTicketNumber) {

        public static Resolution empty() {
            return new Resolution(Map.of(), Map.of());
        }

        public BigDecimal amountFor(CommercialDocument document) {
            var byId = byDocumentId.get(document.getId());
            if (byId != null) {
                return byId;
            }
            var ticketNumber = document.getNumTicket();
            return ticketNumber == null ? null : byTicketNumber.get(ticketNumber);
        }
    }
}
