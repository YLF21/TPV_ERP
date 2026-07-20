package com.tpverp.bridge.app;

import com.tpverp.bridge.spi.BridgeCapability;
import com.tpverp.bridge.spi.OperationRequest;
import com.tpverp.bridge.spi.OperationResult;
import com.tpverp.bridge.spi.PairingRequest;
import com.tpverp.bridge.spi.TerminalProfile;
import com.tpverp.bridge.spi.SensitiveParameterName;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class BridgeService {
    public static final String VERSION = "0.1.0";
    private static final Set<String> COMMANDS = Set.of(
            "PAIRING_STATUS", "CHARGE", "QUERY", "VOID", "REFUND", "RECEIPT", "RECONCILIATION");
    private final TerminalProfileRegistry profiles;
    private final AdapterRegistry adapters;
    private final FileIdempotencyStore idempotency;

    public BridgeService(TerminalProfileRegistry profiles, AdapterRegistry adapters, FileIdempotencyStore idempotency) {
        this.profiles = profiles;
        this.adapters = adapters;
        this.idempotency = idempotency;
    }

    public BridgeHealthResult health() {
        var available = profiles.forProvider(null, null).stream().anyMatch(profile -> {
            try {
                return adapters.required(profile).health(profile).available();
            } catch (RuntimeException exception) {
                return false;
            }
        });
        return new BridgeHealthResult(available, available ? "OK" : "SDK_NOT_INSTALLED", VERSION);
    }

    public Set<BridgeCapability> capabilities(String provider, com.tpverp.bridge.spi.TerminalExecutionMode mode) {
        if (mode == null) return Set.of();
        var selected = profiles.forProvider(provider, mode);
        if (selected.isEmpty()) return Set.of();
        EnumSet<BridgeCapability> intersection = null;
        for (var profile : selected) {
            final EnumSet<BridgeCapability> current;
            try {
                var adapter = adapters.required(profile);
                if (!adapter.health(profile).available()) return Set.of();
                var advertised = adapter.capabilities(profile);
                current = advertised.isEmpty()
                        ? EnumSet.noneOf(BridgeCapability.class) : EnumSet.copyOf(advertised);
                current.add(BridgeCapability.HEALTH);
            } catch (RuntimeException exception) {
                return Set.of();
            }
            if (intersection == null) intersection = current; else intersection.retainAll(current);
        }
        return intersection == null ? Set.of() : Set.copyOf(intersection);
    }

    public java.util.List<com.tpverp.bridge.spi.AdapterManifest> adapters() {
        return adapters.manifests();
    }

    public OperationResult pair(PairingRequest request) {
        try {
            validatePairing(request);
            var profile = profiles.required(request.terminalId(), request.provider(), request.mode());
            var adapter = adapters.required(profile);
            if (!adapter.capabilities(profile).contains(BridgeCapability.PAIR)) return unsupported();
            return idempotency.execute(scope(profile, "PAIR"), request.idempotencyKey(), OperationFingerprint.of(request),
                    () -> SafeResultPolicy.normalize("PAIR", adapter.pair(request, profile)));
        } catch (AdapterRegistry.AdapterNotInstalledException exception) {
            return OperationResult.failure("SDK_NOT_INSTALLED", "Adaptador no instalado");
        } catch (IllegalArgumentException exception) {
            return OperationResult.failure("ERROR", "Solicitud de emparejamiento no válida");
        }
    }

    public OperationResult operate(OperationRequest request) {
        try {
            validateOperation(request);
            var profile = profiles.required(request.terminalId(), request.provider(), request.mode());
            var adapter = adapters.required(profile);
            if (!adapter.capabilities(profile).contains(capability(request.command()))) return unsupported();
            return idempotency.execute(scope(profile, request.command()), request.idempotencyKey(), OperationFingerprint.of(request),
                    () -> SafeResultPolicy.normalize(request.command(), adapter.operate(request, profile)));
        } catch (AdapterRegistry.AdapterNotInstalledException exception) {
            return OperationResult.failure("SDK_NOT_INSTALLED", "Adaptador no instalado");
        } catch (IllegalArgumentException exception) {
            return OperationResult.failure("ERROR", "Solicitud de operación no válida");
        }
    }

    private static void validatePairing(PairingRequest request) {
        if (request == null) throw new IllegalArgumentException("request");
        identifier(request.provider(), 32);
        identifier(request.terminalId(), 64);
        if (request.mode() == null) throw new IllegalArgumentException("mode");
        identifier(request.pairingId(), 128);
        text(request.idempotencyKey(), 128);
        optional(request.configurationReference(), 128);
        if (request.configurationVersion() < 0) throw new IllegalArgumentException("version");
        parameters(request.parameters());
    }

    private static void validateOperation(OperationRequest request) {
        if (request == null) throw new IllegalArgumentException("request");
        identifier(request.provider(), 32);
        identifier(request.terminalId(), 64);
        if (request.mode() == null) throw new IllegalArgumentException("mode");
        identifier(request.operationId(), 128);
        text(request.idempotencyKey(), 128);
        if (!COMMANDS.contains(request.command())) throw new IllegalArgumentException("command");
        if (request.amountMinor() < 0 || (Set.of("CHARGE", "REFUND").contains(request.command()) && request.amountMinor() == 0)) {
            throw new IllegalArgumentException("amount");
        }
        if (!"EUR".equals(request.currency())) throw new IllegalArgumentException("currency");
        optionalIdentifier(request.originalOperationId(), 128);
        optional(request.reference(), 256);
        optional(request.configurationReference(), 128);
        if (request.configurationVersion() < 0) throw new IllegalArgumentException("version");
        parameters(request.parameters());
    }

    private static void parameters(Map<String, String> values) {
        var source = values == null ? Map.<String, String>of() : values;
        if (source.size() > 32) throw new IllegalArgumentException("parameters");
        source.forEach((key, value) -> {
            text(key, 64);
            text(value, 256);
            if (SensitiveParameterName.isSensitive(key)) throw new IllegalArgumentException("sensitive parameter");
        });
    }

    private static BridgeCapability capability(String command) {
        return switch (command) {
            case "PAIRING_STATUS" -> BridgeCapability.PAIR;
            case "CHARGE" -> BridgeCapability.CHARGE;
            case "QUERY" -> BridgeCapability.QUERY;
            case "VOID" -> BridgeCapability.VOID;
            case "REFUND" -> BridgeCapability.REFUND;
            case "RECEIPT" -> BridgeCapability.RECEIPT;
            case "RECONCILIATION" -> BridgeCapability.RECONCILIATION;
            default -> throw new IllegalArgumentException("command");
        };
    }

    private static String scope(TerminalProfile profile, String command) {
        return profile.provider() + ':' + profile.terminalId() + ':' + command;
    }

    private static OperationResult unsupported() {
        return OperationResult.failure("ERROR", "Operación no soportada por el datáfono");
    }

    private static void identifier(String value, int maximum) {
        text(value, maximum);
        if (!value.matches("[A-Za-z0-9._:-]+")) throw new IllegalArgumentException("identifier");
    }

    private static void optionalIdentifier(String value, int maximum) {
        if (value != null) identifier(value, maximum);
    }

    private static void optional(String value, int maximum) {
        if (value != null) text(value, maximum);
    }

    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("text");
    }
}
