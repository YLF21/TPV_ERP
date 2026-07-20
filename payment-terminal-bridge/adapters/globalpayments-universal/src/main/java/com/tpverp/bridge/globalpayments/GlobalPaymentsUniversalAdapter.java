package com.tpverp.bridge.globalpayments;

import com.tpverp.bridge.spi.AdapterHealth;
import com.tpverp.bridge.spi.AdapterManifest;
import com.tpverp.bridge.spi.BridgeCapability;
import com.tpverp.bridge.spi.OperationRequest;
import com.tpverp.bridge.spi.OperationResult;
import com.tpverp.bridge.spi.PairingRequest;
import com.tpverp.bridge.spi.PaymentTerminalAdapter;
import com.tpverp.bridge.spi.TerminalProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Global Payments entry point independent from a physical terminal model.
 * A profile selects a protocol driver explicitly or asks for unambiguous AUTO
 * discovery. Actual vendor SDKs remain isolated in driver JARs.
 */
public final class GlobalPaymentsUniversalAdapter implements PaymentTerminalAdapter {
    public static final String ADAPTER_ID = "globalpayments-universal";
    public static final String PROVIDER = "GLOBAL_PAYMENTS";
    private static final String AUTO = "AUTO";

    private final List<GlobalPaymentsTerminalDriver> drivers;

    public GlobalPaymentsUniversalAdapter() {
        this(loadDrivers());
    }

    GlobalPaymentsUniversalAdapter(List<GlobalPaymentsTerminalDriver> drivers) {
        var indexed = new LinkedHashMap<String, GlobalPaymentsTerminalDriver>();
        for (var driver : List.copyOf(drivers)) {
            var protocol = protocol(driver.protocol());
            if (indexed.putIfAbsent(protocol, driver) != null) {
                throw new IllegalArgumentException("Duplicate Global Payments protocol driver: " + protocol);
            }
        }
        this.drivers = List.copyOf(indexed.values());
    }

    @Override
    public String adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public AdapterManifest manifest() {
        var modes = drivers.stream()
                .filter(driver -> driver.mode() != com.tpverp.bridge.spi.TerminalExecutionMode.LIVE || driver.certifiedForLivePayments())
                .map(GlobalPaymentsTerminalDriver::mode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var protocols = drivers.stream().map(GlobalPaymentsTerminalDriver::protocol)
                .map(GlobalPaymentsUniversalAdapter::protocol)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var connections = drivers.stream().flatMap(driver -> driver.connectionTypes().stream())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var live = drivers.stream().anyMatch(driver -> driver.mode() == com.tpverp.bridge.spi.TerminalExecutionMode.LIVE
                && driver.certifiedForLivePayments());
        return new AdapterManifest(ADAPTER_ID, PROVIDER, "Global Payments Universal", modes, protocols, connections, live);
    }

    @Override
    public void initialize(com.tpverp.bridge.spi.AdapterRuntime runtime) {
        drivers.forEach(driver -> driver.initialize(runtime));
    }

    @Override
    public boolean supports(TerminalProfile profile) {
        return profile != null && PROVIDER.equals(profile.provider()) && selected(profile).isPresent();
    }

    @Override
    public Set<BridgeCapability> capabilities(TerminalProfile profile) {
        return selected(profile).map(driver -> Set.copyOf(driver.capabilities(profile))).orElse(Set.of());
    }

    @Override
    public AdapterHealth health(TerminalProfile profile) {
        return selected(profile).map(driver -> driver.health(profile))
                .orElseGet(() -> AdapterHealth.unavailable("DRIVER_NOT_INSTALLED"));
    }

    @Override
    public OperationResult pair(PairingRequest request, TerminalProfile profile) {
        return selected(profile).map(driver -> driver.pair(request, profile))
                .orElseGet(GlobalPaymentsUniversalAdapter::driverNotInstalled);
    }

    @Override
    public OperationResult operate(OperationRequest request, TerminalProfile profile) {
        return selected(profile).map(driver -> driver.operate(request, profile))
                .orElseGet(GlobalPaymentsUniversalAdapter::driverNotInstalled);
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        for (var driver : drivers) {
            try {
                driver.close();
            } catch (Exception exception) {
                if (failure == null) failure = exception; else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private Optional<GlobalPaymentsTerminalDriver> selected(TerminalProfile profile) {
        if (profile == null || !PROVIDER.equals(profile.provider())) return Optional.empty();
        var requested = requestedProtocol(profile);
        var candidates = drivers.stream()
                .filter(driver -> driver.mode() == profile.mode())
                .filter(driver -> profile.mode() != com.tpverp.bridge.spi.TerminalExecutionMode.LIVE || driver.certifiedForLivePayments())
                .filter(driver -> AUTO.equals(requested) || protocol(driver.protocol()).equals(requested))
                .filter(driver -> safelySupports(driver, profile))
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private static boolean safelySupports(GlobalPaymentsTerminalDriver driver, TerminalProfile profile) {
        try {
            return driver.supports(profile);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String requestedProtocol(TerminalProfile profile) {
        var value = profile.parameters().get("protocol");
        return value == null || value.isBlank() ? AUTO : protocol(value);
    }

    private static String protocol(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Global Payments protocol");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private static List<GlobalPaymentsTerminalDriver> loadDrivers() {
        var loaded = new ArrayList<GlobalPaymentsTerminalDriver>();
        ServiceLoader.load(GlobalPaymentsTerminalDriver.class,
                GlobalPaymentsUniversalAdapter.class.getClassLoader()).forEach(loaded::add);
        return loaded;
    }

    private static OperationResult driverNotInstalled() {
        return OperationResult.failure("SDK_NOT_INSTALLED", "Controlador certificado de Global Payments no instalado");
    }
}
