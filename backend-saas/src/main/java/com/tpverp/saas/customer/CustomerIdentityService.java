package com.tpverp.saas.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.sync.SaasSyncEvent;
import com.tpverp.saas.sync.SyncOperation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerIdentityService {
    private final JdbcTemplate jdbc;
    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator authenticator;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CustomerIdentityService(JdbcTemplate jdbc, SaasInstallationRepository installations,
            InstallationAuthenticator authenticator, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.installations = installations;
        this.authenticator = authenticator;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public CustomerIdentityReservationResponse reserve(CustomerIdentityReservationRequest request, String token) {
        if (request == null || request.operationId() == null || request.localCustomerId() == null) {
            throw CustomerIdentityException.invalid();
        }
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        CustomerDocumentIdentity identity = CustomerDocumentIdentity.validate(
                request.documentType(), request.documentNumber());
        if (request.profile() == null) throw CustomerIdentityException.conflict();
        Profile profile = profile(request.profile());
        String profileHash = hash(profile.asMap());
        if (request.documentType() == null || request.expectedRevision() != null && request.expectedRevision() < 0
                || (request.expectedCustomerId() == null) != (request.expectedRevision() == null)) {
            throw CustomerIdentityException.conflict();
        }
        lockOperation(request.operationId());
        lockCompany(request.companyId());
        String requestHash = identityHash(request, identity, profileHash);
        Operation previous = operation(request.operationId());
        if (previous != null) {
            requireOwner(previous, installation, request.localCustomerId());
            if (!requestHash.equals(previous.requestHash()) || previous.status().equals("CANCELLED")) {
                throw CustomerIdentityException.conflict();
            }
            return previous.response();
        }
        boolean pending = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from saas_customer_identity_operation
                  where status = 'RESERVED' and ((installation_id = ? and local_customer_id = ?)
                    or (company_id = ? and customer_id = ?)))
                  or exists(select 1 from saas_customer_adoption_operation
                    where installation_id = ? and local_customer_id = ? and status = 'RESERVED')
                """, Boolean.class, installation.getId(), request.localCustomerId(), request.companyId(),
                request.expectedCustomerId(), installation.getId(), request.localCustomerId()));
        if (pending) throw CustomerIdentityException.conflict();
        List<UUID> links = jdbc.query("""
                select customer_id from saas_customer_identity_link
                where installation_id = ? and local_customer_id = ? and company_id = ?
                """, (rs, index) -> rs.getObject(1, UUID.class), installation.getId(),
                request.localCustomerId(), request.companyId());
        UUID customerId;
        long revision;
        if (links.isEmpty()) {
            if (request.expectedCustomerId() != null) throw CustomerIdentityException.conflict();
            requireMasterCapacity(request.companyId());
            customerId = UUID.randomUUID();
            revision = 1;
        } else {
            customerId = links.getFirst();
            if (!customerId.equals(request.expectedCustomerId())
                    || !Objects.equals(currentRevision(customerId, request.companyId()), request.expectedRevision())) {
                throw CustomerIdentityException.conflict();
            }
            revision = Math.addExact(request.expectedRevision(), 1);
        }
        claim(request.companyId(), identity.documentNumber(), customerId);
        claimCode(request.companyId(), profile.code(), customerId);
        jdbc.update("""
                insert into saas_customer_identity_operation(operation_id, company_id, store_id,
                  installation_id, local_customer_id, customer_id, expected_revision, revision,
                  document_type, document_number, request_hash, client_code, prepared_profile_hash, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?)
                """, request.operationId(), request.companyId(), request.storeId(), installation.getId(),
                request.localCustomerId(), customerId, request.expectedRevision(), revision,
                identity.documentType(), identity.documentNumber(), requestHash, profile.code(), profileHash,
                Timestamp.from(clock.instant()));
        return new CustomerIdentityReservationResponse(request.operationId(), customerId, revision,
                identity.documentType(), identity.documentNumber());
    }

    @Transactional
    public void cancel(UUID operationId, CustomerIdentityOwnerRequest request, String token) {
        if (request == null || operationId == null || request.localCustomerId() == null) {
            throw CustomerIdentityException.invalid();
        }
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        lockOperation(operationId);
        lockCompany(request.companyId());
        Operation operation = operation(operationId);
        if (operation == null) {
            // A timed-out reserve may still arrive after cancel. Persist a tombstone first.
            jdbc.update("""
                    insert into saas_customer_identity_operation(operation_id, company_id, store_id,
                      installation_id, local_customer_id, status, created_at)
                    values (?, ?, ?, ?, ?, 'CANCELLED', ?)
                    """, operationId, request.companyId(), request.storeId(), installation.getId(),
                    request.localCustomerId(), Timestamp.from(clock.instant()));
            return;
        }
        requireOwner(operation, installation, request.localCustomerId());
        if (operation.status().equals("COMMITTED")) throw CustomerIdentityException.conflict();
        if (operation.status().equals("CANCELLED")) return;
        jdbc.update("update saas_customer_identity_operation set status = 'CANCELLED' where operation_id = ?",
                operationId);
        jdbc.update("""
                delete from saas_customer_document_claim claim
                where company_id = ? and customer_id = ? and document_number = ?
                  and not exists (select 1 from saas_erp_customer customer
                    where customer.id = claim.customer_id and customer.company_id = claim.company_id
                      and saas_customer_document_key(customer.tax_id) = claim.document_number)
                """, operation.companyId(), operation.customerId(), operation.documentNumber());
        jdbc.update("""
                delete from saas_customer_code_claim claim
                where company_id = ? and customer_id = ? and code = ?
                  and not exists (select 1 from saas_erp_customer customer
                    where customer.id = claim.customer_id and customer.company_id = claim.company_id
                      and customer.code = claim.code)
                """, operation.companyId(), operation.customerId(), operation.clientCode());
    }

    public boolean supports(String entityType, SyncOperation operation) {
        return "CUSTOMER_IDENTITY".equals(entityType) && operation == SyncOperation.ACTUALIZAR;
    }

    /** Called in the authenticated sync receiver transaction, after the local customer commit. */
    @Transactional
    public void finalizeIdentity(SaasSyncEvent event, Map<String, Object> payload) {
        if (event == null || event.getInstallation() == null || !event.getInstallation().isActive()
                || event.getCompany() == null || event.getStore() == null
                || !supports(event.getEntityType(), event.getOperation()) || payload == null) {
            throw CustomerIdentityException.conflict();
        }
        SaasInstallation installation = event.getInstallation();
        if (!event.getCompany().getId().equals(installation.getCompany().getId())
                || !event.getStore().getId().equals(installation.getStore().getId())) {
            throw CustomerIdentityException.conflict();
        }
        UUID operationId = uuid(payload.get("operationId"));
        Profile profile = profile(payload);
        lockOperation(operationId);
        lockCompany(event.getCompany().getId());
        Operation operation = operation(operationId);
        requireOwner(operation, installation, event.getEntityId());
        String profileHash = hash(profile.asMap());
        if (!profileHash.equals(operation.preparedProfileHash())) throw CustomerIdentityException.conflict();
        if (operation.status().equals("COMMITTED")) {
            if (!profileHash.equals(operation.finalizedPayloadHash())) throw CustomerIdentityException.conflict();
            return;
        }
        if (!operation.status().equals("RESERVED")) throw CustomerIdentityException.conflict();
        Long current = currentRevision(operation.customerId(), operation.companyId());
        if (!Objects.equals(operation.expectedRevision(), current)) throw CustomerIdentityException.conflict();
        claim(operation.companyId(), operation.documentNumber(), operation.customerId());
        claimCode(operation.companyId(), profile.code(), operation.customerId());
        // The trigger sees a finalized operation; a later write error rolls this back too.
        jdbc.update("""
                update saas_customer_identity_operation
                set status = 'COMMITTED', finalized_payload_hash = ?, committed_at = ? where operation_id = ?
                """, profileHash, Timestamp.from(clock.instant()), operationId);
        if (current == null) {
            jdbc.update("""
                    insert into saas_erp_customer(id, company_id, code, name, tax_id, document_type,
                      identity_revision, phone, email, address_json, active, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), true, ?)
                    """, operation.customerId(), operation.companyId(), profile.code(), profile.name(),
                    operation.documentNumber(), operation.documentType(), operation.revision(), profile.phone(),
                    profile.email(), profile.addressJson(), Timestamp.from(clock.instant()));
            jdbc.update("""
                    insert into saas_customer_identity_link(installation_id, local_customer_id, company_id, customer_id)
                    values (?, ?, ?, ?)
                    """, operation.installationId(), operation.localCustomerId(), operation.companyId(), operation.customerId());
        } else {
            jdbc.update("""
                    update saas_erp_customer set code = ?, name = ?, tax_id = ?, document_type = ?,
                      identity_revision = ?, phone = ?, email = ?, address_json = cast(? as jsonb)
                    where id = ? and company_id = ? and identity_revision = ?
                    """, profile.code(), profile.name(), operation.documentNumber(), operation.documentType(),
                    operation.revision(), profile.phone(), profile.email(), profile.addressJson(),
                    operation.customerId(), operation.companyId(), operation.expectedRevision());
        }
    }

    private SaasInstallation authenticate(UUID companyId, UUID storeId, String token) {
        if (companyId == null || storeId == null) throw CustomerIdentityException.invalid();
        return authenticator.requireLinkedInstallation(companyId, storeId,
                installations.findByCompany_IdAndStore_Id(companyId, storeId), token);
    }

    private void lockCompany(UUID companyId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", Object.class,
                companyId.toString());
    }

    private void lockOperation(UUID operationId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?::text, 1))", Object.class,
                operationId.toString());
    }

    private void claim(UUID companyId, String number, UUID customerId) {
        jdbc.update("""
                insert into saas_customer_document_claim(company_id, document_number, customer_id)
                values (?, ?, ?) on conflict do nothing
                """, companyId, number, customerId);
        UUID owner = jdbc.queryForObject("""
                select customer_id from saas_customer_document_claim where company_id = ? and document_number = ?
                """, UUID.class, companyId, number);
        if (!customerId.equals(owner)) throw CustomerIdentityException.duplicate();
    }

    private Long currentRevision(UUID customerId, UUID companyId) {
        List<Long> revisions = jdbc.query("""
                select identity_revision from saas_erp_customer where id = ? and company_id = ?
                """, (rs, index) -> rs.getLong(1), customerId, companyId);
        return revisions.isEmpty() ? null : revisions.getFirst();
    }

    private void claimCode(UUID companyId, String code, UUID customerId) {
        jdbc.update("""
                insert into saas_customer_code_claim(company_id, code, customer_id)
                values (?, ?, ?) on conflict do nothing
                """, companyId, code, customerId);
        UUID owner = jdbc.queryForObject("select customer_id from saas_customer_code_claim where company_id = ? and code = ?",
                UUID.class, companyId, code);
        if (!customerId.equals(owner)) throw CustomerIdentityException.conflict();
    }

    private Operation operation(UUID operationId) {
        List<Operation> matches = jdbc.query("select * from saas_customer_identity_operation where operation_id = ?",
                (rs, index) -> operation(rs), operationId);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static Operation operation(ResultSet rs) throws SQLException {
        return new Operation(rs.getObject("operation_id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("store_id", UUID.class), rs.getObject("installation_id", UUID.class),
                rs.getObject("local_customer_id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getObject("expected_revision", Long.class), rs.getLong("revision"), rs.getString("document_type"),
                rs.getString("document_number"), rs.getString("request_hash"), rs.getString("status"),
                rs.getString("finalized_payload_hash"), rs.getString("client_code"), rs.getString("prepared_profile_hash"));
    }

    private static void requireOwner(Operation operation, SaasInstallation installation, UUID localCustomerId) {
        if (operation == null || !operation.installationId().equals(installation.getId())
                || !operation.companyId().equals(installation.getCompany().getId())
                || !operation.storeId().equals(installation.getStore().getId())
                || !operation.localCustomerId().equals(localCustomerId)) throw CustomerIdentityException.conflict();
    }

    private String identityHash(CustomerIdentityReservationRequest request, CustomerDocumentIdentity identity, String profileHash) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("localCustomerId", request.localCustomerId().toString());
        values.put("expectedCustomerId", request.expectedCustomerId() == null ? null : request.expectedCustomerId().toString());
        values.put("expectedRevision", request.expectedRevision());
        values.put("documentType", identity.documentType());
        values.put("documentNumber", identity.documentNumber());
        values.put("profileHash", profileHash);
        return hash(values);
    }

    private String hash(Map<String, Object> values) {
        try {
            String canonical = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(values);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw CustomerIdentityException.conflict();
        }
    }

    private Profile profile(Map<String, Object> payload) {
        String address = null;
        Object rawAddress = payload.get("address");
        if (rawAddress != null) {
            if (!(rawAddress instanceof Map<?, ?>)) throw CustomerIdentityException.conflict();
            try {
                address = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(rawAddress);
            } catch (Exception exception) {
                throw CustomerIdentityException.conflict();
            }
            if (address.length() > 4000) throw CustomerIdentityException.conflict();
        }
        return new Profile(text(payload.get("clientId"), 40, true), text(payload.get("fiscalName"), 255, true),
                text(payload.get("phone"), 64, false), text(payload.get("email"), 320, false), address);
    }

    private void requireMasterCapacity(UUID companyId) {
        Long used = jdbc.queryForObject("""
                select (select count(*) from saas_erp_customer where company_id = ?)
                     + (select count(*) from saas_erp_product where company_id = ?)
                     + (select count(*) from saas_erp_supplier where company_id = ?)
                     + (select count(*) from saas_erp_warehouse where company_id = ?)
                     + saas_customer_reserved_master_count(?)
                """, Long.class, companyId, companyId, companyId, companyId, companyId);
        Long limit = jdbc.queryForObject("select saas_plan_limit(?, 'max_master_records')", Long.class, companyId);
        if (used == null || limit == null || used >= limit) throw CustomerIdentityException.conflict();
    }

    private static String text(Object value, int max, boolean required) {
        if (value == null) {
            if (required) throw CustomerIdentityException.conflict();
            return null;
        }
        if (!(value instanceof String raw)) throw CustomerIdentityException.conflict();
        String normalized = raw.trim();
        if (normalized.length() > max || required && normalized.isEmpty()) throw CustomerIdentityException.conflict();
        return normalized.isEmpty() ? null : normalized;
    }

    private static UUID uuid(Object value) {
        try { return UUID.fromString(Objects.toString(value, "")); }
        catch (IllegalArgumentException exception) { throw CustomerIdentityException.conflict(); }
    }

    private record Profile(String code, String name, String phone, String email, String addressJson) {
        Map<String, Object> asMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("clientId", code); values.put("fiscalName", name); values.put("phone", phone);
            values.put("email", email); values.put("address", addressJson);
            return values;
        }
    }

    private record Operation(UUID operationId, UUID companyId, UUID storeId, UUID installationId,
            UUID localCustomerId, UUID customerId, Long expectedRevision, long revision, String documentType,
            String documentNumber, String requestHash, String status, String finalizedPayloadHash,
            String clientCode, String preparedProfileHash) {
        CustomerIdentityReservationResponse response() {
            return new CustomerIdentityReservationResponse(operationId, customerId, revision, documentType, documentNumber);
        }
    }
}
