package com.tpverp.backend.compatibility;

import com.tpverp.backend.terminal.PaymentLifecycleStatus;
import com.tpverp.backend.terminal.PaymentTerminalOperationStatus;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@PreAuthorize("isAuthenticated()")
public class SystemCompatibilityController {
    public static final List<String> CAPABILITIES = List.of(
            "PAYMENT_IDEMPOTENCY",
            "PAYMENT_RECOVERY",
            "PAYMENT_STATUS_QUERY",
            "PAYMENT_VOID",
            "PAYMENT_REFUND",
            "PAYMENT_RECONCILIATION",
            "CORRELATION_ID");

    private final String backendVersion;
    private final String apiVersion;
    private final String minimumFrontendVersion;

    public SystemCompatibilityController(
            @Value("${tpv.compatibility.backend-version:0.0.1-SNAPSHOT}") String backendVersion,
            @Value("${tpv.compatibility.api-version:1}") String apiVersion,
            @Value("${tpv.compatibility.minimum-frontend-version:0.0.1}") String minimumFrontendVersion) {
        this.backendVersion = backendVersion;
        this.apiVersion = apiVersion;
        this.minimumFrontendVersion = minimumFrontendVersion;
    }

    @GetMapping("/compatibility")
    public CompatibilityView compatibility() {
        Map<String, String> paymentStates = new LinkedHashMap<>();
        Arrays.stream(PaymentTerminalOperationStatus.values()).forEach(status ->
                paymentStates.put(status.name(), PaymentLifecycleStatus.from(status).name()));
        return new CompatibilityView(backendVersion, apiVersion, minimumFrontendVersion,
                CAPABILITIES, paymentStates);
    }

    public record CompatibilityView(
            String backendVersion,
            String apiVersion,
            String minimumFrontendVersion,
            List<String> capabilities,
            Map<String, String> paymentStates) {}
}
