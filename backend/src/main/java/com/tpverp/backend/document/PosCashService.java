package com.tpverp.backend.document;

import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosCashService {

    private final DocumentService documents;
    private final ProductRepository products;
    private final StoreTaxRepository taxes;
    private final WarehouseRepository warehouses;
    private final PaymentMethodRepository paymentMethods;
    private final CurrentOrganization organization;

    public PosCashService(
            DocumentService documents,
            ProductRepository products,
            StoreTaxRepository taxes,
            WarehouseRepository warehouses,
            PaymentMethodRepository paymentMethods,
            CurrentOrganization organization) {
        this.documents = documents;
        this.products = products;
        this.taxes = taxes;
        this.warehouses = warehouses;
        this.paymentMethods = paymentMethods;
        this.organization = organization;
    }

    @Transactional(readOnly = true)
    public Quote quote(PosCashController.SaleRequest request, Authentication authentication) {
        var ticket = documents.quoteTicket(authoritativeCommand(request), authentication);
        return new Quote(ticket.getTotal());
    }

    @Transactional
    public Result charge(PosCashController.CashRequest request, Authentication authentication) {
        var command = authoritativeCommand(request.sale());
        var quote = documents.quoteTicket(command, authentication);
        var total = quote.getTotal();
        var received = Money.euros(request.received());
        if (received.compareTo(total) < 0) {
            throw new IllegalArgumentException("El importe recibido no cubre el total");
        }
        if (request.quotedTotal() != null && Money.euros(request.quotedTotal()).compareTo(total) != 0) {
            throw new IllegalStateException("El total de la venta ha cambiado; vuelve a abrir el cobro");
        }
        var cash = paymentMethods.findByEmpresaIdAndNombreAndActivoTrue(
                        organization.currentCompany().getId(), "EFECTIVO")
                .orElseThrow(() -> new IllegalStateException("El metodo EFECTIVO no esta activo"));
        var change = Money.euros(received.subtract(total));
        var ticket = documents.createTicket(
                command,
                List.of(new PaymentCommand(cash.getId(), total, true, received, change)),
                authentication);
        return new Result(ticket.getId(), ticket.getNumero(), total, received, change,
                TicketPrintView.from(ticket));
    }

    @Transactional(readOnly = true)
    DocumentCommand authoritativeCommand(PosCashController.SaleRequest request) {
        var store = organization.currentStore();
        var warehouse = warehouses.findByStoreIdAndPredeterminadoTrue(store.getId())
                .filter(value -> value.isActive())
                .orElseThrow(() -> new IllegalStateException("No hay un almacen predeterminado activo"));
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new IllegalArgumentException("message.document.lines_required");
        }
        var lines = request.lines().stream().map(line -> {
            var product = products.findById(line.productId())
                    .filter(value -> value.getStoreId().equals(store.getId()))
                    .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));
            var tax = taxes.findById(product.getTaxId())
                    .filter(value -> value.getStoreId().equals(store.getId()) && value.isActive())
                    .orElseThrow(() -> new IllegalStateException("El impuesto del producto no esta activo"));
            return new DocumentLineCommand(
                    product.getId(), line.quantity(), product.getCode(), product.getName(), null,
                    product.getSalePrice(), line.discount(), product.isTaxesIncluded(),
                    "IVA", tax.getPercentage());
        }).toList();
        return new DocumentCommand(
                warehouse.getId(), CommercialDocumentType.TICKET,
                LocalDate.now(ZoneId.of(store.getTimezone())), request.customerId(), null, null,
                BigDecimal.ZERO.setScale(2), true, lines);
    }

    public record Quote(BigDecimal total) {}
    public record Result(
            UUID id,
            String number,
            BigDecimal total,
            BigDecimal received,
            BigDecimal change,
            TicketPrintView printTicket) {}
}
