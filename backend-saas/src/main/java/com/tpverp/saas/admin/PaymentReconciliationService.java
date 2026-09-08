package com.tpverp.saas.admin;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentReconciliationService {

    private final JdbcTemplate jdbc;
    private final PaymentReconciliationAdapter adapter;
    private final Clock clock;

    public PaymentReconciliationService(JdbcTemplate jdbc, PaymentReconciliationAdapter adapter, Clock clock) {
        this.jdbc = jdbc;
        this.adapter = adapter;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PaymentReconciliationResponse> list(UUID companyId) {
        return jdbc.query("""
                select id, company_id, payment_id, provider, external_reference, amount, currency,
                       booked_at, status, notes, created_at
                from saas_payment_reconciliation where company_id = ?
                order by booked_at desc, created_at desc
                """, (rs, rowNum) -> map(rs), companyId);
    }

    @Transactional
    public PaymentReconciliationResponse create(UUID companyId, CreatePaymentReconciliationRequest request) {
        adapter.validate(request);
        Long companies = jdbc.queryForObject("select count(*) from saas_company where id = ?", Long.class, companyId);
        if (companies == null || companies == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no existe");
        }
        String provider = request.provider().trim().toUpperCase(Locale.ROOT);
        String externalReference = request.externalReference().trim();
        lock("reconciliation-reference", companyId + ":" + provider + ":" + externalReference);
        if (request.paymentId() != null) {
            lock("reconciliation-payment", request.paymentId().toString());
        }
        rejectDuplicate(companyId, request.paymentId(), provider, externalReference);
        String amount = new BigDecimal(request.amount()).setScale(2).toPlainString();
        String currency = request.currency().trim().toUpperCase(Locale.ROOT);
        String status = "PENDING";
        if (request.paymentId() != null) {
            PaymentMatch payment = jdbc.query("""
                    select p.amount, i.currency from saas_billing_payment p
                    join saas_billing_invoice i on i.id = p.invoice_id
                    where p.id = ? and i.company_id = ?
                    """, rs -> rs.next() ? new PaymentMatch(rs.getString(1), rs.getString(2)) : null,
                    request.paymentId(), companyId);
            if (payment == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El pago no pertenece a la empresa");
            }
            if (new BigDecimal(payment.amount()).compareTo(new BigDecimal(amount)) != 0
                    || !payment.currency().equalsIgnoreCase(currency)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Importe o moneda no coinciden con el pago");
            }
            status = "MATCHED";
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into saas_payment_reconciliation(
                        id, company_id, payment_id, provider, external_reference, amount, currency,
                        booked_at, status, notes, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, companyId, request.paymentId(), provider,
                    externalReference, amount, currency, Timestamp.from(request.bookedAt()),
                    status, blank(request.notes()), Timestamp.from(clock.instant()));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se pudo registrar la conciliacion por una restriccion de integridad", exception);
        }
        return byId(id);
    }

    private PaymentReconciliationResponse byId(UUID id) {
        return jdbc.query("""
                select id, company_id, payment_id, provider, external_reference, amount, currency,
                       booked_at, status, notes, created_at
                from saas_payment_reconciliation where id = ?
                """, (rs, rowNum) -> map(rs), id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conciliacion no existe"));
    }

    private static PaymentReconciliationResponse map(ResultSet rs) throws SQLException {
        return new PaymentReconciliationResponse(
                rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("payment_id", UUID.class), rs.getString("provider"),
                rs.getString("external_reference"), rs.getString("amount"), rs.getString("currency"),
                rs.getTimestamp("booked_at").toInstant(), rs.getString("status"), rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
    }

    private void rejectDuplicate(UUID companyId, UUID paymentId, String provider, String externalReference) {
        if (paymentId != null && exists("""
                select exists(
                    select 1 from saas_payment_reconciliation
                    where payment_id = ? and status = 'MATCHED')
                """, paymentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El pago ya tiene una conciliacion MATCHED");
        }
        if (exists("""
                select exists(
                    select 1 from saas_payment_reconciliation
                    where company_id = ? and provider = ? and external_reference = ?)
                """, companyId, provider, externalReference)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La referencia externa ya fue conciliada para este proveedor");
        }
    }

    private void lock(String namespace, String value) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?::text, hashtext(?)))")) {
                statement.setString(1, value);
                statement.setString(2, namespace);
                statement.execute();
            }
            return null;
        });
    }

    private record PaymentMatch(String amount, String currency) {
    }
}
