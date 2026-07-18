package com.tpverp.backend.control;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentCommand;
import com.tpverp.backend.document.DocumentLineCommand;
import com.tpverp.backend.document.DocumentLineType;
import com.tpverp.backend.document.SaleLineDeletionView;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ControlAlertDetectionService {

    private static final List<CommercialDocumentType> SALES_TYPES = List.of(
            CommercialDocumentType.TICKET,
            CommercialDocumentType.FACTURA_VENTA,
            CommercialDocumentType.ALBARAN_VENTA);

    private final ControlRuleRepository rules;
    private final ControlEventRepository events;
    private final ControlAlertRepository alerts;
    private final ControlAlertHistoryRepository history;
    private final ProductRepository products;
    private final CurrentOrganization organization;
    private final Clock clock;

    public ControlAlertDetectionService(
            ControlRuleRepository rules,
            ControlEventRepository events,
            ControlAlertRepository alerts,
            ControlAlertHistoryRepository history,
            ProductRepository products,
            CurrentOrganization organization,
            Clock clock) {
        this.rules = rules;
        this.events = events;
        this.alerts = alerts;
        this.history = history;
        this.products = products;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional
    public void detectConfirmedDocument(
            CommercialDocument document,
            ManualDiscountSnapshot manualDiscounts,
            UUID terminalId,
            Authentication authentication) {
        if (!SALES_TYPES.contains(document.getTipo())) return;
        var user = organization.currentUser(authentication);
        var now = clock.instant();
        detectManualDiscount(document, manualDiscounts, terminalId, user.getId(), user.getUserName(), now);
        detectProductDiscount(document, manualDiscounts, terminalId, user.getId(), user.getUserName(), now);
        detectInactiveProducts(document, terminalId, user.getId(), user.getUserName(), now);
    }

    @Transactional
    public void detectTicketCancelled(
            CommercialDocument document,
            UUID terminalId,
            Authentication authentication) {
        var user = organization.currentUser(authentication);
        var now = clock.instant();
        var data = new LinkedHashMap<String, Object>();
        data.put("documentType", document.getTipo().name());
        data.put("documentNumber", document.getNumero());
        data.put("reason", document.getMotivoAnulacion());
        data.put("total", document.getTotal());
        for (var rule : activeRules(document.getTiendaId(), ControlAlertType.TICKET_CANCELLED)) {
            emit(rule, "DOCUMENT", document.getId(), document.getId(), document.getNumero(), terminalId,
                    user.getId(), user.getUserName(), now, data);
        }
    }

    @Transactional
    public void detectSaleScreenCleared(
            UUID operationId,
            List<SaleLineDeletionView> deletedLines,
            UUID terminalId,
            Authentication authentication) {
        var storeId = organization.currentStore().getId();
        var user = organization.currentUser(authentication);
        var now = clock.instant();
        var data = new LinkedHashMap<String, Object>();
        data.put("lineCount", deletedLines.size());
        data.put("total", deletedLines.stream().map(SaleLineDeletionView::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        data.put("lines", deletedLines.stream().map(line -> Map.<String, Object>of(
                "productId", line.productId().toString(),
                "code", line.code(),
                "name", line.name(),
                "quantity", line.quantity(),
                "unitPrice", line.unitPrice(),
                "total", line.total())).toList());
        for (var rule : activeRules(storeId, ControlAlertType.SALE_SCREEN_CLEARED)) {
            emit(rule, "SALE_SCREEN", operationId, null, null, terminalId,
                    user.getId(), user.getUserName(), now, data);
        }
    }

    @Transactional
    public void detectConsecutiveLineDeletions(
            UUID saleOperationId,
            int deletionCount,
            List<SaleLineDeletionView> deletedLines,
            UUID terminalId,
            Authentication authentication) {
        var storeId = organization.currentStore().getId();
        var user = organization.currentUser(authentication);
        var now = clock.instant();
        for (var rule : activeRules(storeId, ControlAlertType.CONSECUTIVE_LINE_DELETIONS)) {
            var minimumCount = ControlRuleConfiguration.minimumCount(rule.getConfiguration());
            if (deletionCount < minimumCount) continue;
            var data = new LinkedHashMap<String, Object>();
            data.put("minimumCount", minimumCount);
            data.put("deletionCount", deletionCount);
            data.put("lines", deletedLines.stream().map(line -> Map.<String, Object>of(
                    "productId", line.productId().toString(),
                    "code", line.code(),
                    "name", line.name(),
                    "quantity", line.quantity(),
                    "unitPrice", line.unitPrice(),
                    "total", line.total(),
                    "deletedAt", line.deletedAt().toString())).toList());
            emit(rule, "SALE_LINE_DELETION_SEQUENCE", saleOperationId,
                    null, null, terminalId, user.getId(), user.getUserName(), now, data);
        }
    }

    private void detectManualDiscount(
            CommercialDocument document,
            ManualDiscountSnapshot snapshot,
            UUID terminalId,
            UUID userId,
            String userName,
            java.time.Instant now) {
        var values = snapshot == null ? ManualDiscountSnapshot.from(document) : snapshot;
        for (var rule : activeRules(document.getTiendaId(), ControlAlertType.MANUAL_DISCOUNT_OVER_PERCENT)) {
            var threshold = ControlRuleConfiguration.threshold(rule.getConfiguration());
            var matchingLines = new ArrayList<Map<String, Object>>();
            for (var line : values.lines()) {
                if (line.discountPercent().compareTo(threshold) > 0) {
                    matchingLines.add(Map.of(
                            "position", line.position(),
                            "productId", line.productId().toString(),
                            "discountPercent", line.discountPercent()));
                }
            }
            boolean globalMatches = values.globalDiscountPercent().compareTo(threshold) > 0;
            if (!globalMatches && matchingLines.isEmpty()) continue;
            var data = new LinkedHashMap<String, Object>();
            data.put("documentType", document.getTipo().name());
            data.put("documentNumber", document.getNumero());
            data.put("thresholdPercent", threshold);
            data.put("globalDiscountPercent", values.globalDiscountPercent());
            data.put("matchingLines", matchingLines);
            emit(rule, "DOCUMENT", document.getId(), document.getId(), document.getNumero(), terminalId,
                    userId, userName, now, data);
        }
    }

    private void detectInactiveProducts(
            CommercialDocument document,
            UUID terminalId,
            UUID userId,
            String userName,
            java.time.Instant now) {
        var activeRules = activeRules(document.getTiendaId(), ControlAlertType.INACTIVE_PRODUCT_SOLD);
        if (activeRules.isEmpty()) return;
        var productIds = document.getLineas().stream()
                .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                .map(line -> line.getProductoId()).distinct().toList();
        var inactive = products.findAllByStoreIdAndIdIn(document.getTiendaId(), productIds).stream()
                .filter(product -> !product.isActive()).toList();
        if (inactive.isEmpty()) return;
        var data = new LinkedHashMap<String, Object>();
        data.put("documentType", document.getTipo().name());
        data.put("documentNumber", document.getNumero());
        data.put("products", inactive.stream().map(product -> Map.<String, Object>of(
                "productId", product.getId().toString(),
                "code", productCode(product),
                "name", product.getName())).toList());
        for (var rule : activeRules) {
            emit(rule, "DOCUMENT", document.getId(), document.getId(), document.getNumero(), terminalId,
                    userId, userName, now, data);
        }
    }

    private void detectProductDiscount(
            CommercialDocument document,
            ManualDiscountSnapshot originalSnapshot,
            UUID terminalId,
            UUID userId,
            String userName,
            java.time.Instant now) {
        // Only the original request snapshot can distinguish a manual line discount from
        // promotions, member benefits and other discounts produced by repricing.
        if (originalSnapshot == null) return;
        var discountedLines = originalSnapshot.lines().stream()
                .filter(line -> line.discountPercent().signum() > 0)
                .map(line -> Map.<String, Object>of(
                        "position", line.position(),
                        "productId", line.productId().toString(),
                        "discountPercent", line.discountPercent()))
                .toList();
        if (discountedLines.isEmpty()) return;
        var data = new LinkedHashMap<String, Object>();
        data.put("documentType", document.getTipo().name());
        data.put("documentNumber", document.getNumero());
        data.put("discountedLines", discountedLines);
        for (var rule : activeRules(document.getTiendaId(), ControlAlertType.PRODUCT_DISCOUNT_APPLIED)) {
            emit(rule, "DOCUMENT", document.getId(), document.getId(), document.getNumero(), terminalId,
                    userId, userName, now, data);
        }
    }

    private void emit(
            ControlRule rule,
            String sourceType,
            UUID sourceId,
            UUID documentId,
            String documentNumber,
            UUID terminalId,
            UUID userId,
            String userName,
            java.time.Instant occurredAt,
            Map<String, Object> data) {
        if (events.existsByRuleIdAndSourceTypeAndSourceId(rule.getId(), sourceType, sourceId)) return;
        var event = events.save(new ControlEvent(
                rule.getStoreId(), rule, sourceType, sourceId, documentId, documentNumber, terminalId,
                userId, userName, occurredAt, data));
        var alert = alerts.save(new ControlAlert(event));
        history.save(new ControlAlertHistory(
                alert, null, ControlAlertStatus.NEW, null, userId, occurredAt));
    }

    private List<ControlRule> activeRules(UUID storeId, ControlAlertType type) {
        return rules.findAllByStoreIdAndTypeAndActiveTrue(storeId, type);
    }

    private static String productCode(Product product) {
        for (var value : new String[] {product.getCode(), product.getBarcode(), product.getBarcode2()}) {
            if (value != null && !value.isBlank()) return value;
        }
        return product.getId().toString();
    }

    public record ManualDiscountSnapshot(
            BigDecimal globalDiscountPercent,
            List<ManualLineDiscount> lines) {

        public ManualDiscountSnapshot {
            globalDiscountPercent = globalDiscountPercent == null ? BigDecimal.ZERO : globalDiscountPercent;
            lines = List.copyOf(lines == null ? List.of() : lines);
        }

        public static ManualDiscountSnapshot from(DocumentCommand command) {
            return new ManualDiscountSnapshot(command.descuentoGlobal(), fromCommands(command.lineas()));
        }

        public static ManualDiscountSnapshot from(
                BigDecimal globalDiscount, List<DocumentLineCommand> lines) {
            return new ManualDiscountSnapshot(globalDiscount, fromCommands(lines));
        }

        public static ManualDiscountSnapshot globalOnly(BigDecimal globalDiscount) {
            return new ManualDiscountSnapshot(globalDiscount, List.of());
        }

        public static ManualDiscountSnapshot from(CommercialDocument document) {
            return new ManualDiscountSnapshot(
                    document.getDescuentoGlobal(),
                    document.getLineas().stream()
                            .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
                            .map(line -> new ManualLineDiscount(
                                    line.getPosicion(), line.getProductoId(), line.getDescuento()))
                            .toList());
        }

        private static List<ManualLineDiscount> fromCommands(List<DocumentLineCommand> lines) {
            var result = new ArrayList<ManualLineDiscount>();
            int position = 0;
            for (var line : lines == null ? List.<DocumentLineCommand>of() : lines) {
                position++;
                if (line == null || line.productoId() == null) continue;
                result.add(new ManualLineDiscount(
                        position, line.productoId(),
                        line.descuento() == null ? BigDecimal.ZERO : line.descuento()));
            }
            return result;
        }
    }

    public record ManualLineDiscount(int position, UUID productId, BigDecimal discountPercent) {
    }
}
