package com.tpverp.backend.party;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable ownership survives an uncertain HTTP result without pretending that the customer committed. */
@Service
public class CustomerAdoptionOperations {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;

    public CustomerAdoptionOperations(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Operation prepare(UUID companyId, UUID storeId, UUID existingId, UUID centralId,
            long revision, CustomerDocumentIdentity identity) {
        try {
            return independent.execute(status -> {
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                        companyId + ":" + identity.canonicalNumber());
                var pending = jdbc.query("""
                        select * from customer_adoption_operation where company_id = ?
                          and state in ('PENDING','CANCEL_PENDING')
                          and (central_customer_id = ? or document_number = ? or customer_id = ?)
                        order by created_at
                        """, CustomerAdoptionOperations::map, companyId, centralId, identity.canonicalNumber(), existingId);
                if (!pending.isEmpty()) {
                    var previous = pending.getFirst();
                    if (pending.size() != 1 || !storeId.equals(previous.storeId())
                            || !centralId.equals(previous.centralCustomerId()) || revision != previous.expectedRevision()
                            || (existingId != null && !existingId.equals(previous.customerId()))
                            || previous.documentType() != identity.canonicalType()
                            || !previous.documentNumber().equals(identity.canonicalNumber())) throw CustomerIdentityException.conflict();
                    return previous;
                }
                // A concurrent click may have committed after the initial read. Reuse its stable owner.
                var committed = jdbc.query("""
                        select * from customer_adoption_operation where company_id = ? and store_id = ?
                          and central_customer_id = ? and state = 'LOCAL_COMMITTED'
                        order by created_at desc limit 1
                        """, CustomerAdoptionOperations::map, companyId, storeId, centralId);
                if (!committed.isEmpty()) return committed.getFirst();
                var operation = new Operation(UUID.randomUUID(), companyId, storeId,
                        existingId == null ? UUID.randomUUID() : existingId, centralId, revision,
                        identity.canonicalType(), identity.canonicalNumber(), "PENDING");
                jdbc.update("""
                        insert into customer_adoption_operation(operation_id,company_id,store_id,customer_id,
                            central_customer_id,expected_revision,document_type,document_number,state)
                        values (?,?,?,?,?,?,?,?, 'PENDING')
                        """, operation.operationId(), companyId, storeId, operation.customerId(), centralId,
                        revision, identity.canonicalType().name(), identity.canonicalNumber());
                return operation;
            });
        } catch (DuplicateKeyException exception) { throw CustomerIdentityException.conflict(); }
    }

    public Operation lock(UUID id) {
        return jdbc.query("select * from customer_adoption_operation where operation_id = ? for update",
                CustomerAdoptionOperations::map, id).stream().findFirst().orElseThrow(CustomerIdentityException::conflict);
    }

    public void committed(UUID id) {
        if (jdbc.update("update customer_adoption_operation set state = 'LOCAL_COMMITTED' where operation_id = ? and state = 'PENDING'", id) != 1) {
            throw CustomerIdentityException.conflict();
        }
    }

    public void cancellationPending(UUID id) {
        independent.executeWithoutResult(status -> jdbc.update("""
                update customer_adoption_operation set state = 'CANCEL_PENDING'
                where operation_id = ? and state = 'PENDING'
                """, id));
    }

    public void cancelled(UUID id) {
        independent.executeWithoutResult(status -> jdbc.update("""
                update customer_adoption_operation set state = 'CANCELLED'
                where operation_id = ? and state = 'CANCEL_PENDING'
                """, id));
    }

    public List<Operation> cancellations() {
        return jdbc.query("select * from customer_adoption_operation where state = 'CANCEL_PENDING' order by created_at limit 50",
                CustomerAdoptionOperations::map);
    }

    public void recoverAbandoned() {
        independent.executeWithoutResult(status -> jdbc.update("""
                update customer_adoption_operation set state = 'CANCEL_PENDING' where operation_id in (
                    select operation_id from customer_adoption_operation
                    where state = 'PENDING' and created_at < now() - interval '5 minutes'
                    order by created_at limit 50 for update skip locked)
                """));
    }

    private static Operation map(ResultSet row, int index) throws SQLException {
        return new Operation(row.getObject("operation_id", UUID.class), row.getObject("company_id", UUID.class),
                row.getObject("store_id", UUID.class), row.getObject("customer_id", UUID.class),
                row.getObject("central_customer_id", UUID.class), row.getLong("expected_revision"),
                DocumentType.valueOf(row.getString("document_type")), row.getString("document_number"), row.getString("state"));
    }

    public record Operation(UUID operationId, UUID companyId, UUID storeId, UUID customerId,
            UUID centralCustomerId, long expectedRevision, DocumentType documentType, String documentNumber, String state) { }
}
