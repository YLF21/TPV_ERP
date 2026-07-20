package com.tpverp.bridge.globalpayments;

import com.tpverp.bridge.spi.AdapterHealth;
import com.tpverp.bridge.spi.BridgeCapability;
import com.tpverp.bridge.spi.OperationRequest;
import com.tpverp.bridge.spi.OperationResult;
import com.tpverp.bridge.spi.PairingRequest;
import com.tpverp.bridge.spi.TerminalProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only protocol driver. It never contacts Global Payments or moves money. */
public final class SimulatedGlobalPaymentsDriver implements GlobalPaymentsTerminalDriver {
    private static final String STATE_NAMESPACE = "globalpayments-simulated";
    private static final Set<BridgeCapability> CAPABILITIES = Set.of(
            BridgeCapability.PAIR, BridgeCapability.CHARGE, BridgeCapability.QUERY,
            BridgeCapability.VOID, BridgeCapability.REFUND, BridgeCapability.RECEIPT,
            BridgeCapability.RECONCILIATION);

    private final Set<String> pairedTerminals = ConcurrentHashMap.newKeySet();
    private final Map<String, PaymentState> payments = new ConcurrentHashMap<>();
    private final Map<String, OperationResult> operations = new ConcurrentHashMap<>();
    private volatile com.tpverp.bridge.spi.AdapterRuntime runtime = com.tpverp.bridge.spi.AdapterRuntime.unavailable();

