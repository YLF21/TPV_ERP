package com.tpverp.backend.supervision;

import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.security.domain.OperationalSessionContext;
import com.tpverp.backend.shared.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Best-effort capture: only server-derived identifiers, never exception messages or request data. */
@Service
public class ApplicationFailureRecorder {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationFailureRecorder.class);
    private final JdbcTemplate jdbc;
    private final LicenseRepository licenses;
    private final TransactionTemplate transaction;
    private final String appVersion;
    private final SyncOutboxService outbox;

    public ApplicationFailureRecorder(JdbcTemplate jdbc, LicenseRepository licenses,
            PlatformTransactionManager transactionManager, Environment environment, SyncOutboxService outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.licenses = licenses;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(3);
        appVersion = safe(environment.getProperty("tpv.verifactu.system-version"), "[A-Za-z0-9][A-Za-z0-9._+\\-]{0,79}");
    }

    public void record(Authentication authentication, Module module, Throwable failure, HttpServletRequest request) {
        if (failure == null || authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof OperationalSessionContext context)
                || !context.isOperational()) return;
        try {
            var evidence = evidence(module, failure, request);
            transaction.executeWithoutResult(status -> {
                // A global admin without a terminal is intentionally not assigned to an arbitrary shop.
                licenses.findActiveByTiendaId(context.storeId()).stream()
                        .filter(license -> license.getSaasCompanyId() != null && license.getSaasStoreId() != null)
                        .findFirst().ifPresent(license -> persist(new StoreFailurePublisher.Site(
                                license.getLocalCompanyId(), context.storeId(), license.getInstalacionId()), evidence));
            });
            LOG.warn("APPLICATION_FAILURE traceId={} module={} exceptionType={} errorLocation={}",
                    evidence.traceId(), module, evidence.exceptionType(), evidence.errorLocation());
        } catch (RuntimeException reportingFailure) {
            // Never change the original business result, expose raw diagnostics or recursively report reporting failures.
            LOG.warn("APPLICATION_FAILURE_CAPTURE_UNAVAILABLE");
        }
    }

    void persist(StoreFailurePublisher.Site site, Evidence evidence) {
        var snapshots = jdbc.query("""
                insert into local_application_failure
                    (id,empresa_id,tienda_id,instalacion_id,fingerprint,module,app_version,trace_id,
                     exception_type,error_location,first_seen_at,last_seen_at,occurrences,revision)
                values (?,?,?,?,?,?,?,?,?,?,clock_timestamp(),clock_timestamp(),1,0)
                on conflict (empresa_id,tienda_id,instalacion_id,fingerprint) do update set
                    last_seen_at=greatest(local_application_failure.last_seen_at,excluded.last_seen_at),
                    trace_id=excluded.trace_id, occurrences=local_application_failure.occurrences+1,
                    revision=local_application_failure.revision+1
                returning *
                """, (rs, row) -> StoreFailurePublisher.applicationPayload(rs, site.installationId()), UUID.randomUUID(), site.companyId(), site.storeId(), site.installationId(), fingerprint(evidence),
                evidence.module().name(), evidence.appVersion(), evidence.traceId(), evidence.exceptionType(), evidence.errorLocation());
        // Capture and enqueue each occurrence atomically: coalescing before upload loses earlier trace IDs.
        var snapshot = snapshots.getFirst();
        outbox.enqueue(new SyncOutboundEventCommand(site.companyId(), site.storeId(), null,
                StoreFailurePublisher.ENTITY_TYPE, UUID.fromString((String) snapshot.get("sourceId")),
                SyncOperation.ACTUALIZAR, snapshot));
    }

    Evidence evidence(Module module, Throwable failure, HttpServletRequest request) {
        // Prefer the innermost application frame, retaining a wrapper frame when a library cause has none.
        var causes = new java.util.ArrayList<Throwable>();
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (int depth = 0; failure != null && depth < 16 && seen.add(failure); depth++, failure = failure.getCause()) {
            causes.add(failure);
        }
        String type = safe(causes.getLast().getClass().getName(), "[A-Za-z_$][A-Za-z0-9_$.]{0,159}");
        String location = null;
        for (int index = causes.size() - 1; index >= 0 && location == null; index--) {
            for (StackTraceElement frame : causes.get(index).getStackTrace()) {
                if (frame.getClassName().startsWith("com.tpverp.backend.")
                        && !frame.getClassName().startsWith("com.tpverp.backend.supervision.")) {
                    String candidate = frame.getClassName() + "." + frame.getMethodName() + ":" + Math.max(0, frame.getLineNumber());
                    if (candidate.length() <= 240) {
                        location = safe(candidate, "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+:[0-9]{1,9}");
                    }
                    if (location != null) break;
                }
            }
        }
        String correlation = CorrelationIdFilter.getOrCreate(request);
        return new Evidence(module, appVersion, correlation, type, location);
    }

    private static String safe(String value, String pattern) {
        return value != null && value.matches(pattern) ? value : null;
    }

    private static String fingerprint(Evidence evidence) {
        try {
            String source = evidence.module() + "|" + evidence.appVersion() + "|" + evidence.exceptionType() + "|" + evidence.errorLocation();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public enum Module { SALES, PRINTING, SYNC, APPLICATION }
    record Evidence(Module module, String appVersion, String traceId, String exceptionType, String errorLocation) { }
}
