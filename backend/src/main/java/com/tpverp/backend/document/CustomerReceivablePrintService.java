package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CompanyPrintIdentityView;
import com.tpverp.backend.document.template.InvoiceJasperRenderer;
import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.DocumentTemplateType;
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
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerReceivablePrintService {
    private final CommercialDocumentRepository documents;
    private final DocumentPaymentRepository payments;
    private final CurrentOrganization organization;
    private final CustomerRepository customers;
    private final InvoicePresentationSnapshotFactory presentationSnapshots;
    private final DocumentFiscalQrService fiscalQr;
    private final com.tpverp.backend.verifactu.FiscalQrImageService fiscalQrImages;
    private final InvoiceJasperRenderer jasperRenderer;

    public CustomerReceivablePrintService(CommercialDocumentRepository documents,
            DocumentPaymentRepository payments, CurrentOrganization organization,
            CustomerRepository customers) {
        this(documents, payments, organization, customers, null);
    }

    CustomerReceivablePrintService(CommercialDocumentRepository documents,
            DocumentPaymentRepository payments, CurrentOrganization organization,
            CustomerRepository customers,
            InvoicePresentationSnapshotFactory presentationSnapshots) {
        this(documents, payments, organization, customers, presentationSnapshots, null);
    }

    CustomerReceivablePrintService(CommercialDocumentRepository documents,
            DocumentPaymentRepository payments, CurrentOrganization organization,
            CustomerRepository customers,
            InvoicePresentationSnapshotFactory presentationSnapshots,
            DocumentFiscalQrService fiscalQr) {
        this(documents, payments, organization, customers, presentationSnapshots,
                fiscalQr, null, null);
    }

    CustomerReceivablePrintService(CommercialDocumentRepository documents,
            DocumentPaymentRepository payments, CurrentOrganization organization,
            CustomerRepository customers,
            InvoicePresentationSnapshotFactory presentationSnapshots,
            DocumentFiscalQrService fiscalQr,
            com.tpverp.backend.verifactu.FiscalQrImageService fiscalQrImages) {
        this(documents, payments, organization, customers, presentationSnapshots,
                fiscalQr, fiscalQrImages, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    CustomerReceivablePrintService(CommercialDocumentRepository documents,
            DocumentPaymentRepository payments, CurrentOrganization organization,
            CustomerRepository customers,
            InvoicePresentationSnapshotFactory presentationSnapshots,
            DocumentFiscalQrService fiscalQr,
            com.tpverp.backend.verifactu.FiscalQrImageService fiscalQrImages,
            InvoiceJasperRenderer jasperRenderer) {
        this.documents = documents; this.payments = payments; this.organization = organization;
        this.customers = customers;
        this.presentationSnapshots = presentationSnapshots;
        this.fiscalQr = fiscalQr;
        this.fiscalQrImages = fiscalQrImages;
        this.jasperRenderer = jasperRenderer;
    }

    @Transactional(readOnly = true)
    public CommercialDocumentPrint document(UUID documentId) {
        var document = scoped(documentId);
        var company = organization.currentCompany();
        var customer = document.getClienteId() == null
                ? null
                : customers.findByIdAndCompanyId(document.getClienteId(), company.getId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "customer_receivable_customer_not_found"));
        if (document.getTipo() != CommercialDocumentType.ALBARAN_VENTA
                && customer == null) {
            throw new IllegalArgumentException("customer_receivable_customer_not_found");
        }
        var presentation = presentation(document);
        String logoDataUri = presentationSnapshots == null ? null
                : presentationSnapshots.logoDataUri(
                        presentation, organization.currentStore().getId());
        String qrUrl = fiscalQr == null
                || document.getTipo() == CommercialDocumentType.ALBARAN_VENTA
                ? null
                : fiscalQr.qrUrl(document.getId());
        var rendered = jasperRenderer == null ? java.util.Optional.<byte[]>empty()
                : logoDataUri == null
                        ? jasperRenderer.render(document, organization.currentStore(), company,
                                customer, presentation, qrUrl)
                        : jasperRenderer.render(document, organization.currentStore(), company,
                                customer, presentation, qrUrl, logoDataUri);
        var renderedPdf = rendered
                .map(bytes -> new RenderedPdf(
                        "application/pdf",
                        Base64.getEncoder().encodeToString(bytes)))
                .orElse(null);
        var ticketRendered = jasperRenderer == null
                || document.getTipo() != CommercialDocumentType.FACTURA_VENTA
                ? java.util.Optional.<InvoiceJasperRenderer.RenderedDocument>empty()
                : jasperRenderer.renderDocument(document, organization.currentStore(), company,
                        customer, presentation, qrUrl, logoDataUri,
                        DocumentTemplateFormat.TICKET_80);
        var ticketRenderedPdf = ticketRendered
                .map(renderedDocument -> new RenderedPdf(
                        "application/pdf",
                        Base64.getEncoder().encodeToString(renderedDocument.pdf())))
                .orElse(null);
        var ticketRenderedImage = ticketRendered
                .map(InvoiceJasperRenderer.RenderedDocument::ticketRasterPng)
                .filter(Objects::nonNull)
                .map(bytes -> new RenderedImage(
                        "image/png",
                        Base64.getEncoder().encodeToString(bytes)))
                .orElse(null);
        return new CommercialDocumentPrint(document.getId(), document.getTipo(),
                document.getNumero(), document.getFecha(), document.getConfirmadoEn(),
                document.getClienteId(), FiscalParty.from(company, logoDataUri),
                customer == null ? null : FiscalParty.from(customer),
                document.getLineas().stream().map(Line::from).toList(),
                document.getPagos().stream()
                        .sorted(Comparator.comparingInt(DocumentPayment::getPosicion))
                        .map(Payment::from).toList(),
                document.getBaseTotal(), document.getImpuestoTotal(), document.getTotal(),
                presentation.fiscalProfile(), presentation.observations(),
                presentation.bankAccounts(), qrUrl, qrImage(qrUrl), renderedPdf,
                ticketRenderedPdf, ticketRenderedImage);
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
                payment.getImporte(), payment.getReferencia(), payment.getTransferDate(),
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

    private InvoicePresentationSnapshot presentation(CommercialDocument document) {
        if (presentationSnapshots == null) {
            return new InvoicePresentationSnapshot(
                    1, InvoiceFiscalProfile.IVA, null, List.of());
        }
        String frozen = document.getInvoicePrintSnapshot();
        return presentationSnapshots.read(frozen == null
                ? presentationSnapshots.create(templateType(document)) : frozen);
    }

    private static DocumentTemplateType templateType(CommercialDocument document) {
        return document.getTipo() == CommercialDocumentType.ALBARAN_VENTA
                ? DocumentTemplateType.ALBARAN_VENTA
                : DocumentTemplateType.FACTURA_VENTA;
    }

    private String qrImage(String qrUrl) {
        if (qrUrl == null || fiscalQrImages == null) return null;
        var image = fiscalQrImages.png(qrUrl, 240);
        return "data:" + image.contentType() + ";base64,"
                + Base64.getEncoder().encodeToString(image.bytes());
    }

    public record Line(String code, String name, BigDecimal quantity, BigDecimal unitPrice,
            boolean taxesIncluded, String taxRegime, BigDecimal taxPercentage,
            BigDecimal base, BigDecimal tax, BigDecimal total,
            List<String> serialNumbers, String barcode) {
        public Line(String code, String name, BigDecimal quantity, BigDecimal unitPrice,
                boolean taxesIncluded, String taxRegime, BigDecimal taxPercentage,
                BigDecimal base, BigDecimal tax, BigDecimal total,
                List<String> serialNumbers) {
            this(code, name, quantity, unitPrice, taxesIncluded, taxRegime,
                    taxPercentage, base, tax, total, serialNumbers, null);
        }

        public Line(String code, String name, BigDecimal quantity, BigDecimal unitPrice,
                boolean taxesIncluded, BigDecimal base, BigDecimal tax, BigDecimal total) {
            this(code, name, quantity, unitPrice, taxesIncluded, null, BigDecimal.ZERO,
                    base, tax, total, List.of(), null);
        }

        static Line from(DocumentLine line) { return new Line(line.getCodigo(), line.getNombre(),
                line.getCantidad(), line.getPrecioUnitario(), line.isImpuestosIncluidos(),
                line.getRegimenImpuesto(), line.getPorcentajeImpuesto(), line.getBase(),
                line.getImpuesto(), line.getTotal(), line.getSerialNumbers(),
                line.getCodigoBarras()); }
    }
    public record CommercialDocumentPrint(UUID documentId, CommercialDocumentType documentType,
            String documentNumber, LocalDate issueDate, Instant issuedAt, UUID customerId,
            FiscalParty issuer, FiscalParty customer,
            List<Line> lines, List<Payment> payments,
            BigDecimal baseTotal, BigDecimal taxTotal, BigDecimal total,
            InvoiceFiscalProfile fiscalProfile, String observations,
            List<InvoicePresentationSnapshot.BankAccount> bankAccounts,
            String qrUrl, String qrImage, RenderedPdf renderedPdf,
            RenderedPdf ticketRenderedPdf, RenderedImage ticketRenderedImage) {
        public CommercialDocumentPrint(
                UUID documentId,
                CommercialDocumentType documentType,
                String documentNumber,
                LocalDate issueDate,
                Instant issuedAt,
                UUID customerId,
                FiscalParty issuer,
                FiscalParty customer,
                List<Line> lines,
                List<Payment> payments,
                BigDecimal baseTotal,
                BigDecimal taxTotal,
                BigDecimal total,
                InvoiceFiscalProfile fiscalProfile,
                String observations,
                List<InvoicePresentationSnapshot.BankAccount> bankAccounts,
                String qrUrl,
                String qrImage) {
            this(documentId, documentType, documentNumber, issueDate, issuedAt,
                    customerId, issuer, customer, lines, payments, baseTotal,
                    taxTotal, total, fiscalProfile, observations, bankAccounts,
                    qrUrl, qrImage, null, null, null);
        }
    }
    public record RenderedPdf(String contentType, String base64) {
        public RenderedPdf {
            if (!"application/pdf".equals(contentType)
                    || base64 == null || base64.isBlank()) {
                throw new IllegalArgumentException("invoice_rendered_pdf_invalid");
            }
        }
    }
    public record RenderedImage(String contentType, String base64) {
        public RenderedImage {
            if (!"image/png".equals(contentType)
                    || base64 == null || base64.isBlank()) {
                throw new IllegalArgumentException("invoice_rendered_image_invalid");
            }
        }
    }
    public record Payment(String method, BigDecimal amount, String reference,
            LocalDate transferDate) {
        static Payment from(DocumentPayment payment) {
            return new Payment(payment.getMetodoPago().getNombre(), payment.getImporte(),
                    payment.getReferencia(), payment.getTransferDate());
        }
    }
    public record FiscalParty(String name, String taxId, PartyAddress address,
            String phone, String logo) {
        static FiscalParty from(Company company, String logo) {
            var identity = CompanyPrintIdentityView.from(company);
            return new FiscalParty(identity.name(), identity.taxId(),
                    new PartyAddress(identity.address().line1(), identity.address().postalCode(),
                            identity.address().city(), identity.address().province(),
                            identity.address().country()), null, logo);
        }

        static FiscalParty from(Customer customer) {
            return new FiscalParty(customer.getFiscalName(), customer.getDocumentNumber(),
                    PartyAddress.from(customer.getFiscalAddress()), customer.getPhone(), null);
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
            String reference, LocalDate transferDate, BigDecimal remaining) {}
}
