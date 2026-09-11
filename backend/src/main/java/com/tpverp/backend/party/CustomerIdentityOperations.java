package com.tpverp.backend.party;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Small durable intent store; independent commits are required to survive uncertain HTTP responses. */
@Service
public class CustomerIdentityOperations {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;

    public CustomerIdentityOperations(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Operation prepare(UUID companyId, UUID storeId, UUID customerId, UUID expectedId,
            Long revision, CustomerDocumentIdentity identity) {
        try {
            return independent.execute(status -> {
            boolean creating = customerId == null;
            String lockKey = companyId + ":" + (creating ? identity.canonicalNumber() : customerId);
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, lockKey);
            var pending = jdbc.query("""
                    select * from customer_identity_operation where company_id = ?
                      and state in ('PENDING','CANCEL_PENDING')
                      and (customer_id = ? or document_number = ?) order by created_at
                    """, CustomerIdentityOperations::map, companyId, customerId, identity.canonicalNumber());
            if (!pending.isEmpty()) {
                Operation previous = pending.getFirst();
                if (pending.size() != 1 || !storeId.equals(previous.storeId())
                        || previous.creating() != creating
                        || (!creating && !customerId.equals(previous.customerId()))
                        || previous.documentType() != identity.canonicalType()
                        || !previous.documentNumber().equals(identity.canonicalNumber())
                        || !Objects.equals(expectedId, previous.expectedCustomerId())
                        || !Objects.equals(revision, previous.expectedRevision())) {
                    throw CustomerIdentityException.conflict();
                }
                return previous;
            }
            var operation = new Operation(UUID.randomUUID(), companyId, storeId,
                    creating ? UUID.randomUUID() : customerId, creating, identity.canonicalType(),
                    identity.canonicalNumber(), expectedId, revision, "PENDING");
            jdbc.update("""
                    insert into customer_identity_operation(operation_id,company_id,store_id,customer_id,
                        creating,document_type,document_number,expected_customer_id,expected_revision,state)
                    values (?,?,?,?,?,?,?,?,?, 'PENDING')
                    """, operation.operationId(), companyId, storeId, operation.customerId(), creating,
                    identity.canonicalType().name(), identity.canonicalNumber(), expectedId, revision);
            return operation;
            });
        } catch (DuplicateKeyException exception) {
            throw CustomerIdentityException.conflict();
        }
    }

    /** Held by the business transaction, preventing two submissions from applying the same intent. */
    public Operation lock(UUID id) {
        return jdbc.query("select * from customer_identity_operation where operation_id = ? for update",
                CustomerIdentityOperations::map, id).stream().findFirst().orElseThrow(CustomerIdentityException::conflict);
    }

    public void committed(UUID id) {
        if (jdbc.update("update customer_identity_operation set state = 'LOCAL_COMMITTED' where operation_id = ? and state = 'PENDING'", id) != 1) {
            throw CustomerIdentityException.conflict();
        }
    }

    public void cancellationPending(UUID id) {
        independent.executeWithoutResult(status -> jdbc.update("""
                update customer_identity_operation set state = 'CANCEL_PENDING'
                where operation_id = ? and state = 'PENDING'
                """, id));
    }

    public void cancelled(UUID id) {
        independent.executeWithoutResult(status -> jdbc.update("""
                update customer_identity_operation set state = 'CANCELLED'
                where operation_id = ? and state = 'CANCEL_PENDING'
                """, id));
    }

    public List<Operation> cancellations() {
        return jdbc.query("""
                select * from customer_identity_operation where state = 'CANCEL_PENDING'
                order by created_at limit 50
                """, CustomerIdentityOperations::map);
    }

    /** Recover a process crash without cancelling a currently locked business transaction. */
    public void recoverAbandoned() {
        independent.executeWithoutResult(status -> jdbc.update("""
                update customer_identity_operation set state = 'CANCEL_PENDING'
                where operation_id in (
                    select operation_id from customer_identity_operation
                    where state = 'PENDING' and created_at < now() - interval '5 minutes'
                    order by created_at limit 50 for update skip locked
                )
                """));
    }

    private static Operation map(ResultSet row, int index) throws SQLException {
        return new Operation(row.getObject("operation_id", UUID.class), row.getObject("company_id", UUID.class),
                row.getObject("store_id", UUID.class), row.getObject("customer_id", UUID.class), row.getBoolean("creating"),
                DocumentType.valueOf(row.getString("document_type")), row.getString("document_number"),
                row.getObject("expected_customer_id", UUID.class), row.getObject("expected_revision", Long.class), row.getString("state"));
    }

    public record Operation(UUID operationId, UUID companyId, UUID storeId, UUID customerId, boolean creating,
            DocumentType documentType, String documentNumber, UUID expectedCustomerId, Long expectedRevision, String state) { }
}
