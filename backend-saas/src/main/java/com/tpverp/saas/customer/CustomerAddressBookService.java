package com.tpverp.saas.customer;

import static com.tpverp.saas.customer.CustomerAddressBookApi.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.sync.SaasSyncEvent;
import com.tpverp.saas.sync.SyncOperation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Independent shared contact data: identity revisions and fiscal snapshots are untouched. */
@Service
public class CustomerAddressBookService {
    private final jakarta.persistence.EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator authenticator;
    private final ObjectMapper mapper;

    public CustomerAddressBookService(jakarta.persistence.EntityManager entityManager, JdbcTemplate jdbc,
            SaasInstallationRepository installations, InstallationAuthenticator authenticator, ObjectMapper mapper) {
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.installations = installations;
        this.authenticator = authenticator;
        this.mapper = mapper;
    }

    @Transactional
    public Snapshot snapshot(Request request, String token) {
        if (request == null || request.companyId() == null || request.storeId() == null
                || request.localCustomerId() == null || request.customerId() == null) throw invalid();
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        requireLink(installation, request.localCustomerId(), request.customerId());
        initializeLegacy(request.companyId(), request.customerId());
        Snapshot book = read(request.companyId(), request.customerId());
        String status = null;
        if (request.operationId() != null) {
            var operations = jdbc.queryForList("""
                    select installation_id, store_id, company_id, local_customer_id, customer_id, status
                    from saas_customer_address_operation where operation_id = ?
                    """, request.operationId());
            status = "NOT_FOUND";
            if (!operations.isEmpty()) {
                requireOwner(operations.getFirst(), installation, request.localCustomerId(), request.customerId());
                status = (String) operations.getFirst().get("status");
            }
        }
        return new Snapshot(book.customerId(), book.revision(), book.addresses(), status, request.operationId());
    }

    public boolean supports(String type, SyncOperation operation) {
        return "CUSTOMER_ADDRESSES".equals(type) && operation == SyncOperation.ACTUALIZAR;
    }

