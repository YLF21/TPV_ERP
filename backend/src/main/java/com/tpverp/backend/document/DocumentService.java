package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.SupplierRepository;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.terminal.CurrentTerminal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {

    private static final EnumSet<CommercialDocumentType> DELIVERY_NOTES = EnumSet.of(
            CommercialDocumentType.ALBARAN_VENTA, CommercialDocumentType.ALBARAN_COMPRA);
    private static final EnumSet<CommercialDocumentType> INVOICES = EnumSet.of(
            CommercialDocumentType.FACTURA_VENTA, CommercialDocumentType.FACTURA_COMPRA,
            CommercialDocumentType.RECTIFICATIVA_VENTA, CommercialDocumentType.RECTIFICATIVA_COMPRA);

    private final CommercialDocumentRepository documents;
    private final DocumentCounterRepository counters;
    private final PaymentMethodRepository paymentMethods;
    private final DocumentRelationRepository relations;
    private final StockDocumentGateway stockGateway;
    private final CurrentOrganization organization;
    private final CustomerRepository customers;
    private final SupplierRepository suppliers;
    private final ConfirmedPurchaseRecorder purchaseRecorder;
    private final DocumentFiscalIntegration fiscalIntegration;
    private final VoucherService vouchers;
    private final CurrentTerminal currentTerminal;
    private final CashPaymentRecorder cashPayments;
    private final SyncOutboxService syncOutbox;
    private final Clock clock;

    public DocumentService(
            CommercialDocumentRepository documents,
            DocumentCounterRepository counters,
            PaymentMethodRepository paymentMethods,
            DocumentRelationRepository relations,
            StockDocumentGateway stockGateway,
            CurrentOrganization organization,
            CustomerRepository customers,
            SupplierRepository suppliers,
            ConfirmedPurchaseRecorder purchaseRecorder,
            DocumentFiscalIntegration fiscalIntegration,
            VoucherService vouchers,
            CurrentTerminal currentTerminal,
            CashPaymentRecorder cashPayments,
            SyncOutboxService syncOutbox,
            Clock clock) {
        this.documents = documents;
        this.counters = counters;
        this.paymentMethods = paymentMethods;
        this.relations = relations;
        this.stockGateway = stockGateway;
        this.organization = organization;
        this.customers = customers;
        this.suppliers = suppliers;
        this.purchaseRecorder = purchaseRecorder;
        this.fiscalIntegration = fiscalIntegration;
        this.vouchers = vouchers;
        this.currentTerminal = currentTerminal;
        this.cashPayments = cashPayments;
        this.syncOutbox = syncOutbox;
        this.clock = clock;
    }

    @Transactional
    public CommercialDocument createDeliveryNote(
            DocumentCommand command, Authentication authentication) {
        requireType(command, DELIVERY_NOTES);
        return documents.save(createDraft(command, authentication));
    }

    @Transactional(readOnly = true)
    public List<CommercialDocument> listDeliveryNotes() {
        return documents.findAllByTiendaIdAndTipoInOrderByFechaDesc(
                organization.currentStore().getId(), DELIVERY_NOTES);
    }

    // Confirms, numbers, and records stock/purchase in one transaction.
    @Transactional
    public CommercialDocument confirm(UUID id, Authentication authentication) {
        var document = find(id);
        var userId = organization.currentUser(authentication).getId();
        validateConfirmation(document);
        // Confirmation resets stockOrigin; this flag must be read first.
        boolean recordsPurchase = document.getTipo() == CommercialDocumentType.ALBARAN_COMPRA
                || (document.getTipo() == CommercialDocumentType.FACTURA_COMPRA
                && document.isOrigenStock());
        var requiresStock = requiresStock(document) || document.isOrigenStock();
        document.confirm(nextNumber(document), userId, Instant.now(clock), false);
        document.setStockOrigin(requiresStock && stockGateway.confirm(document));
        if (recordsPurchase) {
            purchaseRecorder.record(
                    document.getProveedorId(),
                    document.getFecha(),
                    document.getLineas().stream()
                            .map(DocumentLine::getProductoId)
                            .distinct()
                            .toList());
        }
        var saved = documents.save(document);
        fiscalIntegration.registerAlta(saved, false);
        enqueueConfirmedDocument(saved, null);
        return saved;
    }

    // Creates and confirms the ticket in one transaction.
    @Transactional
    public CommercialDocument createTicket(
            DocumentCommand command,
            List<PaymentCommand> payments,
            Authentication authentication) {
        if (command.tipo() != CommercialDocumentType.TICKET) {
            throw new IllegalArgumentException("message.document.invalid_ticket_type");
        }
        var ticket = createDraft(command, authentication);
        if (ticket.getTotal().signum() >= 0) {
            requirePaymentsPresent(payments);
            requirePaymentTotal(payments, ticket.getTotal(), "los pagos deben cuadrar con el total del ticket");
        }
        var terminalId = currentTerminal.terminalId(authentication);
        ticket.confirm(
                nextNumber(ticket),
                organization.currentUser(authentication).getId(),
                Instant.now(clock),
                false);
        if (ticket.getTotal().signum() >= 0) {
            addPayments(ticket, payments, "los pagos deben cuadrar con el total del ticket");
        }
        ticket.setStockOrigin(stockGateway.confirm(ticket));
        var saved = documents.save(ticket);
        cashPayments.recordDocumentPayments(terminalId, saved);
        if (saved.getTotal().signum() < 0) {
            vouchers.issueFromNegativeTicket(saved);
        }
        fiscalIntegration.registerAlta(saved, false);
        enqueueConfirmedDocument(saved, terminalId);
        return saved;
    }

    private void enqueueConfirmedDocument(CommercialDocument document, UUID terminalId) {
        enqueueDocumentEvent(document, terminalId, SyncOperation.CONFIRMAR);
    }

    private void enqueueDocumentEvent(
            CommercialDocument document, UUID terminalId, SyncOperation operation) {
        syncOutbox.enqueue(new SyncOutboundEventCommand(
                organization.currentCompany().getId(),
                document.getTiendaId(),
                terminalId,
                "DOCUMENTO",
                document.getId(),
                operation,
                documentPayload(document)));
    }

    private Map<String, Object> documentPayload(CommercialDocument document) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("tipo", document.getTipo().name());
        payload.put("numero", document.getNumero());
        payload.put("estado", document.getEstado().name());
        payload.put("fecha", document.getFecha().toString());
        payload.put("clienteId", nullableUuid(document.getClienteId()));
        payload.put("proveedorId", nullableUuid(document.getProveedorId()));
        payload.put("almacenId", nullableUuid(document.getAlmacenId()));
        payload.put("descuentoGlobal", document.getDescuentoGlobal().toPlainString());
        payload.put("subtotal", document.getBaseTotal().toPlainString());
        payload.put("impuestos", document.getImpuestoTotal().toPlainString());
        payload.put("total", document.getTotal().toPlainString());
        payload.put("moneda", document.getMoneda());
        payload.put("lineas", document.getLineas().stream()
                .map(DocumentService::linePayload)
                .toList());
        payload.put("pagos", document.getPagos().stream()
                .map(DocumentService::paymentPayload)
                .toList());
        return payload;
    }

    private static Map<String, Object> linePayload(DocumentLine line) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("productoId", line.getProductoId().toString());
        payload.put("posicion", line.getPosicion());
        payload.put("codigo", line.getCodigo());
        payload.put("nombre", line.getNombre());
        payload.put("tarifa", line.getTarifa());
        payload.put("cantidad", String.valueOf(line.getCantidad()));
        payload.put("precioUnitario", line.getPrecioUnitario().toPlainString());
        payload.put("descuento", line.getDescuento().toPlainString());
        payload.put("impuestosIncluidos", line.isImpuestosIncluidos());
        payload.put("regimenImpuesto", line.getRegimenImpuesto());
        payload.put("porcentajeImpuesto", line.getPorcentajeImpuesto().toPlainString());
        payload.put("base", line.getBase().toPlainString());
        payload.put("impuesto", line.getImpuesto().toPlainString());
        payload.put("total", line.getTotal().toPlainString());
        return payload;
    }

    private static Map<String, Object> paymentPayload(DocumentPayment payment) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("metodoPagoId", payment.getMetodoPago().getId().toString());
        payload.put("metodoPago", payment.getMetodoPago().getNombre());
        payload.put("posicion", payment.getPosicion());
        payload.put("importe", payment.getImporte().toPlainString());
        payload.put("principal", payment.isPrincipal());
        payload.put("entregado", nullableAmount(payment.getEntregado()));
        payload.put("cambio", nullableAmount(payment.getCambio()));
        payload.put("voucherCode", payment.getVoucherCode());
        payload.put("referencia", payment.getReferencia());
        return payload;
    }

    private static String nullableUuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String nullableAmount(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    @Transactional(readOnly = true)
    public List<CommercialDocument> listTickets() {
        return documents.findAllByTiendaIdAndTipoInOrderByFechaDesc(
                organization.currentStore().getId(), List.of(CommercialDocumentType.TICKET));
    }

    // Cancels the ticket and requests stock reversal only if it was originally applied.
    @Transactional
    public CommercialDocument cancelTicket(
            UUID id, Authentication authentication, String reason) {
        var ticket = find(id);
        if (ticket.getTipo() != CommercialDocumentType.TICKET) {
            throw new IllegalArgumentException("el documento no es un ticket");
        }
        if (relations.existsByOrigen_IdAndTipo(
                ticket.getId(), DocumentRelationType.FACTURA_DE)) {
            throw new IllegalStateException(
                    "el ticket facturado debe corregirse con factura rectificativa");
        }
        if (vouchers.hasVoucherImpact(ticket)) {
            throw new IllegalStateException(
                    "el ticket con vale debe corregirse con un documento rectificativo");
        }
        var userId = organization.currentUser(authentication).getId();
        ticket.cancel(userId, Instant.now(clock), reason);
        if (ticket.isOrigenStock()) {
            stockGateway.cancel(ticket);
        }
        var saved = documents.save(ticket);
        fiscalIntegration.registerTicketCancellation(saved);
        enqueueDocumentEvent(saved, null, SyncOperation.ANULAR);
        return saved;
    }

    // Converts a confirmed ticket into an F3 invoice without duplicating stock or payments.
    @Transactional
    public CommercialDocument convertTicketToInvoice(
            UUID ticketId, UUID customerId, Authentication authentication) {
        var ticket = find(ticketId);
        if (ticket.getTipo() != CommercialDocumentType.TICKET
                || ticket.getEstado() != DocumentStatus.CONFIRMADO) {
            throw new IllegalStateException("solo se puede facturar un ticket confirmado");
        }
        if (relations.existsByOrigen_IdAndTipo(ticket.getId(), DocumentRelationType.FACTURA_DE)) {
            throw new IllegalStateException("el ticket ya esta facturado");
        }
        var invoice = invoiceFromTicket(ticket, customerId, authentication);
        validateConfirmation(invoice);
        invoice.confirm(nextNumber(invoice), organization.currentUser(authentication).getId(),
                Instant.now(clock), false);
        var saved = documents.save(invoice);
        relations.save(new DocumentRelation(saved, ticket, DocumentRelationType.FACTURA_DE));
        fiscalIntegration.registerInvoiceFromTicket(saved, ticket);
        enqueueConfirmedDocument(saved, null);
        return saved;
    }

    @Transactional
    public CommercialDocument createInvoice(
            DocumentCommand command, Authentication authentication) {
        requireType(command, INVOICES);
        return documents.save(createDraft(command, authentication));
    }

    @Transactional(readOnly = true)
    public List<CommercialDocument> listInvoices() {
        return documents.findAllByTiendaIdAndTipoInOrderByFechaDesc(
                organization.currentStore().getId(), INVOICES);
    }

    // Records payments only when they exactly cover the pending total.
    @Transactional
    public CommercialDocument payInvoice(UUID id, List<PaymentCommand> payments, Authentication authentication) {
        var invoice = find(id);
        if (!INVOICES.contains(invoice.getTipo())) {
            throw new IllegalArgumentException("el documento no es una factura");
        }
        return payReceivable(invoice, payments, authentication);
    }

    @Transactional
    public CommercialDocument payDeliveryNote(UUID id, List<PaymentCommand> payments, Authentication authentication) {
        var deliveryNote = find(id);
        if (!DELIVERY_NOTES.contains(deliveryNote.getTipo())) {
            throw new IllegalArgumentException("message.document.only_delivery_note_can_be_paid");
        }
        return payReceivable(deliveryNote, payments, authentication);
    }
    // Records actual delivery-note payments without applying stock again.

    private CommercialDocument payReceivable(
            CommercialDocument document, List<PaymentCommand> payments, Authentication authentication) {
        var terminalId = currentTerminal.terminalId(authentication);
        addPartialPayments(document, payments);
        document.updatePaymentStatus();
        var saved = documents.save(document);
        cashPayments.recordDocumentPayments(terminalId, saved);
        enqueueDocumentEvent(saved, terminalId, SyncOperation.ACTUALIZAR);
        return saved;
    }

    // Exceptionally edits a confirmed ticket or delivery note without stock or audit side effects.
    @Transactional
    public CommercialDocument adminEditConfirmed(
            UUID id,
            BigDecimal globalDiscount,
            UUID customerId,
            UUID supplierId,
            List<DocumentLineCommand> lines) {
        var document = find(id);
        if (fiscalIntegration.hasFiscalRecord(document.getId())) {
            throw new IllegalStateException(
                    "el documento con registro fiscal es inmutable");
        }
        document.adminReplace(
                globalDiscount, customerId, supplierId, List.copyOf(lines));
        return documents.save(document);
    }

    // Explicitly links an invoice to its origin document.
    @Transactional
    public CommercialDocument relate(UUID invoiceId, UUID originId, DocumentRelationType type) {
        var invoice = find(invoiceId);
        if (!INVOICES.contains(invoice.getTipo())) {
            throw new IllegalStateException("solo una factura puede relacionarse con origen");
        }
        Objects.requireNonNull(type, "tipoRelacion");
        var origin = find(originId);
        validateRelationOrigin(type, origin);
        relations.save(new DocumentRelation(invoice, origin, type));
        return invoice;
    }

    private static void validateRelationOrigin(DocumentRelationType type, CommercialDocument origin) {
        if (type == DocumentRelationType.FACTURA_DE && INVOICES.contains(origin.getTipo())) {
            throw new IllegalStateException("origen incompatible para factura agrupada");
        }
    }

    private CommercialDocument createDraft(
            DocumentCommand command, Authentication authentication) {
        Objects.requireNonNull(command, "command");
        if (command.lineas() == null || command.lineas().isEmpty()) {
            throw new IllegalArgumentException("message.document.lines_required");
        }
        var store = organization.currentStore();
        var user = organization.currentUser(authentication);
        var document = new CommercialDocument(
                store.getId(), command.almacenId(), command.tipo(), command.fecha(),
                user.getId(), command.descuentoGlobal());
        document.setParties(
                command.clienteId(), command.proveedorId(), command.numeroExterno());
        document.setStockOrigin(
                command.directo()
                        || command.tipo() == CommercialDocumentType.TICKET
                        || DELIVERY_NOTES.contains(command.tipo()));
        for (var line : command.lineas()) {
            document.addLine(line.toEntity(document));
        }
        return document;
    }

    private CommercialDocument invoiceFromTicket(
            CommercialDocument ticket, UUID customerId, Authentication authentication) {
        var invoice = new CommercialDocument(
                ticket.getTiendaId(), ticket.getAlmacenId(), CommercialDocumentType.FACTURA_VENTA,
                ticket.getFecha(), organization.currentUser(authentication).getId(),
                ticket.getDescuentoGlobal());
        invoice.setParties(Objects.requireNonNull(customerId, "clienteId"), null, null);
        invoice.setNumTicket(ticket.getNumero());
        ticket.getLineas().stream()
                .map(line -> new DocumentLineCommand(
                        line.getProductoId(), line.getCantidad(), line.getCodigo(),
                        line.getNombre(), line.getTarifa(), line.getPrecioUnitario(),
                        line.getDescuento(), line.isImpuestosIncluidos(),
                        line.getRegimenImpuesto(), line.getPorcentajeImpuesto()))
                .forEach(line -> invoice.addLine(line.toEntity(invoice)));
        invoice.setStockOrigin(false);
        return invoice;
    }

    private void addPayments(
            CommercialDocument document, List<PaymentCommand> commands, String mismatchMessage) {
        requirePaymentsPresent(commands);
        requirePaymentTotal(commands, document.getTotal(), mismatchMessage);
        var resolved = commands.stream()
                .map(command -> resolvePayment(document, command))
                .toList();
        requirePaymentTotal(resolved, document.getTotal(), mismatchMessage);
        appendPayments(document, resolved);
        if (document.getPagos().stream().noneMatch(DocumentPayment::isPrincipal)) {
            throw new IllegalArgumentException("se requiere un pago principal");
        }
    }

    private void addPartialPayments(CommercialDocument document, List<PaymentCommand> commands) {
        requirePaymentsPresent(commands);
        var pending = document.getPendingTotal();
        requirePaymentTotalAtMost(commands, pending);
        var resolved = commands.stream()
                .map(command -> resolvePayment(document, command))
                .toList();
        requirePaymentTotalAtMost(resolved, pending);
        appendPayments(document, resolved);
    }

    private void appendPayments(CommercialDocument document, List<PaymentCommand> commands) {
        var position = document.getPagos().size();
        var hasPrincipal = document.getPagos().stream().anyMatch(DocumentPayment::isPrincipal);
        for (var command : commands) {
            var method = paymentMethods.findById(command.metodoPagoId())
                    .filter(PaymentMethod::isActivo)
                    .filter(value -> value.getEmpresaId().equals(
                            organization.currentCompany().getId()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "message.payment_method.active_not_found"));
            var principal = command.principal() && !hasPrincipal;
            document.addPayment(new DocumentPayment(
                    document, method, ++position, command.importe(), principal,
                    command.entregado(), command.cambio(), command.voucherCode(),
                    command.reference(), Instant.now(clock)));
            hasPrincipal = hasPrincipal || principal;
        }
    }

    private static void requirePaymentsPresent(List<PaymentCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("se requiere al menos un pago");
        }
    }

    private static void requirePaymentTotal(
            List<PaymentCommand> commands, BigDecimal expected, String message) {
        var total = commands.stream().map(PaymentCommand::importe)
                .map(Money::euros).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (Money.euros(total).compareTo(expected) != 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requirePaymentTotalAtMost(List<PaymentCommand> commands, BigDecimal maximum) {
        var total = commands.stream().map(PaymentCommand::importe)
                .map(Money::euros).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (Money.euros(total).compareTo(maximum) > 0) {
            throw new IllegalArgumentException("message.document.payment_exceeds_pending_total");
        }
    }

    private PaymentCommand resolvePayment(CommercialDocument document, PaymentCommand command) {
        var method = paymentMethods.findById(command.metodoPagoId())
                .filter(PaymentMethod::isActivo)
                .filter(value -> value.getEmpresaId().equals(
                        organization.currentCompany().getId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "metodo de pago activo no encontrado"));
        requireReferenceIfNeeded(method, command);
        requireCashAmountsOnlyForDrawerMethods(method, command);
        if (!"VALE".equals(method.getNombre())) {
            if (command.voucherCode() != null && !command.voucherCode().isBlank()) {
                throw new IllegalArgumentException("codigo de vale solo permitido con metodo VALE");
            }
            return command;
        }
        if (command.voucherCode() == null || command.voucherCode().isBlank()) {
            throw new IllegalArgumentException("el pago con VALE necesita codigo");
        }
        var result = vouchers.consume(command.voucherCode(), command.importe(), document);
        return new PaymentCommand(
                command.metodoPagoId(), result.consumedAmount(), command.principal(),
                command.entregado(), command.cambio(), command.voucherCode(), command.reference());
    }
    // Consumes vouchers before storing payments so the applied amount is exact.

    private static void requireReferenceIfNeeded(PaymentMethod method, PaymentCommand command) {
        if (method.isRequiereReferencia()
                && (command.reference() == null || command.reference().isBlank())) {
            throw new IllegalArgumentException("message.payment.reference_required");
        }
    }

    private static void requireCashAmountsOnlyForDrawerMethods(PaymentMethod method, PaymentCommand command) {
        if (!method.isAbreCajaRegistradora()
                && (command.entregado() != null || command.cambio() != null)) {
            throw new IllegalArgumentException("message.payment.cash_amounts_only_for_cash_drawer");
        }
    }

    private String nextNumber(CommercialDocument document) {
        var type = document.getTipo();
        var period = DocumentNumbering.period(type, document.getFecha());
        var counter = counters.findByTiendaIdAndTipoAndPeriodo(
                        document.getTiendaId(), type.prefix(), period)
                .orElseGet(() -> new DocumentCounter(
                        document.getTiendaId(), type, document.getFecha()));
        var number = counter.siguiente(
                type, document.getFecha(), organization.currentStore().getCodigoTienda());
        counters.save(counter);
        return number;
    }

    private CommercialDocument find(UUID id) {
        var storeId = organization.currentStore().getId();
        return documents.findById(id)
                .filter(document -> document.getTiendaId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("documento no encontrado"));
    }

    private boolean requiresStock(CommercialDocument document) {
        return DELIVERY_NOTES.contains(document.getTipo());
    }

    private void validateConfirmation(CommercialDocument document) {
        switch (document.getTipo()) {
            case FACTURA_VENTA, RECTIFICATIVA_VENTA -> {
                if (document.getClienteId() == null) {
                    throw new IllegalStateException(
                            "La factura de venta necesita cliente");
                }
                var customer = customers.findById(document.getClienteId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Cliente de factura no encontrado"));
                if (!customer.hasCompleteFiscalData()) {
                    throw new IllegalStateException(
                            "El cliente no tiene datos fiscales completos");
                }
            }
            case ALBARAN_COMPRA, FACTURA_COMPRA, RECTIFICATIVA_COMPRA -> {
                if (document.getProveedorId() == null) {
                    throw new IllegalStateException(
                            "El documento de compra necesita proveedor");
                }
                var supplier = suppliers.findByIdAndCompanyId(
                                document.getProveedorId(),
                                organization.currentCompany().getId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Proveedor de compra no encontrado"));
                if (!supplier.isActive()) {
                    throw new IllegalStateException(
                            "El proveedor de compra esta inactivo");
                }
            }
            default -> {
            }
        }
    }

    private static void requireType(
            DocumentCommand command, EnumSet<CommercialDocumentType> allowedTypes) {
        Objects.requireNonNull(command, "command");
        if (!allowedTypes.contains(command.tipo())) {
            throw new IllegalArgumentException("message.document.invalid_document_type");
        }
    }
}
