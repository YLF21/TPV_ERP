package com.tpverp.backend.pdawork;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.OperationalSessionContext;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.terminal.TerminalType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PdaOperationalObservabilityService {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_.-]{1,64}");
    private static final Pattern TRACE = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final Duration MAX_AGE = Duration.ofDays(7);
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);
    private static final EnumSet<PdaOperationalEventType> SYNC_TYPES = EnumSet.of(
            PdaOperationalEventType.SYNC_FAILURE, PdaOperationalEventType.SYNC_CONFLICT);
    private static final EnumSet<PdaOperationalEventType> BLOCKED_TYPES = EnumSet.of(
            PdaOperationalEventType.TASK_BLOCKED);

    private final PdaOperationalEventRepository events;
    private final PdaWorkRepository work;
    private final TerminalRepository terminals;
    private final CurrentOrganization organization;
    private final Clock clock;
    private final MeterRegistry meters;

    public PdaOperationalObservabilityService(PdaOperationalEventRepository events, PdaWorkRepository work,
            TerminalRepository terminals, CurrentOrganization organization, Clock clock,
            ObjectProvider<MeterRegistry> meters) {
        this.events = events;
        this.work = work;
        this.terminals = terminals;
        this.organization = organization;
        this.clock = clock;
        this.meters = meters.getIfAvailable();
    }

    @Transactional
    public AcceptedEvent record(Authentication authentication, RecordCommand command, String requestTraceId) {
        Objects.requireNonNull(command, "command");
        OperationalSessionContext context = operationalContext(authentication);
        UUID storeId = organization.currentStore().getId();
        if (!storeId.equals(context.storeId())) throw new AccessDeniedException("PDA_STORE_SCOPE_MISMATCH");
        Terminal terminal = terminals.findByIdAndTiendaId(context.terminalId(), storeId)
                .filter(value -> value.getTipo() == TerminalType.PDA && value.isActiva() && value.isAprobada())
                .orElseThrow(() -> new AccessDeniedException("PDA_TERMINAL_NOT_AUTHORIZED"));
        Instant now = Instant.now(clock);
        validateTimestamp(command.occurredAt(), now);
        String code = normalizeCode(command.code());
        validateDuration(command.type(), command.durationMs());
        if (command.workId() != null && work.findByIdAndStoreId(command.workId(), storeId).isEmpty()) {
            throw new IllegalArgumentException("PDA_WORK_NOT_FOUND");
        }
        String traceId = safeTrace(command.traceId()) ? command.traceId() :
                (safeTrace(requestTraceId) ? requestTraceId : null);
        UUID eventId = UUID.randomUUID();
        int inserted = events.insertIfAbsent(eventId, storeId, terminal.getId(), command.clientEventId(),
                command.type().name(), code, command.workId(), command.durationMs(), command.occurredAt(), now, traceId);
        if (inserted == 0) {
            var duplicate = events.findByStoreIdAndTerminalIdAndClientEventId(
                    storeId, terminal.getId(), command.clientEventId()).orElseThrow(
                            () -> new IllegalStateException("PDA_EVENT_IDEMPOTENCY_LOOKUP_FAILED"));
            if (duplicate.getTipo() != command.type() || !duplicate.getCodigo().equals(code)
                    || !Objects.equals(duplicate.getWorkId(), command.workId())
                    || !Objects.equals(duplicate.getDurationMs(), command.durationMs())
                    || !duplicate.getOccurredAt().equals(command.occurredAt())) {
                throw new PdaWorkConflictException("PDA_EVENT_IDEMPOTENCY_CONFLICT");
            }
            return new AcceptedEvent(duplicate.getId(), duplicate.getReceivedAt(), true);
        }
        if (meters != null) {
            meters.counter("tpverp.pda.operational.events", "type", command.type().name()).increment();
            if (command.type() == PdaOperationalEventType.REQUEST_LATENCY) {
                meters.summary("tpverp.pda.request.latency", "source", "client")
                        .record(command.durationMs());
            }
        }
        return new AcceptedEvent(eventId, now, false);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(int windowHours, int inactiveMinutes, int page, int size, UUID terminalId) {
        if (windowHours < 1 || windowHours > 720) throw new IllegalArgumentException("windowHours debe estar entre 1 y 720");
        if (inactiveMinutes < 5 || inactiveMinutes > 1440) throw new IllegalArgumentException("inactiveMinutes debe estar entre 5 y 1440");
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Paginacion no valida");
        UUID storeId = organization.currentStore().getId();
        if (terminalId != null) terminal(storeId, terminalId);
        Instant now = Instant.now(clock);
        Instant from = now.minus(Duration.ofHours(windowHours));
        Page<PdaOperationalEvent> eventPage = terminalId == null
                ? events.findByStoreIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDescIdDesc(storeId, from, PageRequest.of(page, size))
                : events.findByStoreIdAndTerminalIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDescIdDesc(storeId, terminalId, from, PageRequest.of(page, size));
        List<Terminal> allTerminals = terminals.findAllByTiendaIdOrderByNombre(storeId).stream()
                .filter(value -> value.getTipo() == TerminalType.PDA).toList();
        Map<UUID, String> names = allTerminals.stream().collect(Collectors.toMap(Terminal::getId, Terminal::getNombre));
        List<EventView> sync = top(storeId, terminalId, SYNC_TYPES, from).stream().map(e -> view(e, names)).toList();
        List<EventView> blocked = top(storeId, terminalId, BLOCKED_TYPES, from).stream().map(e -> view(e, names)).toList();
        Instant inactiveBefore = now.minus(Duration.ofMinutes(inactiveMinutes));
        List<InactiveTerminal> inactive = allTerminals.stream()
                .filter(Terminal::isActiva).filter(Terminal::isAprobada)
                .filter(value -> terminalId == null || terminalId.equals(value.getId()))
                .filter(value -> value.getLastSeenAt() == null || value.getLastSeenAt().isBefore(inactiveBefore))
                .map(value -> new InactiveTerminal(value.getId(), value.getNombre(), value.getLastSeenAt(),
                        value.getLastSeenAt() == null ? null : value.getLastSeenAt().plus(Duration.ofMinutes(inactiveMinutes))))
                .toList();
        var latency = terminalId == null ? events.latency(storeId, from)
                : events.latencyForTerminal(storeId, terminalId, from);
        long failures = count(storeId, terminalId, PdaOperationalEventType.SYNC_FAILURE, from);
        long conflicts = count(storeId, terminalId, PdaOperationalEventType.SYNC_CONFLICT, from);
        long blockedCount = count(storeId, terminalId, PdaOperationalEventType.TASK_BLOCKED, from);
        Performance performance = new Performance(latency.getSampleCount(), latency.getAverageLatencyMs(),
                latency.getP95LatencyMs(), latency.getMaxLatencyMs());
        Summary summary = new Summary(failures, conflicts, blockedCount, inactive.size(),
                performance.averageLatencyMs(), performance.p95LatencyMs());
        return new Dashboard(now, from, summary, sync, blocked, inactive, performance,
                eventPage.getContent().stream().map(e -> view(e, names)).toList(), page, size,
                eventPage.getTotalElements(), eventPage.getTotalPages());
    }

    private List<PdaOperationalEvent> top(UUID storeId, UUID terminalId, EnumSet<PdaOperationalEventType> types, Instant from) {
        var limit = PageRequest.of(0, 50);
        return terminalId == null
                ? events.findByStoreIdAndTipoInAndOccurredAtGreaterThanEqualOrderByOccurredAtDescIdDesc(storeId, types, from, limit)
                : events.findByStoreIdAndTerminalIdAndTipoInAndOccurredAtGreaterThanEqualOrderByOccurredAtDescIdDesc(storeId, terminalId, types, from, limit);
    }

    private long count(UUID storeId, UUID terminalId, PdaOperationalEventType type, Instant from) {
        return terminalId == null ? events.countByStoreIdAndTipoAndOccurredAtGreaterThanEqual(storeId, type, from)
                : events.countByStoreIdAndTerminalIdAndTipoAndOccurredAtGreaterThanEqual(storeId, terminalId, type, from);
    }

    private Terminal terminal(UUID storeId, UUID terminalId) {
        return terminals.findByIdAndTiendaId(terminalId, storeId)
                .filter(value -> value.getTipo() == TerminalType.PDA)
                .orElseThrow(() -> new IllegalArgumentException("PDA_TERMINAL_NOT_FOUND"));
    }

    private static OperationalSessionContext operationalContext(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof OperationalSessionContext context)
                || !context.isOperational()) throw new AccessDeniedException("PDA_OPERATIONAL_SESSION_REQUIRED");
        return context;
    }

    private static void validateTimestamp(Instant occurredAt, Instant now) {
        if (occurredAt == null || occurredAt.isBefore(now.minus(MAX_AGE))
                || occurredAt.isAfter(now.plus(FUTURE_TOLERANCE))) throw new IllegalArgumentException("PDA_EVENT_TIMESTAMP_OUT_OF_RANGE");
    }

    private static String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) throw new IllegalArgumentException("PDA_EVENT_CODE_INVALID");
        return normalized;
    }

    private static void validateDuration(PdaOperationalEventType type, Long duration) {
        if (type == PdaOperationalEventType.REQUEST_LATENCY && duration == null)
            throw new IllegalArgumentException("PDA_EVENT_DURATION_REQUIRED");
        if (type != PdaOperationalEventType.REQUEST_LATENCY && duration != null)
            throw new IllegalArgumentException("PDA_EVENT_DURATION_NOT_ALLOWED");
        if (duration != null && (duration < 0 || duration > 120000))
            throw new IllegalArgumentException("PDA_EVENT_DURATION_INVALID");
    }

    private static boolean safeTrace(String value) { return value != null && TRACE.matcher(value).matches(); }
    private static EventView view(PdaOperationalEvent e, Map<UUID, String> names) {
        return new EventView(e.getId(), e.getTerminalId(), names.getOrDefault(e.getTerminalId(), "PDA"),
                e.getTipo(), e.getCodigo(), e.getWorkId(), e.getDurationMs(), e.getOccurredAt(), e.getTraceId());
    }

    public record RecordCommand(UUID clientEventId, PdaOperationalEventType type, String code, UUID workId,
            Long durationMs, Instant occurredAt, String traceId) {}
    public record AcceptedEvent(UUID id, Instant acceptedAt, boolean duplicate) {}
    public record EventView(UUID id, UUID terminalId, String terminalName, PdaOperationalEventType type,
            String code, UUID workId, Long durationMs, Instant occurredAt, String traceId) {}
    public record InactiveTerminal(UUID terminalId, String terminalName, Instant lastSeenAt, Instant inactiveSince) {}
    public record Performance(long sampleCount, double averageLatencyMs, double p95LatencyMs, long maxLatencyMs) {}
    public record Summary(long syncFailures, long syncConflicts, long blockedWork, long inactiveTerminals,
            double averageLatencyMs, double p95LatencyMs) {}
    public record Dashboard(Instant generatedAt, Instant windowFrom, Summary summary, List<EventView> syncFailures,
            List<EventView> blockedWork, List<InactiveTerminal> inactiveTerminals, Performance performance,
            List<EventView> events, int page, int size, long totalEvents, int totalPages) {}
}