    /** Stale revisions are final domain outcomes; malformed or unauthorized events roll back. */
    @Transactional
    public void project(SaasSyncEvent event, Map<String, Object> payload) {
        if (event == null || event.getInstallation() == null || !event.getInstallation().isActive()
                || event.getCompany() == null || event.getStore() == null || event.getEntityId() == null
                || !supports(event.getEntityType(), event.getOperation()) || payload == null
                || !payload.keySet().equals(Set.of("operationId", "customerId", "expectedRevision", "addresses"))) throw invalid();
        SaasInstallation installation = event.getInstallation();
        if (!event.getCompany().getId().equals(installation.getCompany().getId())
                || !event.getStore().getId().equals(installation.getStore().getId())) throw invalid();
        UUID operationId = uuid(payload.get("operationId"));
        UUID customerId = uuid(payload.get("customerId"));
        long expected = revision(payload.get("expectedRevision"));
        List<Entry> entries = normalize(payload.get("addresses"));
        String addresses = serialize(entries);
        String hash = hash(serialize(Map.of("customerId", customerId, "expectedRevision", expected, "addresses", entries)));
        lockCompany(installation.getCompany().getId());
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?::text, 3))", Object.class, operationId.toString());
        requireLink(installation, event.getEntityId(), customerId);
        var previous = jdbc.queryForList("select * from saas_customer_address_operation where operation_id = ?", operationId);
        if (!previous.isEmpty()) {
            var prior = previous.getFirst();
            requireOwner(prior, installation, event.getEntityId(), customerId);
            if (!hash.equals(prior.get("payload_hash"))) throw conflict();
            return;
        }
        initializeLegacy(installation.getCompany().getId(), customerId);
        int updated = jdbc.update("""
                update saas_customer_address_book set revision = revision + 1, addresses_json = cast(? as jsonb)
                where company_id = ? and customer_id = ? and revision = ? and revision < 9223372036854775807
                """, addresses, installation.getCompany().getId(), customerId, expected);
        jdbc.update("""
                insert into saas_customer_address_operation(operation_id, company_id, customer_id, installation_id,
                    store_id, local_customer_id, expected_revision, payload_hash, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, operationId, installation.getCompany().getId(), customerId, installation.getId(),
                installation.getStore().getId(), event.getEntityId(), expected, hash, updated == 1 ? "APPLIED" : "CONFLICT");
    }

    /** Called only for initial identity creation, within the same transaction as its master. */
    void initialize(UUID companyId, UUID customerId, List<Entry> entries) {
        if (entries == null) { initializeLegacy(companyId, customerId); return; }
        jdbc.update("""
                insert into saas_customer_address_book(company_id, customer_id, addresses_json)
                values (?, ?, cast(? as jsonb)) on conflict (company_id, customer_id) do nothing
                """, companyId, customerId, serialize(entries));
    }

    private void initializeLegacy(UUID companyId, UUID customerId) {
        jdbc.update("""
                insert into saas_customer_address_book(company_id, customer_id, addresses_json)
                select company_id, id, jsonb_build_array(jsonb_build_object('id', id,
                    'address', coalesce(address_json, '{}'::jsonb), 'phone', phone, 'email', email, 'notes', null))
                from saas_erp_customer where company_id = ? and id = ?
                on conflict (company_id, customer_id) do nothing
                """, companyId, customerId);
    }

    /** Read-only fallback covers centrally created masters after the migration. */
    Snapshot read(UUID companyId, UUID customerId) {
        var rows = jdbc.query("""
                select c.id, coalesce(b.revision, 1) as revision,
                    coalesce(b.addresses_json, jsonb_build_array(jsonb_build_object('id', c.id,
                    'address', coalesce(c.address_json, '{}'::jsonb), 'phone', c.phone,
                    'email', c.email, 'notes', null)))::text as addresses
                from saas_erp_customer c left join saas_customer_address_book b
                    on b.company_id = c.company_id and b.customer_id = c.id
                where c.company_id = ? and c.id = ?
                """, (row, index) -> new Snapshot(row.getObject("id", UUID.class), row.getLong("revision"),
                        deserialize(row.getString("addresses")), null, null), companyId, customerId);
        if (rows.isEmpty()) throw CustomerIdentityException.notFound();
        return rows.getFirst();
    }

    private SaasInstallation authenticate(UUID companyId, UUID storeId, String token) {
        var candidates = installations.findByCompany_IdAndStore_Id(companyId, storeId);
        if (candidates.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Instalacion no autorizada");
        lockCompany(companyId);
        candidates.forEach(value -> entityManager.refresh(value, jakarta.persistence.LockModeType.PESSIMISTIC_READ));
        return authenticator.requireLinkedInstallation(companyId, storeId, candidates, token);
    }

    private void lockCompany(UUID companyId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", Object.class, companyId.toString());
    }

    private void requireLink(SaasInstallation installation, UUID localId, UUID customerId) {
        Boolean linked = jdbc.queryForObject("""
                select exists(select 1 from saas_customer_identity_link
                    where installation_id = ? and company_id = ? and local_customer_id = ? and customer_id = ?)
                """, Boolean.class, installation.getId(), installation.getCompany().getId(), localId, customerId);
        if (!Boolean.TRUE.equals(linked)) throw conflict();
    }

    private static void requireOwner(Map<String, Object> operation, SaasInstallation installation, UUID localId, UUID customerId) {
        if (!installation.getId().equals(operation.get("installation_id"))
                || !installation.getCompany().getId().equals(operation.get("company_id"))
                || !installation.getStore().getId().equals(operation.get("store_id"))
                || !localId.equals(operation.get("local_customer_id")) || !customerId.equals(operation.get("customer_id"))) throw conflict();
    }

    static List<Entry> normalize(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 50) throw invalid();
        List<Entry> entries = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> value)
                    || !Set.of("id", "address", "phone", "email", "notes").containsAll(value.keySet())) throw invalid();
            UUID id = uuid(value.get("id"));
            if (!ids.add(id)) throw invalid();
            Object rawAddress = value.get("address");
            if (!(rawAddress instanceof Map<?, ?> address)
                    || !Set.of("address", "postalCode", "city", "province", "country").containsAll(address.keySet())) throw invalid();
            String country = text(address.get("country"), 2);
            if (country != null && !country.matches("[A-Za-z]{2}")) throw invalid();
            entries.add(new Entry(id, new CustomerAdoptionApi.Address(text(address.get("address"), 255),
                    text(address.get("postalCode"), 16), text(address.get("city"), 128), text(address.get("province"), 128),
                    country == null ? null : country.toUpperCase(Locale.ROOT)), text(value.get("phone"), 64),
                    text(value.get("email"), 320), text(value.get("notes"), 65536)));
        }
        return List.copyOf(entries);
    }

    static long revision(Object raw) {
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long
                || raw instanceof java.math.BigInteger)) throw invalid();
        try {
            long value = new BigDecimal(raw.toString()).longValueExact();
            if (value < 1) throw invalid();
            return value;
        } catch (ArithmeticException exception) { throw invalid(); }
    }

    private List<Entry> deserialize(String json) {
        try { return normalize(mapper.readValue(json, List.class)); }
        catch (Exception exception) { throw invalid(); }
    }

    private String serialize(Object value) {
        try { return mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value); }
        catch (Exception exception) { throw invalid(); }
    }

    private static String text(Object value, int max) {
        if (value == null) return null;
        if (!(value instanceof String text)) throw invalid();
        text = text.trim();
        if (text.length() > max) throw invalid();
        return text.isEmpty() ? null : text;
    }

    private static UUID uuid(Object raw) {
        if (!(raw instanceof String value) || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid();
        return UUID.fromString(value);
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "CUSTOMER_ADDRESSES_INVALID");
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "CUSTOMER_ADDRESSES_CONFLICT");
    }
}
