package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.FiscalAddress;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerReceivablePrintService {
    private final CommercialDocumentRepository documents;
    private final DocumentPaymentRepository payments;
    private final CurrentOrganization organization;
    private final CustomerRepository customers;

    public CustomerReceivablePrintService(CommercialDocumentRepository documents,
            DocumentPaymentRepository payments, CurrentOrganization organization,
            CustomerRepository customers) {
        this.documents = documents; this.payments = payments; this.organization = organization;
        this.customers = customers;
    }

    @Transactional(readOnly = true)
    public CommercialDocumentPrint document(UUID documentId) {
        var document = scoped(documentId);
        var company = organization.currentCompany();
        var customer = customers.findByIdAndCompanyId(document.getClienteId(), company.getId())
                .orElseThrow(() -> new IllegalArgumentException("customer_receivable_customer_not_found"));
        return new CommercialDocumentPrint(document.getId(), document.getTipo(),
                document.getNumero(), document.getFecha(), document.getConfirmadoEn(),
                document.getClienteId(), FiscalParty.from(company), FiscalParty.from(customer),
                document.getLineas().stream().map(Line::from).toList(),
                document.getBaseTotal(), document.getImpuestoTotal(), document.getTotal());
    }

    @Transactional(readOnly = true)
    public PaymentReceipt paymentReceipt(UUID documentId, UUID paymentId) {
        var document = scoped(documentId);
        var payment = payments.findByRequestId(Objects.requireNonNull(paymentId, "paymentId"))
                .filter(value -> value.getDocumento().getId().equals(document.getId()))
                .orElseThrow(() -> new IllegalArgumentException("customer_receivable_payment_not_found"));
        return receipt(document, payment);
    }

    @Transactional(readOnly = true)
    public PaymentReceipt paymentReceiptByPaymentId(UUID documentId, UUID paymentId) {
        var document = scoped(documentId);
        var payment = payments.findCustomerReceivablePayment(
                        document.getId(), Objects.requireNonNull(paymentId, "paymentId"),
                        organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "customer_receivable_payment_not_found"));
        return receipt(document, payment);
    }

    private PaymentReceipt receipt(CommercialDocument document, DocumentPayment payment) {
        var paidThroughReceipt = payments.findAllByDocumentoId(document.getId()).stream()
                .sorted(Comparator.comparingInt(DocumentPayment::getPosicion)
                        .thenComparing(DocumentPayment::getCreadoEn)
                        .thenComparing(DocumentPayment::getId))
                .takeWhile(value -> compare(value, payment) <= 0)
                .map(DocumentPayment::getImporte)
                .map(Money::euros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var printablePaymentId = payment.getRequestId() == null
                ? payment.getId() : payment.getRequestId();
        return new PaymentReceipt(printablePaymentId, document.getId(), document.getNumero(),
                document.getClienteId(), payment.getCreadoEn(), payment.getMetodoPago().getNombre(),
                payment.getImporte(), payment.getReferencia(),
                Money.euros(document.getTotal()).subtract(paidThroughReceipt).max(BigDecimal.ZERO));
    }

    private static int compare(DocumentPayment left, DocumentPayment right) {
        int byPosition = Integer.compare(left.getPosicion(), right.getPosicion());
        if (byPosition != 0) return byPosition;
        int byDate = left.getCreadoEn().compareTo(right.getCreadoEn());
        return byDate != 0 ? byDate : left.getId().compareTo(right.getId());
    }

    private CommercialDocument scoped(UUID id) {
        return documents.findCustomerDocumentForPrint(Objects.requireNonNull(id, "documentId"),
                organization.currentStore().getId()).orElseThrow(() ->
                        new IllegalArgumentException("customer_receivable_not_found"));
    }

    public record Line(String code, String name, BigDecimal quantity, BigDecimal unitPrice,
            boolean taxesIncluded, BigDecimal base, BigDecimal tax, BigDecimal total) {
        static Line from(DocumentLine line) { return new Line(line.getCodigo(), line.getNombre(),
                line.getCantidad(), line.getPrecioUnitario(), line.isImpuestosIncluidos(),
                line.getBase(), line.getImpuesto(), line.getTotal()); }
    }
    public record CommercialDocumentPrint(UUID documentId, CommercialDocumentType documentType,
            String documentNumber, LocalDate issueDate, Instant issuedAt, UUID customerId,
            FiscalParty issuer, FiscalParty customer,
            List<Line> lines, BigDecimal baseTotal, BigDecimal taxTotal, BigDecimal total) {}
    public record FiscalParty(String name, String taxId, PartyAddress address) {
        static FiscalParty from(Company company) {
            var address = company.getDomicilioFiscal();
            return new FiscalParty(company.getRazonSocial(), company.getTaxId(),
                    new PartyAddress(address.get("linea1"), address.get("codigoPostal"),
                            address.get("ciudad"), address.get("provincia"), address.get("pais")));
        }

        static FiscalParty from(Customer customer) {
            return new FiscalParty(customer.getFiscalName(), customer.getDocumentNumber(),
                    PartyAddress.from(customer.getFiscalAddress()));
        }
    }
    public record PartyAddress(String line1, String postalCode, String city,
            String province, String country) {
        static PartyAddress from(FiscalAddress address) {
            return address == null ? new PartyAddress(null, null, null, null, null)
                    : new PartyAddress(address.getAddress(), address.getPostalCode(),
                            address.getCity(), address.getProvince(), address.getCountry());
        }
    }
    public record PaymentReceipt(UUID paymentId, UUID documentId, String documentNumber,
            UUID customerId, Instant collectedAt, String method, BigDecimal amount,
            String reference, BigDecimal remaining) {}
}
