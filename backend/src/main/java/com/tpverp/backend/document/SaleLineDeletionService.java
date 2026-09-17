package com.tpverp.backend.document;

import com.tpverp.backend.control.ControlAlertDetectionService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaleLineDeletionService {

    private final JdbcTemplate jdbc;
    private final CurrentOrganization organization;
    private final CurrentTerminal currentTerminal;
    private final ControlAlertDetectionService controlAlerts;
    private final Clock clock;

    public SaleLineDeletionService(
            JdbcTemplate jdbc,
            CurrentOrganization organization,
            CurrentTerminal currentTerminal,
            ControlAlertDetectionService controlAlerts,
            Clock clock) {
        this.jdbc = jdbc;
        this.organization = organization;
        this.currentTerminal = currentTerminal;
        this.controlAlerts = controlAlerts;
        this.clock = clock;
    }

    // Records products removed from an unpaid sale screen before fiscal numbering exists.
    @Transactional
    public List<SaleLineDeletionView> record(
            UUID saleOperationId,
            UUID deletionOperationId,
            List<SaleLineDeletionCommand> lines,
            boolean fullTicketClear,
            Authentication authentication) {
        return record(saleOperationId, deletionOperationId, lines, fullTicketClear, null, null, authentication);
    }

    @Transactional
    public List<SaleLineDeletionView> record(
            UUID saleOperationId,
            UUID deletionOperationId,
            List<SaleLineDeletionCommand> lines,
            boolean fullTicketClear,
            Instant occurredAt,
            SaleLineDeletionContext context,
            Authentication authentication) {
        Objects.requireNonNull(saleOperationId, "saleOperationId");
        Objects.requireNonNull(deletionOperationId, "deletionOperationId");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("message.document.lines_required");
        }
        var storeId = organization.currentStore().getId();
        var userId = organization.currentUser(authentication).getId();
        var terminalId = currentTerminal.terminalId(authentication);
        requireContext(context, occurredAt, storeId, userId, terminalId);
        var receivedAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        var deletedAt = occurrence(occurredAt, receivedAt);
        var normalizedLines = lines.stream().map(SaleLineDeletionService::normalize).toList();
        requireStoreProducts(storeId, normalizedLines);
        // Serialize only this operator's sale sequence, including different operation UUIDs.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "sale-line-deletion:" + storeId + ":" + terminalId + ":" + userId + ":" + saleOperationId);
        var type = fullTicketClear ? "LISTA" : "LINEA";
        var created = jdbc.update("""
                insert into venta_operacion_eliminacion (
                    id, tienda_id, terminal_id, usuario_id, operacion_venta_id,
                    vaciado_completo, eliminado_en, recibido_en)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict do nothing
                """, deletionOperationId, storeId, terminalId, userId, saleOperationId,
                fullTicketClear, Timestamp.from(deletedAt), Timestamp.from(receivedAt));
        if (created == 0) {
            return existingOperation(
                    storeId, terminalId, userId, saleOperationId,
                    deletionOperationId, fullTicketClear, occurredAt == null ? null : deletedAt, normalizedLines);
        }
        var recorded = normalizedLines.stream()
                .map(line -> insert(
                        storeId, terminalId, userId, saleOperationId, deletionOperationId,
                        deletedAt, receivedAt, type, line))
                .toList();
        var sequence = sequence(storeId, terminalId, userId, saleOperationId);
        controlAlerts.detectRecordedDeletion(saleOperationId, deletionOperationId, fullTicketClear,
                recorded, sequence.points(), sequence.lines(), terminalId, deletedAt, receivedAt, authentication);
        return recorded;
    }

    private static void requireContext(SaleLineDeletionContext context, Instant occurredAt,
            UUID storeId, UUID userId, UUID terminalId) {
        if ((context == null) != (occurredAt == null)) {
            throw new IllegalArgumentException("message.control.delivery_context_time_required");
        }
        if (context != null && (!storeId.equals(context.storeId()) || !userId.equals(context.userId())
                || !terminalId.equals(context.terminalId()))) {
            throw new AccessDeniedException("Control event context differs from the authenticated session");
        }
    }

    private static Instant occurrence(Instant occurredAt, Instant receivedAt) {
        if (occurredAt == null) return receivedAt;
        // The client anchors its monotonic clock to serverTime; tolerate sub-second transport rounding.
        if (occurredAt.isAfter(receivedAt.plusSeconds(1))) {
            throw new IllegalArgumentException("message.control.delivery_time_future");
        }
        return occurredAt.truncatedTo(ChronoUnit.MICROS);
    }

    private static SaleLineDeletionCommand normalize(SaleLineDeletionCommand line) {
        return new SaleLineDeletionCommand(Objects.requireNonNull(line.productId(), "productId"),
                line.code() == null ? "" : line.code().trim(), clean(line.name()),
                quantity(line.quantity()), Money.exactUnitPrice(line.unitPrice()));
    }

    private void requireStoreProducts(UUID storeId, List<SaleLineDeletionCommand> lines) {
        var productIds = lines.stream()
                .map(line -> Objects.requireNonNull(line.productId(), "productId"))
                .distinct().toList();
        var arguments = new ArrayList<Object>();
        arguments.add(storeId);
        arguments.addAll(productIds);
        var placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        var matching = jdbc.queryForObject(
                "select count(*) from producto where tienda_id = ? and id in (" + placeholders + ")",
                Integer.class, arguments.toArray());
        if (matching == null || matching != productIds.size()) {
            throw new NoSuchElementException("Producto no encontrado");
        }
    }

    private List<SaleLineDeletionView> existingOperation(
            UUID storeId,
            UUID terminalId,
            UUID userId,
            UUID saleOperationId,
            UUID deletionOperationId,
            boolean fullTicketClear,
            Instant occurredAt,
            List<SaleLineDeletionCommand> expectedLines) {
        var matchingHeader = jdbc.queryForObject("""
                select count(*)
                from venta_operacion_eliminacion
                where id = ? and tienda_id = ? and terminal_id = ? and usuario_id = ?
                  and operacion_venta_id = ? and vaciado_completo = ?
                """, Integer.class, deletionOperationId, storeId, terminalId, userId,
                saleOperationId, fullTicketClear);
        if (matchingHeader == null || matchingHeader != 1) {
            throw new IllegalStateException("sale_line_deletion_idempotency_conflict");
        }
        var recorded = jdbc.query("""
                select id, tienda_id, terminal_id, usuario_id, eliminado_en, tipo,
                       producto_id, codigo, nombre, cantidad, precio_unitario, total, recibido_en
                from venta_linea_eliminada
                where tienda_id = ? and operacion_eliminacion_id = ?
                order by id
                """, (rs, row) -> view(rs), storeId, deletionOperationId);
        var storedLines = recorded.stream().map(line -> normalize(new SaleLineDeletionCommand(
                line.productId(), line.code(), line.name(), line.quantity(), line.unitPrice()))).toList();
        if ((occurredAt != null && recorded.stream().anyMatch(line -> !occurredAt.equals(line.deletedAt())))
                || !lineCounts(storedLines).equals(lineCounts(expectedLines))) {
            throw new IllegalStateException("sale_line_deletion_idempotency_conflict");
        }
        return recorded;
    }

    private static java.util.Map<SaleLineDeletionCommand, Long> lineCounts(List<SaleLineDeletionCommand> lines) {
        return lines.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    @Transactional(readOnly = true)
    public List<SaleLineDeletionView> list() {
        return jdbc.query("""
                select id, tienda_id, terminal_id, usuario_id, eliminado_en, tipo,
                       producto_id, codigo, nombre, cantidad, precio_unitario, total, recibido_en
                from venta_linea_eliminada
                where tienda_id = ?
                order by eliminado_en desc
                limit 200
                """, (rs, row) -> view(rs), organization.currentStore().getId());
    }

    @Transactional
    public void delete(UUID id) {
        jdbc.update("delete from venta_linea_eliminada where id = ? and tienda_id = ?",
                id, organization.currentStore().getId());
    }

    @Scheduled(cron = "${tpv.sales.deleted-lines-purge-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpired() {
        var cutoff = Instant.now(clock).minus(365, ChronoUnit.DAYS);
        jdbc.update("delete from venta_linea_eliminada where recibido_en < ?", Timestamp.from(cutoff));
        jdbc.update("delete from venta_operacion_eliminacion where recibido_en < ?", Timestamp.from(cutoff));
    }

    private SaleLineDeletionView insert(
            UUID storeId,
            UUID terminalId,
            UUID userId,
            UUID saleOperationId,
            UUID deletionOperationId,
            Instant deletedAt,
            Instant receivedAt,
            String type,
            SaleLineDeletionCommand line) {
        var id = UUID.randomUUID();
        var quantity = quantity(line.quantity());
        var unitPrice = Money.exactUnitPrice(line.unitPrice());
        var total = Money.euros(unitPrice.multiply(quantity));
        var productId = Objects.requireNonNull(line.productId(), "productId");
        var code = line.code() == null ? "" : line.code().trim();
        var name = clean(line.name());
        jdbc.update("""
                insert into venta_linea_eliminada (
                    id, tienda_id, terminal_id, usuario_id,
                    operacion_venta_id, operacion_eliminacion_id, eliminado_en, tipo,
                    producto_id, codigo, nombre, cantidad, precio_unitario, total, recibido_en)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, storeId, terminalId, userId, saleOperationId, deletionOperationId,
                Timestamp.from(deletedAt), type,
                productId, code, name, quantity, unitPrice, total, Timestamp.from(receivedAt));
        return new SaleLineDeletionView(
                id, storeId, terminalId, userId, deletedAt, type,
                productId, code, name, quantity, unitPrice, total, receivedAt);
    }

    private DeletionSequence sequence(
            UUID storeId, UUID terminalId, UUID userId, UUID saleOperationId) {
        var points = jdbc.query("""
                select id, eliminado_en, recibido_en,
                       count(*) over (order by eliminado_en range unbounded preceding) as deletion_count
                from venta_operacion_eliminacion
                where tienda_id = ? and terminal_id = ? and usuario_id = ?
                  and operacion_venta_id = ?
                order by eliminado_en, id
                """, (rs, row) -> new ControlAlertDetectionService.DeletionPoint(
                        rs.getObject("id", UUID.class), rs.getTimestamp("eliminado_en").toInstant(),
                        rs.getTimestamp("recibido_en").toInstant(), rs.getInt("deletion_count")),
                storeId, terminalId, userId, saleOperationId);
        var lines = jdbc.query("""
                select id, tienda_id, terminal_id, usuario_id, eliminado_en, tipo,
                       producto_id, codigo, nombre, cantidad, precio_unitario, total, recibido_en
                from venta_linea_eliminada
                where tienda_id = ? and terminal_id = ? and usuario_id = ?
                  and operacion_venta_id = ?
                order by eliminado_en, id
                """, (rs, row) -> view(rs), storeId, terminalId, userId, saleOperationId);
        return new DeletionSequence(points, lines);
    }

    private record DeletionSequence(List<ControlAlertDetectionService.DeletionPoint> points,
            List<SaleLineDeletionView> lines) {
    }

    private static SaleLineDeletionView view(ResultSet rs) throws SQLException {
        return new SaleLineDeletionView(
                rs.getObject("id", UUID.class),
                rs.getObject("tienda_id", UUID.class),
                rs.getObject("terminal_id", UUID.class),
                rs.getObject("usuario_id", UUID.class),
                rs.getTimestamp("eliminado_en").toInstant(),
                rs.getString("tipo"),
                rs.getObject("producto_id", UUID.class),
                rs.getString("codigo"),
                rs.getString("nombre"),
                rs.getBigDecimal("cantidad"),
                rs.getBigDecimal("precio_unitario"),
                rs.getBigDecimal("total"),
                rs.getTimestamp("recibido_en").toInstant());
    }

    private static BigDecimal quantity(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("cantidad es obligatoria");
        if (value.signum() == 0) {
            throw new IllegalArgumentException("cantidad no puede ser cero");
        }
        var normalized = value.stripTrailingZeros();
        if (normalized.scale() > 3) throw new IllegalArgumentException("message.document.quantity_scale");
        if (normalized.precision() - normalized.scale() > 16) {
            throw new IllegalArgumentException("message.control.delivery_quantity_range");
        }
        return value.setScale(3, RoundingMode.UNNECESSARY);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("message.common.value_required");
        }
        return value.trim();
    }
}
