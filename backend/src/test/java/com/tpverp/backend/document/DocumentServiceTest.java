package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.CustomerRate;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.Supplier;
import com.tpverp.backend.party.SupplierRepository;
import com.tpverp.backend.security.domain.Rol;
import com.tpverp.backend.security.domain.Usuario;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-08T12:00:00Z");

    @Mock
    private DocumentoRepository documentRepository;
    @Mock
    private ContadorDocumentoRepository counterRepository;
    @Mock
    private MetodoPagoRepository paymentMethodRepository;
    @Mock
    private DocumentoRelacionRepository relationRepository;
    @Mock
    private StockDocumentGateway stockGateway;
    @Mock
    private CurrentOrganization currentOrganization;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private SupplierRepository supplierRepository;
    @Mock
    private ConfirmedPurchaseRecorder purchaseRecorder;

    private DocumentService service;
    private Tienda store;
    private Usuario user;

    @BeforeEach
    void setUp() {
        var address = Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
        store = new Tienda(
                new Empresa("B00000000", "Empresa", address),
                "Tienda", address, "hash", "Atlantic/Canary", "EUR", "es-ES");
        var role = new Rol(store, "ADMIN");
        user = new Usuario(store, "ADMIN", "hash", role);
        lenient().when(currentOrganization.currentStore()).thenReturn(store);
        lenient().when(currentOrganization.currentCompany())
                .thenReturn(store.getEmpresa());
        lenient().when(currentOrganization.currentUser(any())).thenReturn(user);
        service = new DocumentService(
                documentRepository,
                counterRepository,
                paymentMethodRepository,
                relationRepository,
                stockGateway,
                currentOrganization,
                customerRepository,
                supplierRepository,
                purchaseRecorder,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void authenticatedUserOverridesClientSuppliedStoreAndUser() {
        when(currentOrganization.currentStore()).thenReturn(store);
        when(currentOrganization.currentUser(any())).thenReturn(user);
        when(documentRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        var created = service.createDeliveryNote(
                command(TipoDocumento.ALBARAN_VENTA),
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "ADMIN", "n/a"));

        assertThat(created.getTiendaId()).isEqualTo(store.getId());
        assertThat(created.getStockUserId()).isEqualTo(user.getId());
    }

    @Test
    void salesInvoiceCannotBeConfirmedWithoutCustomer() {
        var invoice = draft(TipoDocumento.FACTURA_VENTA);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(currentOrganization.currentUser(any())).thenReturn(user);

        assertThatThrownBy(() -> service.confirm(invoice.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cliente");
    }

    @Test
    void salesInvoiceRequiresCompleteCustomerFiscalData() {
        var invoice = draft(TipoDocumento.FACTURA_VENTA);
        var customer = new Customer(
                store.getEmpresa(), "Cliente", DocumentType.NIF, "12345678Z",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        invoice.setParties(customer.getId(), null, null);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(currentOrganization.currentUser(any())).thenReturn(user);

        assertThatThrownBy(() -> service.confirm(invoice.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fiscales");
    }

    @Test
    void confirmsDeliveryNoteWithAnnualNumber() {
        var document = draft(TipoDocumento.ALBARAN_VENTA);
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.save(document)).thenReturn(document);
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(
                document.getTiendaId(), "AV", "2026")).thenReturn(Optional.empty());
        when(stockGateway.confirm(document)).thenReturn(true);
        when(currentOrganization.currentUser(any())).thenReturn(user);

        var confirmed = service.confirm(document.getId(), authentication());

        assertThat(confirmed.getNumero()).isEqualTo("AV-2026-000001");
        assertThat(confirmed.getEstado()).isEqualTo(EstadoDocumento.CONFIRMADO);
        assertThat(confirmed.isOrigenStock()).isTrue();
    }

    @Test
    void ticketRequiresPaymentsToMatchTotal() {
        when(currentOrganization.currentStore()).thenReturn(store);
        when(currentOrganization.currentUser(any())).thenReturn(user);

        assertThatThrownBy(() -> service.createTicket(
                command(TipoDocumento.TICKET),
                List.of(new PaymentCommand(
                        UUID.randomUUID(), new BigDecimal("9.99"), true, null, null)),
                authentication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total");

        verify(documentRepository, never()).save(any());
    }

    @Test
    void createsDirectTicketWithDailyNumberAndMixedPayments() {
        var cash = new MetodoPago(store.getEmpresa().getId(), "EFECTIVO", true);
        var card = new MetodoPago(store.getEmpresa().getId(), "TARJETA", true);
        when(paymentMethodRepository.findById(cash.getId())).thenReturn(Optional.of(cash));
        when(paymentMethodRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(stockGateway.confirm(any())).thenReturn(false);
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentOrganization.currentStore()).thenReturn(store);
        when(currentOrganization.currentUser(any())).thenReturn(user);

        var ticket = service.createTicket(
                command(TipoDocumento.TICKET),
                List.of(
                        new PaymentCommand(cash.getId(), new BigDecimal("5.00"), true,
                                new BigDecimal("5.00"), BigDecimal.ZERO),
                        new PaymentCommand(card.getId(), new BigDecimal("5.00"), false, null, null)),
                authentication());

        assertThat(ticket.getNumero()).isEqualTo("26060800001");
        assertThat(ticket.getEstado()).isEqualTo(EstadoDocumento.CONFIRMADO);
        assertThat(ticket.getPagos()).hasSize(2);
        assertThat(ticket.isOrigenStock()).isFalse();
    }

    @Test
    void cancelsTicketAndReversesAppliedStock() {
        var ticket = draft(TipoDocumento.TICKET);
        ticket.confirm("26060800001", UUID.randomUUID(), NOW, true);
        when(documentRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(documentRepository.save(ticket)).thenReturn(ticket);
        when(stockGateway.cancel(ticket)).thenReturn(true);
        when(currentOrganization.currentUser(any())).thenReturn(user);

        var cancelled = service.cancelTicket(ticket.getId(), authentication(), "ERROR");

        assertThat(cancelled.getEstado()).isEqualTo(EstadoDocumento.ANULADO);
        assertThat(cancelled.getMotivoAnulacion()).isEqualTo("ERROR");
        verify(stockGateway).cancel(ticket);
    }

    @Test
    void invoiceMustBePaidInFull() {
        var invoice = draft(TipoDocumento.FACTURA_VENTA);
        invoice.confirm("FV-2026-000001", UUID.randomUUID(), NOW, false);
        var method = new MetodoPago(
                store.getEmpresa().getId(), "TRANSFERENCIA", false);
        when(documentRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(paymentMethodRepository.findById(method.getId())).thenReturn(Optional.of(method));
        when(documentRepository.save(invoice)).thenReturn(invoice);

        assertThatThrownBy(() -> service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        method.getId(), new BigDecimal("9.99"), true, null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completo");

        var paid = service.payInvoice(
                invoice.getId(),
                List.of(new PaymentCommand(
                        method.getId(), new BigDecimal("10.00"), true, null, null)));

        assertThat(paid.getEstado()).isEqualTo(EstadoDocumento.PAGADO);
    }

    @Test
    void adminEditOfConfirmedDeliveryNoteDoesNotTouchStockOrIdentity() {
        var note = draft(TipoDocumento.ALBARAN_COMPRA);
        note.confirm("AC-2026-000001", UUID.randomUUID(), NOW, true);
        when(documentRepository.findById(note.getId())).thenReturn(Optional.of(note));
        when(documentRepository.save(note)).thenReturn(note);

        var edited = service.adminEditConfirmed(
                note.getId(), BigDecimal.TEN, null, UUID.randomUUID(), lines());

        assertThat(edited.getNumero()).isEqualTo("AC-2026-000001");
        assertThat(edited.getFecha()).isEqualTo(LocalDate.of(2026, 6, 8));
        assertThat(edited.isOrigenStock()).isTrue();
        verify(stockGateway, never()).confirm(any());
        verify(stockGateway, never()).cancel(any());
    }

    @Test
    void confirmedPurchaseDeliveryNoteRecordsSupplierProducts() {
        var supplier = supplier(true);
        var note = purchaseDraft(TipoDocumento.ALBARAN_COMPRA, supplier, true);
        preparePurchaseConfirmation(note, supplier, true);

        service.confirm(note.getId(), authentication());

        verify(purchaseRecorder).record(
                supplier.getId(), note.getFecha(), productIds(note));
    }

    @Test
    void confirmedDirectPurchaseInvoiceRecordsSupplierProducts() {
        var supplier = supplier(true);
        var invoice = purchaseDraft(TipoDocumento.FACTURA_COMPRA, supplier, true);
        preparePurchaseConfirmation(invoice, supplier, true);

        service.confirm(invoice.getId(), authentication());

        verify(purchaseRecorder).record(
                supplier.getId(), invoice.getFecha(), productIds(invoice));
    }

    @Test
    void confirmedNonDirectPurchaseInvoiceDoesNotRecordSupplierProductsAgain() {
        var supplier = supplier(true);
        var invoice = purchaseDraft(TipoDocumento.FACTURA_COMPRA, supplier, false);
        preparePurchaseConfirmation(invoice, supplier, false);

        service.confirm(invoice.getId(), authentication());

        verify(purchaseRecorder, never()).record(any(), any(), any());
    }

    @Test
    void confirmedPurchaseCreditNoteDoesNotRecordSupplierProducts() {
        var supplier = supplier(true);
        var invoice = purchaseDraft(TipoDocumento.RECTIFICATIVA_COMPRA, supplier, true);
        preparePurchaseConfirmation(invoice, supplier, true);

        service.confirm(invoice.getId(), authentication());

        verify(purchaseRecorder, never()).record(any(), any(), any());
    }

    @Test
    void confirmedSalesDocumentDoesNotRecordSupplierProducts() {
        var note = draft(TipoDocumento.ALBARAN_VENTA);
        when(documentRepository.findById(note.getId())).thenReturn(Optional.of(note));
        when(documentRepository.save(note)).thenReturn(note);
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(
                note.getTiendaId(), "AV", "2026")).thenReturn(Optional.empty());
        when(stockGateway.confirm(note)).thenReturn(true);

        service.confirm(note.getId(), authentication());

        verify(purchaseRecorder, never()).record(any(), any(), any());
    }

    @Test
    void purchaseDeliveryNoteRequiresSupplierBeforeNumberingOrStock() {
        var note = draft(TipoDocumento.ALBARAN_COMPRA);
        when(documentRepository.findById(note.getId())).thenReturn(Optional.of(note));

        assertThatThrownBy(() -> service.confirm(note.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("proveedor");

        verify(counterRepository, never())
                .findByTiendaIdAndTipoAndPeriodo(any(), any(), any());
        verify(stockGateway, never()).confirm(any());
        verify(purchaseRecorder, never()).record(any(), any(), any());
    }

    @Test
    void purchaseDeliveryNoteRequiresActiveSupplierBeforeNumberingOrStock() {
        var supplier = supplier(false);
        var note = purchaseDraft(TipoDocumento.ALBARAN_COMPRA, supplier, true);
        when(documentRepository.findById(note.getId())).thenReturn(Optional.of(note));
        when(supplierRepository.findByIdAndCompanyId(
                supplier.getId(), store.getEmpresa().getId()))
                .thenReturn(Optional.of(supplier));

        assertThatThrownBy(() -> service.confirm(note.getId(), authentication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactivo");

        verify(counterRepository, never())
                .findByTiendaIdAndTipoAndPeriodo(any(), any(), any());
        verify(stockGateway, never()).confirm(any());
        verify(purchaseRecorder, never()).record(any(), any(), any());
    }

    private Documento draft(TipoDocumento type) {
        var command = command(type);
        var document = new Documento(
                store.getId(), command.almacenId(), type, command.fecha(),
                user.getId(), command.descuentoGlobal());
        command.lineas().forEach(line -> document.addLine(line.toEntity(document)));
        return document;
    }

    private Documento purchaseDraft(
            TipoDocumento type, Supplier supplier, boolean stockOrigin) {
        var document = draft(type);
        document.setParties(null, supplier.getId(), null);
        document.setStockOrigin(stockOrigin);
        return document;
    }

    private void preparePurchaseConfirmation(
            Documento document, Supplier supplier, boolean appliesStock) {
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        when(documentRepository.save(document)).thenReturn(document);
        when(supplierRepository.findByIdAndCompanyId(
                supplier.getId(), store.getEmpresa().getId()))
                .thenReturn(Optional.of(supplier));
        when(counterRepository.findByTiendaIdAndTipoAndPeriodo(
                document.getTiendaId(),
                document.getTipo().prefix(),
                "2026")).thenReturn(Optional.empty());
        if (appliesStock) {
            when(stockGateway.confirm(document)).thenReturn(true);
        }
    }

    private Supplier supplier(boolean active) {
        var supplier = new Supplier(
                store.getEmpresa(), "Proveedor", null, DocumentType.CIF, "B00000001",
                null, null, null, null);
        if (!active) {
            supplier.deactivate();
        }
        return supplier;
    }

    private List<UUID> productIds(Documento document) {
        return document.getLineas().stream()
                .map(DocumentoLinea::getProductoId)
                .distinct()
                .toList();
    }

    private DocumentCommand command(TipoDocumento type) {
        return new DocumentCommand(
                UUID.randomUUID(),
                type,
                LocalDate.of(2026, 6, 8),
                null,
                null,
                null,
                BigDecimal.ZERO,
                false,
                lines());
    }

    private List<DocumentLineCommand> lines() {
        return List.of(new DocumentLineCommand(
                UUID.randomUUID(), 1, "P-1", "Producto", "VENTA",
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21")));
    }

    private UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken("ADMIN", "n/a");
    }
}
