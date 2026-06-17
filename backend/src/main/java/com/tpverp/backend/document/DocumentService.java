package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.SupplierRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {

    private static final EnumSet<TipoDocumento> DELIVERY_NOTES = EnumSet.of(
            TipoDocumento.ALBARAN_VENTA, TipoDocumento.ALBARAN_COMPRA);
    private static final EnumSet<TipoDocumento> INVOICES = EnumSet.of(
            TipoDocumento.FACTURA_VENTA, TipoDocumento.FACTURA_COMPRA,
            TipoDocumento.RECTIFICATIVA_VENTA, TipoDocumento.RECTIFICATIVA_COMPRA);

    private final DocumentoRepository documents;
    private final ContadorDocumentoRepository counters;
    private final MetodoPagoRepository paymentMethods;
    private final DocumentoRelacionRepository relations;
    private final StockDocumentGateway stockGateway;
    private final CurrentOrganization organization;
    private final CustomerRepository customers;
    private final SupplierRepository suppliers;
    private final ConfirmedPurchaseRecorder purchaseRecorder;
    private final DocumentFiscalIntegration fiscalIntegration;
    private final VoucherService vouchers;
    private final Clock clock;

    public DocumentService(
            DocumentoRepository documents,
            ContadorDocumentoRepository counters,
            MetodoPagoRepository paymentMethods,
            DocumentoRelacionRepository relations,
            StockDocumentGateway stockGateway,
            CurrentOrganization organization,
            CustomerRepository customers,
            SupplierRepository suppliers,
            ConfirmedPurchaseRecorder purchaseRecorder,
            DocumentFiscalIntegration fiscalIntegration,
            VoucherService vouchers,
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
        this.clock = clock;
    }

    @Transactional
    public Documento createDeliveryNote(
            DocumentCommand command, Authentication authentication) {
        requireType(command, DELIVERY_NOTES);
        return documents.save(createDraft(command, authentication));
    }

    @Transactional(readOnly = true)
    public List<Documento> listDeliveryNotes() {
        return documents.findAllByTiendaIdAndTipoInOrderByFechaDesc(
                organization.currentStore().getId(), DELIVERY_NOTES);
    }

    // Confirma, numera y registra stock/compra en una unica transaccion.
    @Transactional
    public Documento confirm(UUID id, Authentication authentication) {
        var document = find(id);
        var userId = organization.currentUser(authentication).getId();
        validateConfirmation(document);
        // La confirmacion reinicia origenStock; esta marca debe leerse antes.
        boolean recordsPurchase = document.getTipo() == TipoDocumento.ALBARAN_COMPRA
                || (document.getTipo() == TipoDocumento.FACTURA_COMPRA
                && document.isOrigenStock());
        var requiresStock = requiresStock(document) || document.isOrigenStock();
        document.confirm(nextNumber(document), userId, Instant.now(clock), false);
        document.setStockOrigin(requiresStock && stockGateway.confirm(document));
        if (recordsPurchase) {
            purchaseRecorder.record(
                    document.getProveedorId(),
                    document.getFecha(),
                    document.getLineas().stream()
                            .map(DocumentoLinea::getProductoId)
                            .distinct()
                            .toList());
        }
        var saved = documents.save(document);
        fiscalIntegration.registerAlta(saved, false);
        return saved;
    }

    // Crea y confirma el ticket en una sola transacción.
    @Transactional
    public Documento createTicket(
            DocumentCommand command,
            List<PaymentCommand> payments,
            Authentication authentication) {
        if (command.tipo() != TipoDocumento.TICKET) {
            throw new IllegalArgumentException("tipo de ticket no válido");
        }
        var ticket = createDraft(command, authentication);
        if (ticket.getTotal().signum() >= 0) {
            requirePaymentsPresent(payments);
            requirePaymentTotal(payments, ticket.getTotal(), "los pagos deben cuadrar con el total del ticket");
        }
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
        if (saved.getTotal().signum() < 0) {
            vouchers.issueFromNegativeTicket(saved);
        }
        fiscalIntegration.registerAlta(saved, false);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Documento> listTickets() {
        return documents.findAllByTiendaIdAndTipoInOrderByFechaDesc(
                organization.currentStore().getId(), List.of(TipoDocumento.TICKET));
    }

    // Anula el ticket y solicita inversión de stock solo si se aplicó originalmente.
    @Transactional
    public Documento cancelTicket(
            UUID id, Authentication authentication, String reason) {
        var ticket = find(id);
        if (ticket.getTipo() != TipoDocumento.TICKET) {
            throw new IllegalArgumentException("el documento no es un ticket");
        }
        if (relations.existsByOrigen_IdAndTipo(
                ticket.getId(), TipoRelacionDocumento.FACTURA_DE)) {
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
        return saved;
    }

    // Convierte un ticket confirmado en factura F3 sin duplicar stock ni pagos.
    @Transactional
    public Documento convertTicketToInvoice(
            UUID ticketId, UUID customerId, Authentication authentication) {
        var ticket = find(ticketId);
        if (ticket.getTipo() != TipoDocumento.TICKET
                || ticket.getEstado() != EstadoDocumento.CONFIRMADO) {
            throw new IllegalStateException("solo se puede facturar un ticket confirmado");
        }
        if (relations.existsByOrigen_IdAndTipo(ticket.getId(), TipoRelacionDocumento.FACTURA_DE)) {
            throw new IllegalStateException("el ticket ya esta facturado");
        }
        var invoice = invoiceFromTicket(ticket, customerId, authentication);
        validateConfirmation(invoice);
        invoice.confirm(nextNumber(invoice), organization.currentUser(authentication).getId(),
                Instant.now(clock), false);
        var saved = documents.save(invoice);
        relations.save(new DocumentoRelacion(saved, ticket, TipoRelacionDocumento.FACTURA_DE));
        fiscalIntegration.registerAlta(saved, true);
        return saved;
    }

    @Transactional
    public Documento createInvoice(
            DocumentCommand command, Authentication authentication) {
        requireType(command, INVOICES);
        return documents.save(createDraft(command, authentication));
    }

    @Transactional(readOnly = true)
    public List<Documento> listInvoices() {
        return documents.findAllByTiendaIdAndTipoInOrderByFechaDesc(
                organization.currentStore().getId(), INVOICES);
    }

    // Registra pagos únicamente cuando cubren exactamente el total pendiente.
    @Transactional
    public Documento payInvoice(UUID id, List<PaymentCommand> payments) {
        var invoice = find(id);
        if (!INVOICES.contains(invoice.getTipo())) {
            throw new IllegalArgumentException("el documento no es una factura");
        }
        addPayments(invoice, payments, "la factura debe pagarse por completo");
        invoice.markPaid();
        return documents.save(invoice);
    }

    // Edita excepcionalmente ticket o albarán confirmado sin invocar stock ni auditoría.
    @Transactional
    public Documento adminEditConfirmed(
            UUID id,
            BigDecimal globalDiscount,
            UUID customerId,
            UUID supplierId,
            List<DocumentLineCommand> lines) {
        var document = find(id);
        document.adminReplace(
                globalDiscount, customerId, supplierId, List.copyOf(lines));
        return documents.save(document);
    }

    // Relaciona explícitamente una factura con su documento de origen.
    @Transactional
    public Documento relate(UUID invoiceId, UUID originId, TipoRelacionDocumento type) {
        var invoice = find(invoiceId);
        if (!INVOICES.contains(invoice.getTipo())) {
            throw new IllegalStateException("solo una factura puede relacionarse con origen");
        }
        Objects.requireNonNull(type, "tipoRelacion");
        var origin = find(originId);
        validateRelationOrigin(type, origin);
        relations.save(new DocumentoRelacion(invoice, origin, type));
        return invoice;
    }

    private static void validateRelationOrigin(TipoRelacionDocumento type, Documento origin) {
        if (type == TipoRelacionDocumento.FACTURA_DE && INVOICES.contains(origin.getTipo())) {
            throw new IllegalStateException("origen incompatible para factura agrupada");
        }
    }

    private Documento createDraft(
            DocumentCommand command, Authentication authentication) {
        Objects.requireNonNull(command, "command");
        if (command.lineas() == null || command.lineas().isEmpty()) {
            throw new IllegalArgumentException("el documento debe tener líneas");
        }
        var store = organization.currentStore();
        var user = organization.currentUser(authentication);
        var document = new Documento(
                store.getId(), command.almacenId(), command.tipo(), command.fecha(),
                user.getId(), command.descuentoGlobal());
        document.setParties(
                command.clienteId(), command.proveedorId(), command.numeroExterno());
        document.setStockOrigin(
                command.directo()
                        || command.tipo() == TipoDocumento.TICKET
                        || DELIVERY_NOTES.contains(command.tipo()));
        for (var line : command.lineas()) {
            document.addLine(line.toEntity(document));
        }
        return document;
    }

    private Documento invoiceFromTicket(
            Documento ticket, UUID customerId, Authentication authentication) {
        var invoice = new Documento(
                ticket.getTiendaId(), ticket.getAlmacenId(), TipoDocumento.FACTURA_VENTA,
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
            Documento document, List<PaymentCommand> commands, String mismatchMessage) {
        requirePaymentsPresent(commands);
        requirePaymentTotal(commands, document.getTotal(), mismatchMessage);
        var resolved = commands.stream()
                .map(command -> resolvePayment(document, command))
                .toList();
        requirePaymentTotal(resolved, document.getTotal(), mismatchMessage);
        for (var index = 0; index < resolved.size(); index++) {
            var command = resolved.get(index);
            var method = paymentMethods.findById(command.metodoPagoId())
                    .filter(MetodoPago::isActivo)
                    .filter(value -> value.getEmpresaId().equals(
                            organization.currentCompany().getId()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "método de pago activo no encontrado"));
            document.addPayment(new DocumentoPago(
                    document, method, index + 1, command.importe(), command.principal(),
                    command.entregado(), command.cambio(), command.voucherCode(), Instant.now(clock)));
        }
        if (document.getPagos().stream().noneMatch(DocumentoPago::isPrincipal)) {
            throw new IllegalArgumentException("se requiere un pago principal");
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

    private PaymentCommand resolvePayment(Documento document, PaymentCommand command) {
        var method = paymentMethods.findById(command.metodoPagoId())
                .filter(MetodoPago::isActivo)
                .filter(value -> value.getEmpresaId().equals(
                        organization.currentCompany().getId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "metodo de pago activo no encontrado"));
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
                command.entregado(), command.cambio(), command.voucherCode());
    }
    // Consume vales antes de registrar pagos para guardar el importe real aplicado.

    private String nextNumber(Documento document) {
        var type = document.getTipo();
        var period = DocumentNumbering.period(type, document.getFecha());
        var counter = counters.findByTiendaIdAndTipoAndPeriodo(
                        document.getTiendaId(), type.prefix(), period)
                .orElseGet(() -> new ContadorDocumento(
                        document.getTiendaId(), type, document.getFecha()));
        var number = counter.siguiente(
                type, document.getFecha(), organization.currentStore().getCodigoTienda());
        counters.save(counter);
        return number;
    }

    private Documento find(UUID id) {
        var storeId = organization.currentStore().getId();
        return documents.findById(id)
                .filter(document -> document.getTiendaId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("documento no encontrado"));
    }

    private boolean requiresStock(Documento document) {
        return DELIVERY_NOTES.contains(document.getTipo());
    }

    private void validateConfirmation(Documento document) {
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
            DocumentCommand command, EnumSet<TipoDocumento> allowedTypes) {
        Objects.requireNonNull(command, "command");
        if (!allowedTypes.contains(command.tipo())) {
            throw new IllegalArgumentException("tipo documental no válido");
        }
    }
}
