package com.tpverp.saas.customer;

import static com.tpverp.saas.customer.CustomerAdoptionApi.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.sync.SaasSyncEvent;
import com.tpverp.saas.sync.SyncOperation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** An explicit selected central identity, never an automatic merge or a central profile update. */
@Service
public class CustomerAdoptionService {
    private final JdbcTemplate jdbc;
    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator authenticator;
    private final ObjectMapper mapper;

    public CustomerAdoptionService(JdbcTemplate jdbc, SaasInstallationRepository installations,
            InstallationAuthenticator authenticator, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.installations = installations;
        this.authenticator = authenticator;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Profile lookup(Lookup request, String token) {
        if (request == null || request.documentType() == null) throw CustomerIdentityException.invalid();
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        var identity = CustomerDocumentIdentity.validate(request.documentType(), request.documentNumber());
        List<UUID> ids = jdbc.queryForList("""
                select id from saas_erp_customer where company_id = ?
                  and saas_customer_document_key(tax_id) = ?
                """, UUID.class, installation.getCompany().getId(), identity.documentNumber());
        if (ids.isEmpty()) throw CustomerIdentityException.notFound();
        return profile(installation, ids.getFirst());
    }

    @Transactional
    public Reservation reserve(Reserve request, String token) {
        if (request == null || request.operationId() == null || request.localCustomerId() == null
                || request.customerId() == null || request.expectedRevision() == null
                || request.expectedRevision() < 0 || request.documentType() == null) throw CustomerIdentityException.invalid();
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        var identity = CustomerDocumentIdentity.validate(request.documentType(), request.documentNumber());
        lock(request.operationId(), installation.getCompany().getId());
        Operation prior = operation(request.operationId());
        if (prior != null) {
            owner(prior, installation, request.localCustomerId());
            if (prior.status().equals("CANCELLED") || !Objects.equals(prior.customerId(), request.customerId())
                    || !Objects.equals(prior.revision(), request.expectedRevision())
                    || !Objects.equals(prior.documentType(), identity.documentType())
                    || !Objects.equals(prior.documentNumber(), identity.documentNumber())) throw CustomerIdentityException.conflict();
            return new Reservation(prior.id(), prior.localId(), deserialize(prior.profileJson(), Profile.class));
        }
        Profile selected = profile(installation, request.customerId());
        if (!selected.active() || selected.revision() != request.expectedRevision()
                || !selected.documentType().equals(identity.documentType())
                || !selected.documentNumber().equals(identity.documentNumber())) throw CustomerIdentityException.conflict();
        requireAvailableLink(installation, request.localCustomerId(), selected.customerId());
        boolean busy = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from saas_customer_adoption_operation
                    where installation_id = ? and status = 'RESERVED'
                      and (local_customer_id = ? or customer_id = ?))
                    or exists(select 1 from saas_customer_identity_operation
                        where status = 'RESERVED' and ((company_id = ? and customer_id = ?)
                          or (installation_id = ? and local_customer_id = ?)))
                """, Boolean.class, installation.getId(), request.localCustomerId(), selected.customerId(),
                installation.getCompany().getId(), selected.customerId(), installation.getId(), request.localCustomerId()));
        if (busy) throw CustomerIdentityException.conflict();
        jdbc.update("""
                insert into saas_customer_adoption_operation(operation_id, company_id, store_id,
                    installation_id, local_customer_id, customer_id, expected_revision, document_type,
                    document_number, profile_json, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), 'RESERVED')
                """, request.operationId(), installation.getCompany().getId(), installation.getStore().getId(),
                installation.getId(), request.localCustomerId(), selected.customerId(), selected.revision(),
                selected.documentType(), selected.documentNumber(), serialize(selected));
        return new Reservation(request.operationId(), request.localCustomerId(), selected);
    }

    @Transactional
    public void cancel(UUID operationId, CustomerIdentityOwnerRequest request, String token) {
        if (request == null || operationId == null || request.localCustomerId() == null) throw CustomerIdentityException.invalid();
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        lock(operationId, installation.getCompany().getId());
        Operation operation = operation(operationId);
        if (operation == null) {
            // Tombstone prevents a delayed HTTP reserve from reviving a rolled-back local attempt.
            jdbc.update("""
                    insert into saas_customer_adoption_operation(operation_id, company_id, store_id,
                        installation_id, local_customer_id, status)
                    values (?, ?, ?, ?, ?, 'CANCELLED')
                    """, operationId, installation.getCompany().getId(), installation.getStore().getId(),
                    installation.getId(), request.localCustomerId());
            return;
        }
        owner(operation, installation, request.localCustomerId());
        if (operation.status().equals("COMMITTED")) throw CustomerIdentityException.conflict();
        jdbc.update("update saas_customer_adoption_operation set status = 'CANCELLED' where operation_id = ?", operationId);
    }

    public boolean supports(String type, SyncOperation operation) {
        return "CUSTOMER_ADOPTION".equals(type) && operation == SyncOperation.ACTUALIZAR;
    }

    @Transactional
    public void finalizeAdoption(SaasSyncEvent event, Map<String, Object> payload) {
        if (event == null || event.getInstallation() == null || !event.getInstallation().isActive()
                || event.getCompany() == null || event.getStore() == null || event.getEntityId() == null
                || !supports(event.getEntityType(), event.getOperation()) || payload == null
                || !payload.keySet().equals(Set.of("operationId"))) throw CustomerIdentityException.conflict();
        SaasInstallation installation = event.getInstallation();
        if (!installation.getCompany().getId().equals(event.getCompany().getId())
                || !installation.getStore().getId().equals(event.getStore().getId())) throw CustomerIdentityException.conflict();
        UUID operationId = uuid(payload.get("operationId"));
        lock(operationId, event.getCompany().getId());
        Operation operation = operation(operationId);
        owner(operation, installation, event.getEntityId());
        if (operation.status().equals("CANCELLED")) throw CustomerIdentityException.conflict();
        requireAvailableLink(installation, operation.localId(), operation.customerId());
        jdbc.update("""
                insert into saas_customer_identity_link(installation_id, local_customer_id, company_id, customer_id)
                values (?, ?, ?, ?) on conflict do nothing
                """, installation.getId(), operation.localId(), operation.companyId(), operation.customerId());
        requireAvailableLink(installation, operation.localId(), operation.customerId());
        // Master/profile/revision are deliberately untouched, even if they changed after reservation.
        jdbc.update("""
                update saas_customer_adoption_operation set status = 'COMMITTED', committed_at = coalesce(committed_at, now())
                where operation_id = ?
                """, operationId);
    }

    private Profile profile(SaasInstallation installation, UUID customerId) {
        List<Profile> rows = jdbc.query("""
                select c.id, c.identity_revision, c.code, c.name, c.document_type, c.tax_id,
                       c.address_json::text, c.phone, c.email, c.active, l.local_customer_id
                from saas_erp_customer c left join saas_customer_identity_link l
                  on l.customer_id = c.id and l.company_id = c.company_id and l.installation_id = ?
                where c.company_id = ? and c.id = ?
                """, (row, index) -> {
            if (row.getString("document_type") == null) throw CustomerIdentityException.conflict();
            var identity = CustomerDocumentIdentity.validate(row.getString("document_type"), row.getString("tax_id"));
            String address = row.getString("address_json");
            return new Profile(row.getObject("id", UUID.class), row.getLong("identity_revision"), row.getString("code"),
                    row.getString("name"), identity.documentType(), identity.documentNumber(),
                    address == null ? null : deserialize(address, Address.class), row.getString("phone"), row.getString("email"),
                    row.getBoolean("active"), row.getObject("local_customer_id", UUID.class));
        }, installation.getId(), installation.getCompany().getId(), customerId);
        if (rows.isEmpty()) throw CustomerIdentityException.notFound();
        return rows.getFirst();
    }

    private void requireAvailableLink(SaasInstallation installation, UUID localId, UUID centralId) {
        List<Map<String, Object>> links = jdbc.queryForList("""
                select company_id, local_customer_id, customer_id from saas_customer_identity_link
                where installation_id = ? and (local_customer_id = ? or customer_id = ?)
                """, installation.getId(), localId, centralId);
        if (links.size() > 1 || links.stream().anyMatch(link -> !installation.getCompany().getId().equals(link.get("company_id"))
                || !localId.equals(link.get("local_customer_id")) || !centralId.equals(link.get("customer_id")))) {
            throw CustomerIdentityException.conflict();
        }
    }

    private SaasInstallation authenticate(UUID companyId, UUID storeId, String token) {
        if (companyId == null || storeId == null) throw CustomerIdentityException.invalid();
        return authenticator.requireLinkedInstallation(companyId, storeId,
                installations.findByCompany_IdAndStore_Id(companyId, storeId), token);
    }

    private void lock(UUID operationId, UUID companyId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?::text, 1))", Object.class, operationId.toString());
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", Object.class, companyId.toString());
    }

    private Operation operation(UUID id) {
        return jdbc.query("select * from saas_customer_adoption_operation where operation_id = ?",
                (row, index) -> operation(row), id).stream().findFirst().orElse(null);
    }

    private static Operation operation(ResultSet row) throws SQLException {
        return new Operation(row.getObject("operation_id", UUID.class), row.getObject("company_id", UUID.class),
                row.getObject("store_id", UUID.class), row.getObject("installation_id", UUID.class),
                row.getObject("local_customer_id", UUID.class), row.getObject("customer_id", UUID.class),
                row.getObject("expected_revision", Long.class), row.getString("document_type"), row.getString("document_number"),
                row.getString("profile_json"), row.getString("status"));
    }

    private static void owner(Operation operation, SaasInstallation installation, UUID localId) {
        if (operation == null || !operation.installationId().equals(installation.getId())
                || !operation.companyId().equals(installation.getCompany().getId())
                || !operation.storeId().equals(installation.getStore().getId())
                || !operation.localId().equals(localId)) throw CustomerIdentityException.conflict();
    }

    private String serialize(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw CustomerIdentityException.conflict(); }
    }

    private <T> T deserialize(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (Exception exception) { throw CustomerIdentityException.conflict(); }
    }

    private static UUID uuid(Object raw) {
        if (!(raw instanceof String value) || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw CustomerIdentityException.conflict();
        }
        return UUID.fromString(value);
    }

    private record Operation(UUID id, UUID companyId, UUID storeId, UUID installationId, UUID localId,
            UUID customerId, Long revision, String documentType, String documentNumber, String profileJson, String status) { }
}
