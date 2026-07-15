package com.tpverp.backend.document;

import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.MemberLoyaltyService;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.HexFormat;
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
    private final PosCashCheckoutRepository checkouts;
    private final PosCashTicketSnapshot snapshots;
    private final CurrentTerminal currentTerminal;
    private final MemberLoyaltyService memberLoyalty;

    public PosCashService(
            DocumentService documents,
            ProductRepository products,
            StoreTaxRepository taxes,
            WarehouseRepository warehouses,
            PaymentMethodRepository paymentMethods,
            CurrentOrganization organization,
            PosCashCheckoutRepository checkouts,
            PosCashTicketSnapshot snapshots,
            CurrentTerminal currentTerminal,
            MemberLoyaltyService memberLoyalty) {
        this.documents = documents;
        this.products = products;
        this.taxes = taxes;
        this.warehouses = warehouses;
        this.paymentMethods = paymentMethods;
        this.organization = organization;
        this.checkouts = checkouts;
        this.snapshots = snapshots;
        this.currentTerminal = currentTerminal;
        this.memberLoyalty = memberLoyalty;
    }

    @Transactional(readOnly = true)
    public Quote quote(PosCashController.SaleRequest request, Authentication authentication) {
        var ticket = documents.quoteTicket(authoritativeCommand(request), authentication);
        return new Quote(ticket.getTotal());
    }

    @Transactional
    public Result charge(PosCashController.CashRequest request, Authentication authentication) {
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        var terminalId = currentTerminal.terminalId(authentication);
        var userId = requireUser(authentication).getId();
        var requestHash = requestHash(request);
        var now = Instant.now();
        var reserved = PosCashCheckout.reserve(
                UUID.randomUUID(), request.checkoutId(), companyId, storeId, terminalId,
                userId, requestHash, now);
        var inserted = checkouts.reserve(
                reserved.getId(), request.checkoutId(), companyId, storeId, terminalId,
                userId, requestHash, now);
        if (inserted == 0) {
            var existing = checkouts.findScopedForUpdate(
                    request.checkoutId(), companyId, storeId, terminalId, userId)
                    .orElseThrow();
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IllegalStateException("cash_checkout_idempotency_conflict");
            }
            if (!existing.isCompleted()) {
                throw new IllegalStateException("cash_checkout_in_progress");
            }
            return resultFrom(existing);
        }
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
        var printTicket = TicketPrintView.from(ticket);
        reserved.complete(ticket.getId(), ticket.getNumero(), total, received, change,
                snapshots.serialize(printTicket), Instant.now());
        checkouts.save(reserved);
        return new Result(ticket.getId(), ticket.getNumero(), total, received, change, printTicket);
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
            var catalogLine = new DocumentLineCommand(
                    product.getId(), line.quantity(), product.getCode(), product.getName(), null,
                    product.getSalePrice(), line.discount(), product.isTaxesIncluded(),
                    "IVA", tax.getPercentage());
            return memberLoyalty.applyLineBenefit(request.customerId(), catalogLine, product);
        }).toList();
        return new DocumentCommand(
                warehouse.getId(), CommercialDocumentType.TICKET,
                LocalDate.now(ZoneId.of(store.getTimezone())), request.customerId(), null, null,
                BigDecimal.ZERO.setScale(2), true, lines);
    }

    public record Quote(BigDecimal total) {}

    static String requestHash(PosCashController.CashRequest request) {
        var canonical = new StringBuilder("v1|")
                .append(request.sale().customerId()).append('|')
                .append(Money.euros(request.received())).append('|')
                .append(Money.euros(request.quotedTotal()));
        request.sale().lines().forEach(line -> canonical.append('|')
                .append(line.productId()).append(':')
                .append(line.quantity().stripTrailingZeros().toPlainString()).append(':')
                .append(line.discount().stripTrailingZeros().toPlainString()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private Result resultFrom(PosCashCheckout checkout) {
        return new Result(checkout.getDocumentId(), checkout.getTicketNumber(), checkout.getTotal(),
                checkout.getReceived(), checkout.getChange(),
                snapshots.deserialize(checkout.getTicketSnapshot()));
    }

    private static UserAccount requireUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof UserAccount user) {
            return user;
        }
        throw new IllegalStateException("user_required");
    }

    public record Result(
            UUID id,
            String number,
            BigDecimal total,
            BigDecimal received,
            BigDecimal change,
            TicketPrintView printTicket) {}
}