    @Override
    public void initialize(com.tpverp.bridge.spi.AdapterRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime);
    }

    @Override
    public String protocol() {
        return "SIMULATED";
    }

    @Override
    public com.tpverp.bridge.spi.TerminalExecutionMode mode() {
        return com.tpverp.bridge.spi.TerminalExecutionMode.SIMULATED;
    }

    @Override
    public Set<String> connectionTypes() {
        return Set.of("SIMULATED");
    }

    @Override
    public boolean supports(TerminalProfile profile) {
        return profile.mode() == com.tpverp.bridge.spi.TerminalExecutionMode.SIMULATED
                && "SIMULATED".equals(profile.connectionType());
    }

    @Override
    public Set<BridgeCapability> capabilities(TerminalProfile profile) {
        return CAPABILITIES;
    }

    @Override
    public AdapterHealth health(TerminalProfile profile) {
        return new AdapterHealth(true, "SIMULATOR_READY", "1.0");
    }

    @Override
    public OperationResult pair(PairingRequest request, TerminalProfile profile) {
        pairedTerminals.add(profile.terminalId());
        runtime.writeState(STATE_NAMESPACE, pairingKey(profile), new byte[] { 1 });
        return success("PAIRED", request.pairingId(), "Emparejamiento simulado completado", null);
    }

    @Override
    public OperationResult operate(OperationRequest request, TerminalProfile profile) {
        return switch (request.command()) {
            case "PAIRING_STATUS" -> pairingStatus(request, profile);
            case "CHARGE" -> charge(request, profile);
            case "QUERY" -> query(request, profile);
            case "VOID" -> voidPayment(request, profile);
            case "REFUND" -> refund(request, profile);
            case "RECEIPT" -> receipt(request, profile);
            case "RECONCILIATION" -> reconcile(request, profile);
            default -> OperationResult.failure("ERROR", "Operacion simulada no soportada");
        };
    }

    private OperationResult pairingStatus(OperationRequest request, TerminalProfile profile) {
        if (!pairedTerminals.contains(profile.terminalId())
                && runtime.readState(STATE_NAMESPACE, pairingKey(profile)).isEmpty()) {
            return OperationResult.failure("PAIRING_NOT_FOUND", "Terminal no emparejado");
        }
        pairedTerminals.add(profile.terminalId());
        return success("PAIRED", profile.terminalId(), "Terminal emparejado", null);
    }

    private OperationResult charge(OperationRequest request, TerminalProfile profile) {
        var outcome = profile.parameters().getOrDefault("simulationOutcome", "APPROVED").toUpperCase(java.util.Locale.ROOT);
        var result = simulatedOutcome(outcome, request);
        saveOperation(profile, request.operationId(), result);
        if (result.approved()) {
            var state = new PaymentState(request.amountMinor(), result);
            savePayment(profile, request.operationId(), state);
            updateApprovedCount(profile, 1);
        }
        return result;
    }

    private OperationResult query(OperationRequest request, TerminalProfile profile) {
        var target = targetOperation(request);
        return operation(profile, target).orElse(
                OperationResult.failure("OPERATION_NOT_FOUND", "Operacion no encontrada"));
    }

    private OperationResult voidPayment(OperationRequest request, TerminalProfile profile) {
        var original = request.originalOperationId();
        var state = original == null ? null : payment(profile, original).orElse(null);
        if (state == null) return OperationResult.failure("OPERATION_NOT_FOUND", "Operacion original no encontrada");
        final OperationResult result;
        synchronized (state) {
            if (!"APPROVED".equals(state.current.code()) || state.refundedMinor != 0) {
                result = OperationResult.failure("DECLINED", "La operacion ya no se puede anular");
            } else {
                result = success("VOIDED", request.operationId(), "Anulacion simulada aprobada", null);
                state.current = result;
                saveOperation(profile, original, result);
                savePayment(profile, original, state);
                updateApprovedCount(profile, -1);
            }
        }
        saveOperation(profile, request.operationId(), result);
        return result;
    }

    private OperationResult refund(OperationRequest request, TerminalProfile profile) {
        var original = request.originalOperationId();
        var state = original == null ? null : payment(profile, original).orElse(null);
        if (state == null) return OperationResult.failure("OPERATION_NOT_FOUND", "Operacion original no encontrada");
        final OperationResult result;
        synchronized (state) {
            var remaining = state.amountMinor - state.refundedMinor;
            if (request.amountMinor() <= 0 || request.amountMinor() > remaining
                    || Set.of("VOIDED", "REFUNDED").contains(state.current.code())) {
                result = OperationResult.failure("DECLINED", "Importe de devolucion no permitido");
            } else {
                state.refundedMinor += request.amountMinor();
                var code = state.refundedMinor == state.amountMinor ? "REFUNDED" : "PARTIALLY_REFUNDED";
                result = success(code, request.operationId(), "Devolucion simulada aprobada", null);
                state.current = result;
                saveOperation(profile, original, result);
                savePayment(profile, original, state);
            }
        }
        saveOperation(profile, request.operationId(), result);
        return result;
    }

    private OperationResult receipt(OperationRequest request, TerminalProfile profile) {
        var target = targetOperation(request);
        var result = operation(profile, target).orElse(null);
        if (result == null) return OperationResult.failure("OPERATION_NOT_FOUND", "Operacion no encontrada");
        var text = "GLOBAL PAYMENTS - SIMULACION\n"
                + "Terminal: " + profile.terminalId() + "\n"
                + "Operacion: " + target + "\n"
                + "Resultado: " + result.code() + "\n"
                + "Referencia: " + result.reference();
        return success("RECEIPT_AVAILABLE", result.reference(), "Recibo simulado disponible", text);
    }

    private OperationResult reconcile(OperationRequest request, TerminalProfile profile) {
        var approved = approvedCount(profile);
        return success("RECONCILED", request.operationId(),
                "Conciliacion simulada completada: " + approved + " operaciones", null);
    }

    private static OperationResult simulatedOutcome(String outcome, OperationRequest request) {
        return switch (outcome) {
            case "APPROVED" -> success("APPROVED", request.operationId(), "Pago simulado aprobado", null);
            case "DECLINED" -> OperationResult.failure("DECLINED", "Pago simulado rechazado");
            case "CANCELLED" -> OperationResult.failure("CANCELLED", "Pago simulado cancelado");
            case "PENDING" -> OperationResult.failure("PENDING", "Pago simulado pendiente");
            case "TIMEOUT" -> OperationResult.failure("TIMEOUT", "Tiempo de espera simulado agotado");
            case "REVIEW_REQUIRED" -> OperationResult.failure("REVIEW_REQUIRED", "Revision simulada requerida");
            default -> OperationResult.failure("ERROR", "Error simulado de Global Payments");
        };
    }

    private static OperationResult success(String code, String seed, String message, String receipt) {
        var digest = digest(seed);
        return new OperationResult(true, code, "GPSIM-" + digest.substring(0, 16),
                digest.substring(16, 22).toUpperCase(java.util.Locale.ROOT), message, receipt);
    }

    private static String targetOperation(OperationRequest request) {
        return request.originalOperationId() == null ? request.operationId() : request.originalOperationId();
    }

    private static String key(TerminalProfile profile, String operationId) {
        return profile.terminalId() + ':' + operationId;
    }

    private static String pairingKey(TerminalProfile profile) {
        return "paired:" + profile.terminalId();
    }

    private java.util.Optional<OperationResult> operation(TerminalProfile profile, String operationId) {
        var cacheKey = key(profile, operationId);
        var cached = operations.get(cacheKey);
        if (cached != null) return java.util.Optional.of(cached);
        return runtime.readState(STATE_NAMESPACE, "operation:" + cacheKey).map(SimulatedGlobalPaymentsDriver::decodeResult)
                .map(result -> { operations.put(cacheKey, result); return result; });
    }

    private void saveOperation(TerminalProfile profile, String operationId, OperationResult result) {
        var cacheKey = key(profile, operationId);
        operations.put(cacheKey, result);
        runtime.writeState(STATE_NAMESPACE, "operation:" + cacheKey, encodeResult(result));
    }

    private java.util.Optional<PaymentState> payment(TerminalProfile profile, String operationId) {
        var cacheKey = key(profile, operationId);
        var cached = payments.get(cacheKey);
        if (cached != null) return java.util.Optional.of(cached);
        return runtime.readState(STATE_NAMESPACE, "payment:" + cacheKey).map(SimulatedGlobalPaymentsDriver::decodePayment)
                .map(state -> { payments.put(cacheKey, state); return state; });
    }

    private void savePayment(TerminalProfile profile, String operationId, PaymentState state) {
        var cacheKey = key(profile, operationId);
        payments.put(cacheKey, state);
        runtime.writeState(STATE_NAMESPACE, "payment:" + cacheKey, encodePayment(state));
    }

    private long approvedCount(TerminalProfile profile) {
        return runtime.readState(STATE_NAMESPACE, "summary:" + profile.terminalId())
                .map(bytes -> java.nio.ByteBuffer.wrap(bytes).getLong()).orElse(0L);
    }

    private synchronized void updateApprovedCount(TerminalProfile profile, long delta) {
        var next = Math.max(0L, approvedCount(profile) + delta);
        runtime.writeState(STATE_NAMESPACE, "summary:" + profile.terminalId(),
                java.nio.ByteBuffer.allocate(Long.BYTES).putLong(next).array());
    }

    private static byte[] encodePayment(PaymentState state) {
        try {
            var output = new java.io.ByteArrayOutputStream();
            try (var data = new java.io.DataOutputStream(output)) {
                data.writeLong(state.amountMinor);
                data.writeLong(state.refundedMinor);
                writeResult(data, state.current);
            }
            return output.toByteArray();
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static PaymentState decodePayment(byte[] bytes) {
        try (var data = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var state = new PaymentState(data.readLong(), null);
            state.refundedMinor = data.readLong();
            state.current = readResult(data);
            if (data.available() != 0) throw new IllegalArgumentException("Unexpected payment state data");
            return state;
        } catch (java.io.IOException | RuntimeException exception) {
            throw new IllegalStateException("Invalid simulated payment state", exception);
        }
    }

    private static byte[] encodeResult(OperationResult result) {
        try {
            var output = new java.io.ByteArrayOutputStream();
            try (var data = new java.io.DataOutputStream(output)) { writeResult(data, result); }
            return output.toByteArray();
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static OperationResult decodeResult(byte[] bytes) {
        try (var data = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var result = readResult(data);
            if (data.available() != 0) throw new IllegalArgumentException("Unexpected operation state data");
            return result;
        } catch (java.io.IOException | RuntimeException exception) {
            throw new IllegalStateException("Invalid simulated operation state", exception);
        }
    }

    private static void writeResult(java.io.DataOutputStream data, OperationResult result) throws java.io.IOException {
        data.writeBoolean(result.approved());
        writeNullable(data, result.code());
        writeNullable(data, result.reference());
        writeNullable(data, result.authorization());
        writeNullable(data, result.message());
        writeNullable(data, result.receiptText());
    }

    private static OperationResult readResult(java.io.DataInputStream data) throws java.io.IOException {
        return new OperationResult(data.readBoolean(), readNullable(data), readNullable(data), readNullable(data),
                readNullable(data), readNullable(data));
    }

    private static void writeNullable(java.io.DataOutputStream data, String value) throws java.io.IOException {
        data.writeBoolean(value != null);
        if (value != null) data.writeUTF(value);
    }

    private static String readNullable(java.io.DataInputStream data) throws java.io.IOException {
        return data.readBoolean() ? data.readUTF() : null;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class PaymentState {
        private final long amountMinor;
        private long refundedMinor;
        private OperationResult current;

        private PaymentState(long amountMinor, OperationResult current) {
            this.amountMinor = amountMinor;
            this.current = current;
        }
    }
}
