package com.tpverp.backend.terminal;

import com.tpverp.backend.terminal.bridge.BridgeOperationRequest;
import com.tpverp.backend.terminal.bridge.BridgeOperationResult;
import com.tpverp.backend.terminal.bridge.BridgePairingRequest;
import com.tpverp.backend.terminal.bridge.PaymentTerminalBridgeClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class BridgeLivePaymentTerminalGateway implements CardTerminalGateway {
    private final PaymentTerminalProvider provider;
    private final PaymentTerminalBridgeClient bridge;

    public BridgeLivePaymentTerminalGateway(PaymentTerminalProvider provider, PaymentTerminalBridgeClient bridge) {
        if (provider == null || provider == PaymentTerminalProvider.NONE)
            throw new IllegalArgumentException("A physical payment provider is required by the LIVE bridge");
        this.provider = provider;
        this.bridge = Objects.requireNonNull(bridge);
    }

    @Override
    public boolean supports(PaymentTerminalProvider candidate, boolean testMode) {
        return candidate == provider && !testMode;
    }

    @Override
    public Set<PaymentTerminalCapability> capabilities() {
        var result = EnumSet.noneOf(PaymentTerminalCapability.class);
        for (var capability : bridge.capabilities(provider.name(), "LIVE")) {
            switch (capability) {
                case "HEALTH" -> result.add(PaymentTerminalCapability.CONNECTION_TEST);
                case "PAIR" -> result.add(PaymentTerminalCapability.PAIRING);
                case "CHARGE" -> result.add(PaymentTerminalCapability.CHARGE);
                case "QUERY" -> result.add(PaymentTerminalCapability.QUERY);
                case "VOID" -> result.add(PaymentTerminalCapability.VOID);
                case "REFUND" -> result.add(PaymentTerminalCapability.REFUND);
                case "RECEIPT" -> result.add(PaymentTerminalCapability.RECEIPT);
                case "RECONCILIATION" -> result.add(PaymentTerminalCapability.RECONCILIATION);
                default -> { }
            }
        }
        return Set.copyOf(result);
    }

    @Override
    public CardTerminalResult testConnection(CardTerminalConfiguration configuration) {
        var diagnostic = configurationDiagnostic(configuration);
        if (diagnostic != null) return new CardTerminalResult(PaymentTerminalOperationStatus.ERROR, null, null, diagnostic);
        var health = bridge.health();
        var ready = health.available() && capabilities().contains(PaymentTerminalCapability.CHARGE);
        return new CardTerminalResult(ready ? PaymentTerminalOperationStatus.APPROVED : PaymentTerminalOperationStatus.ERROR,
                ready ? safe(health.version(), 128) : null, null,
                ready ? "Puente local " + providerName() + " disponible"
                        : "Puente local " + providerName() + " no disponible: " + safeMessage(health.code()));
    }

    @Override
    public CardTerminalResult charge(CardTerminalRequest request, CardTerminalConfiguration configuration) {
        var diagnostic = configurationDiagnostic(configuration);
        if (diagnostic != null) return new CardTerminalResult(PaymentTerminalOperationStatus.ERROR, null, null, diagnostic);
        if (request.provider() != provider || !request.terminalId().equals(configuration.terminalId())) {
            return new CardTerminalResult(PaymentTerminalOperationStatus.ERROR, null, null, "Solicitud incompatible con el datafono configurado");
        }
        var result = charge(new PaymentTerminalChargeCommand(request.checkoutId(), request.amount()), context(configuration, request.checkoutId()));
        return new CardTerminalResult(result.status(), result.reference(), result.authorization(), result.message());
    }

    @Override
    public PaymentTerminalResult pair(PaymentTerminalPairCommand command, PaymentTerminalGatewayContext context) {
        validate(context);
        var result = bridge.pair(new BridgePairingRequest(provider.name(), context.terminalId().toString(), "LIVE",
                command.pairingId().toString(), context.idempotencyKey(), context.configurationReference(),
                context.configurationVersion(), context.parameters()));
        return normalize(result, "PAIR");
    }

    @Override
    public PaymentTerminalResult pairingStatus(PaymentTerminalPairCommand command, PaymentTerminalGatewayContext context) {
        validate(context);
        return normalize(bridge.operate(request(context, command.pairingId(), "PAIRING_STATUS", 0L,
                null, command.pairingId().toString())), "PAIRING_STATUS");
    }

    @Override
    public PaymentTerminalResult charge(PaymentTerminalChargeCommand command, PaymentTerminalGatewayContext context) {
        validate(context);
        return normalize(bridge.operate(request(context, command.operationId(), "CHARGE", minor(command.amount()), null, null)), "CHARGE");
    }

    @Override
    public PaymentTerminalResult query(PaymentTerminalQueryCommand command, PaymentTerminalGatewayContext context) {
        validate(context);
        return normalize(bridge.operate(request(context, command.operationId(), "QUERY", 0L, null, command.reference())), "QUERY");
    }

    @Override
    public PaymentTerminalResult voidAuthorization(PaymentTerminalVoidCommand command, PaymentTerminalGatewayContext context) {
        validate(context);
        return normalize(bridge.operate(request(context, command.operationId(), "VOID", 0L,
                command.originalOperationId(), command.reference())), "VOID");
    }

    @Override
    public PaymentTerminalResult refund(PaymentTerminalRefundCommand command, PaymentTerminalGatewayContext context) {
        validate(context);
        return normalize(bridge.operate(request(context, command.operationId(), "REFUND", minor(command.amount()),
                command.originalOperationId(), command.reference())), "REFUND");
    }

    @Override
    public PaymentTerminalReceipt receipt(PaymentTerminalReceiptCommand command, PaymentTerminalGatewayContext context) {
        validate(context);
        var bridgeResult = bridge.operate(request(context, command.operationId(), "RECEIPT", 0L, null, command.reference()));
        var result = normalize(bridgeResult, "RECEIPT");
        if (result.status() != PaymentTerminalOperationStatus.APPROVED || bridgeResult.receiptText() == null) {
            return new PaymentTerminalReceipt(result.status(), result.code(), result.message());
        }
        return new PaymentTerminalReceipt(PaymentTerminalOperationStatus.APPROVED, "RECEIPT_AVAILABLE",
                PaymentTerminalSensitiveData.mask(bridgeResult.receiptText()));
    }

    @Override
    public PaymentTerminalResult reconcile(PaymentTerminalReconciliationCommand command, PaymentTerminalGatewayContext context) {
        validate(context);
        return normalize(bridge.operate(request(context, command.reconciliationId(), "RECONCILIATION", 0L, null, null)), "RECONCILIATION");
    }

    private BridgeOperationRequest request(PaymentTerminalGatewayContext context, UUID operationId, String command,
            long amountMinor, UUID originalOperationId, String reference) {
        return new BridgeOperationRequest(provider.name(), context.terminalId().toString(), "LIVE", operationId.toString(),
                context.idempotencyKey(), command, amountMinor, context.currency(),
                originalOperationId == null ? null : originalOperationId.toString(), reference,
                context.configurationReference(), context.configurationVersion(), context.parameters());
    }

    private PaymentTerminalResult normalize(BridgeOperationResult result, String command) {
        var code = normalizeCode(result.code());
        var status = switch (code) {
            case "APPROVED", "PAIRED", "RECEIPT_AVAILABLE", "RECONCILED" -> PaymentTerminalOperationStatus.APPROVED;
            case "DECLINED" -> PaymentTerminalOperationStatus.DECLINED;
            case "CANCELLED", "VOIDED" -> PaymentTerminalOperationStatus.CANCELLED;
            case "REFUNDED" -> PaymentTerminalOperationStatus.REFUNDED;
            case "PARTIALLY_REFUNDED" -> PaymentTerminalOperationStatus.PARTIALLY_REFUNDED;
            case "PENDING" -> PaymentTerminalOperationStatus.PENDING;
            case "TIMEOUT", "BRIDGE_TIMEOUT", "BRIDGE_UNAVAILABLE", "BRIDGE_HTTP_ERROR" -> PaymentTerminalOperationStatus.TIMEOUT;
            case "REVIEW_REQUIRED", "INVALID_RESPONSE" -> PaymentTerminalOperationStatus.REVIEW_REQUIRED;
            case "OPERATION_NOT_FOUND" -> "QUERY".equals(command)
                    ? PaymentTerminalOperationStatus.REVIEW_REQUIRED : PaymentTerminalOperationStatus.ERROR;
            default -> PaymentTerminalOperationStatus.ERROR;
        };
        var finalOutcome = switch (status) {
            case APPROVED, DECLINED, CANCELLED, REFUNDED, PARTIALLY_REFUNDED, REVIEW_REQUIRED -> true;
            case PENDING, TIMEOUT -> false;
            case ERROR -> !Set.of("BRIDGE_UNAVAILABLE", "BRIDGE_HTTP_ERROR").contains(code);
            default -> true;
        };
        var normalized = new PaymentTerminalResult(status, code, safe(result.reference(), 128),
                safe(result.authorization(), 64), result.message() == null ? defaultMessage(code) : safeMessage(result.message()), finalOutcome);
        return PaymentTerminalSensitiveData.safe(normalized);
    }

    private void validate(PaymentTerminalGatewayContext context) {
        if (context.provider() != provider) throw new IllegalArgumentException("provider mismatch");
        if (context.mode() != PaymentTerminalMode.LIVE) throw new IllegalArgumentException("LIVE mode required");
    }

    private String configurationDiagnostic(CardTerminalConfiguration configuration) {
        if (!configuration.enabled()) return "Configuracion de datafono desactivada";
        if (configuration.mode() != PaymentCardMode.INTEGRATED) return "El datafono requiere modo integrado";
        if (configuration.provider() != provider) return "Proveedor incompatible";
        if (configuration.testMode()) return "El conector LIVE no admite modo simulado";
        return null;
    }

    private PaymentTerminalGatewayContext context(CardTerminalConfiguration configuration, UUID operationId) {
        return new PaymentTerminalGatewayContext(configuration.terminalId(), provider, PaymentTerminalMode.LIVE,
                "EUR", operationId.toString(), configuration.configurationReference(), configuration.configurationVersion(),
                configuration.configurationHash(), configuration.parameters());
    }

    private static long minor(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    private static String normalizeCode(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,64}")) return "INVALID_RESPONSE";
        return value.toUpperCase(Locale.ROOT);
    }

    private static String safe(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        var masked = PaymentTerminalSensitiveData.mask(value.trim());
        return masked.length() <= maximum ? masked : masked.substring(0, maximum);
    }

    private static String safeMessage(String value) {
        var masked = PaymentTerminalSensitiveData.mask(Objects.requireNonNullElse(value, "Error del puente local").trim());
        return masked.length() <= 512 ? masked : masked.substring(0, 512);
    }

    private static String defaultMessage(String code) {
        return switch (code) {
            case "SDK_NOT_INSTALLED" -> "SDK oficial no instalado en el puente local";
            case "BRIDGE_TIMEOUT" -> "Tiempo de espera agotado; consulte el estado antes de repetir";
            case "BRIDGE_UNAVAILABLE", "BRIDGE_HTTP_ERROR" -> "Puente local no disponible; consulte el estado antes de repetir";
            case "INVALID_RESPONSE" -> "Respuesta no valida del puente local; requiere revision";
            default -> "Resultado del proveedor: " + code;
        };
    }

    private String providerName() {
        return switch (provider) {
            case REDSYS_TPV_PC -> "Redsys TPV-PC";
            case PAYTEF -> "PAYTEF";
            case PAYCOMET -> "PAYCOMET";
            case GLOBAL_PAYMENTS -> "Global Payments";
            case NONE -> throw new IllegalStateException("Proveedor no configurado");
        };
    }
}
