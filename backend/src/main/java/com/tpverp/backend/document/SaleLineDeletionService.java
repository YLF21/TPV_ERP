package com.tpverp.backend.document;

import com.tpverp.backend.control.ControlAlertDetectionService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
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
        Objects.requireNonNull(saleOperationId, "saleOperationId");
        Objects.requireNonNull(deletionOperationId, "deletionOperationId");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("message.document.lines_required");
        }
        var storeId = organization.currentStore().getId();
        var userId = organization.currentUser(authentication).getId();
        var terminalId = currentTerminal.terminalId(authentication);
        var deletedAt = Instant.now(clock);
        var type = fullTicketClear ? "LISTA" : "LINEA";
        var created = jdbc.update("""
                insert into venta_operacion_eliminacion (
                    id, tienda_id, terminal_id, usuario_id, operacion_venta_id,
                    vaciado_completo, eliminado_en)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (id) do nothing
                """, deletionOperationId, storeId, terminalId, userId, saleOperationId,
                fullTicketClear, deletedAt);
        if (created == 0) {
            return existingOperation(
                    storeId, terminalId, userId, saleOperationId,
                    deletionOperationId, fullTicketClear);
        }
        var recorded = lines.stream()
                .map(line -> insert(
                        storeId, terminalId, userId, saleOperationId, deletionOperationId,
                        deletedAt, type, line))
                .toList();
        var sequence = sequence(storeId, terminalId, userId, saleOperationId);
        controlAlerts.detectConsecutiveLineDeletions(
                saleOperationId, sequence.deletionCount(), sequence.lines(),
                terminalId, authentication);
        if (fullTicketClear) {
            controlAlerts.detectSaleScreenCleared(
                    deletionOperationId, recorded, terminalId, authentication);
        }
        return recorded;
    }

    private List<SaleLineDeletionView> existingOperation(
            UUID storeId,
            UUID terminalId,
            UUID userId,
            UUID saleOperationId,
            UUID deletionOperationId,
            boolean fullTicketClear) {
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
        return jdbc.query("""
                select id, tienda_id, terminal_id, usuario_id, eliminado_en, tipo,
                       producto_id, codigo, nombre, cantidad, precio_unitario, total
                from venta_linea_eliminada
                where tienda_id = ? and operacion_eliminacion_id = ?
                order by id
                """, (rs, row) -> view(rs), storeId, deletionOperationId);
    }

    @Transactional(readOnly = true)
    public List<SaleLineDeletionView> list() {
        return jdbc.query("""
                select id, tienda_id, terminal_id, usuario_id, eliminado_en, tipo,
                       producto_id, codigo, nombre, cantidad, precio_unitario, total
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
        jdbc.update("delete from venta_linea_eliminada where eliminado_en < ?", cutoff);
        jdbc.update("delete from venta_operacion_eliminacion where eliminado_en < ?", cutoff);
    }

    private SaleLineDeletionView insert(
            UUID storeId,
            UUID terminalId,
            UUID userId,
            UUID saleOperationId,
            UUID deletionOperationId,
            Instant deletedAt,
            String type,
            SaleLineDeletionCommand line) {
        var id = UUID.randomUUID();
        var quantity = quantity(line.quantity());
        var unitPrice = Money.euros(line.unitPrice());
        var total = Money.euros(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        var productId = Objects.requireNonNull(line.productId(), "productId");
        var code = clean(line.code());
        var name = clean(line.name());
        jdbc.update("""
                insert into venta_linea_eliminada (
                    id, tienda_id, terminal_id, usuario_id,
                    operacion_venta_id, operacion_eliminacion_id, eliminado_en, tipo,
                    producto_id, codigo, nombre, cantidad, precio_unitario, total)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, storeId, terminalId, userId, saleOperationId, deletionOperationId,
                deletedAt, type,
                productId, code, name, quantity, unitPrice, total);
        return new SaleLineDeletionView(
                id, storeId, terminalId, userId, deletedAt, type,
                productId, code, name, quantity, unitPrice, total);
    }

    private DeletionSequence sequence(
            UUID storeId, UUID terminalId, UUID userId, UUID saleOperationId) {
        var deletionCount = jdbc.queryForObject("""
                select count(*)
                from venta_operacion_eliminacion
                where tienda_id = ? and terminal_id = ? and usuario_id = ?
                  and operacion_venta_id = ?
                """, Integer.class, storeId, terminalId, userId, saleOperationId);
        var lines = jdbc.query("""
                select id, tienda_id, terminal_id, usuario_id, eliminado_en, tipo,
                       producto_id, codigo, nombre, cantidad, precio_unitario, total
                from venta_linea_eliminada
                where tienda_id = ? and terminal_id = ? and usuario_id = ?
                  and operacion_venta_id = ?
                order by eliminado_en, id
                """, (rs, row) -> view(rs), storeId, terminalId, userId, saleOperationId);
        return new DeletionSequence(deletionCount == null ? 0 : deletionCount, lines);
    }

    private record DeletionSequence(int deletionCount, List<SaleLineDeletionView> lines) {
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
                rs.getInt("cantidad"),
                rs.getBigDecimal("precio_unitario"),
                rs.getBigDecimal("total"));
    }

    private static int quantity(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("message.document.position_must_be_positive");
        }
        return value;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("message.common.value_required");
        }
        return value.trim();
    }
}
