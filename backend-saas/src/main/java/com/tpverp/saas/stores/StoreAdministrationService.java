package com.tpverp.saas.stores;

import static com.tpverp.saas.stores.StoreWorkspaceApi.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.AdminAuditService;
import com.tpverp.saas.license.*;
import com.tpverp.saas.plan.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StoreAdministrationService {
    private static final String SELECT = """
            select s.*, c.name company_name,
                   (select count(*) from saas_installation i where i.store_id=s.id) installations,
                   (select count(*) from saas_installation i where i.store_id=s.id and i.active) active_installations,
                   (select max(e.received_at) from saas_sync_event e where e.company_id=s.company_id and e.store_id=s.id) last_sync_at,
                   (exists(select 1 from saas_license l where l.store_id=s.id)
                    or exists(select 1 from saas_pairing_code p where p.store_id=s.id)
                    or exists(select 1 from saas_installation i where i.store_id=s.id)) tax_regime_locked
            from saas_store s join saas_company c on c.id=s.company_id
            """;
    private final SaasStoreRepository stores;
    private final SaasCompanyRepository companies;
    private final SaasLicenseRepository licenses;
    private final PlanLimitService limits;
    private final AdminAuditService audit;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper mapper;
    private final Clock clock;

    public StoreAdministrationService(SaasStoreRepository stores, SaasCompanyRepository companies, SaasLicenseRepository licenses,
            PlanLimitService limits, AdminAuditService audit, JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.stores = stores; this.companies = companies; this.licenses = licenses; this.limits = limits; this.audit = audit;
        this.jdbc = jdbc; this.named = new NamedParameterJdbcTemplate(jdbc); this.mapper = mapper; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<StoreRow> list(UUID companyId, String query, Boolean active, int page, int size) {
        return list(companyId, query, active, page, size, "companyName", "ASC");
    }

    @Transactional(readOnly = true)
    public Page<StoreRow> list(UUID companyId, String query, Boolean active, int page, int size,
            String sortBy, String sortDirection) {
        validatePage(page, size);
        String order = sortOrder(sortBy, sortDirection);
        var params = new LinkedHashMap<String, Object>();
        var where = new StringBuilder(" where 1=1");
        if (companyId != null) { where.append(" and s.company_id=:companyId"); params.put("companyId", companyId); }
        if (active != null) { where.append(" and s.active=:active"); params.put("active", active); }
        String q = searchPattern(query);
        if (q != null) {
            where.append(" and (s.internal_code ilike :q or s.code ilike :q or s.name ilike :q or c.name ilike :q or c.tax_id ilike :q)");
            params.put("q", q);
        }
        long total = named.queryForObject("select count(*) from saas_store s join saas_company c on c.id=s.company_id" + where, params, Long.class);
        params.put("limit", size); params.put("offset", (long) page * size);
        var rows = named.query(SELECT + where + " order by " + order + " limit :limit offset :offset", params, (rs, n) -> row(rs));
        return new Page<>(rows, page, size, total, (int) Math.ceil((double) total / size));
    }

    private static String sortOrder(String sortBy, String sortDirection) {
        String key = sortBy == null ? "companyName" : sortBy;
        String column = switch (key) {
            case "internalCode" -> "s.internal_code";
            case "companyName" -> "c.name";
            case "code" -> "s.code";
            case "name" -> "s.name";
            case "active" -> "s.active";
            case "taxRegime" -> "s.tax_regime";
            case "commercialProfile" -> "s.commercial_profile";
            case "servicePrice" -> "s.service_price";
            case "billingPeriod" -> "s.billing_period";
            case "validUntil" -> "s.valid_until";
            case "maxWindows" -> "s.max_windows";
            case "maxPda" -> "s.max_pda";
            case "installations" -> "installations";
            case "activeInstallations" -> "active_installations";
            case "lastSyncAt" -> "last_sync_at";
            case "createdAt" -> "s.created_at";
            default -> throw badRequest("Columna de ordenacion no valida");
        };
        String direction = sortDirection == null ? "ASC" : sortDirection.toUpperCase(Locale.ROOT);
        if (!Set.of("ASC", "DESC").contains(direction)) throw badRequest("Direccion de ordenacion no valida");
        return column + " " + direction + " nulls last"
                + (key.equals("companyName") ? ",s.code ASC" : "") + ",s.id ASC";
    }

    @Transactional
    public StoreRow create(UUID companyId, CreateStore request) {
        var company = companies.findById(companyId).orElseThrow(() -> missing("Empresa no encontrada"));
        var address = requireSpanishStoreAddress(request.storeAddress());
        var store = validate(() -> new SaasStore(UUID.randomUUID(), company,
                LicenseProvisioningData.storeCode(request.code()),
                LicenseProvisioningData.requiredName(request.name(), "name", 200), address,
                LicenseProvisioningData.timeZoneId(request.timeZoneId()), clock.instant()));
        if (request.taxRegime() == null) throw badRequest("Selecciona el regimen fiscal de la tienda");
        store.setTaxRegime(request.taxRegime());
        var commercialProfile = request.commercialProfile() == null
                ? company.getCommercialProfile() : request.commercialProfile();
        if (commercialProfile == null) throw badRequest("Selecciona el perfil comercial de la tienda");
        store.setCommercialProfile(commercialProfile);
        validatePrice(request.servicePrice(), request.billingPeriod());
        validateLicenseConfiguration(request.validUntil(), request.maxWindows(), request.maxPda(), true);
        store.updateServicePrice(request.servicePrice(), request.billingPeriod());
        store.updateLicenseConfiguration(request.validUntil(), request.maxWindows(), request.maxPda());
        limits.requireCapacity(companyId, PlanResource.STORES);
        try { stores.saveAndFlush(store); }
        catch (DataIntegrityViolationException exception) { throw conflict("El codigo fiscal de tienda ya existe o la numeracion interna esta agotada"); }
        audit.log("CREATE_STORE", "STORE", store.getId().toString());
        return get(store.getId());
    }

    @Transactional
    public StoreRow update(UUID id, UpdateStore request) {
        return update(id, request, false);
    }

    @Transactional
    public StoreRow update(UUID id, UpdateStore request, boolean mayRenewLicense) {
        lockCompanyForStore(id);
        var store = stores.findByIdForUpdate(id).orElseThrow(() -> missing("Tienda no encontrada"));
        var address = requireSpanishStoreAddress(request.storeAddress());
        if (request.taxRegime() == null) throw badRequest("Selecciona el regimen fiscal de la tienda");
        if (store.getTaxRegime() != request.taxRegime() && get(id).taxRegimeLocked()) {
            throw conflict("El regimen fiscal de una tienda con licencia no puede cambiar");
        }
        validatePrice(request.servicePrice(), request.billingPeriod());
        boolean configurationChanged = !Objects.equals(store.getValidUntil(), request.validUntil())
                || store.getMaxWindows() != request.maxWindows() || store.getMaxPda() != request.maxPda();
        validateLicenseConfiguration(request.validUntil(), request.maxWindows(), request.maxPda(), configurationChanged);
        if (configurationChanged) {
            var associated = associatedLicenseReferences(jdbc, id);
            if (!associated.isEmpty()) {
                if (!mayRenewLicense) throw forbidden("Se requiere permiso para configurar la licencia de esta tienda");
                if (associated.size() != 1) throw conflict("Configura las licencias historicas desde Licencias; la tienda tiene varias licencias");
                var license = licenses.findByReferenceForUpdate(associated.getFirst()).orElseThrow(() -> missing("Licencia no encontrada"));
                if (!isExclusiveStoreLicense(jdbc, license.getId(), id)) {
                    throw conflict("Configura la licencia historica desde Licencias; no pertenece exclusivamente a esta tienda");
                }
                license.renew(request.validUntil(), request.maxWindows(), request.maxPda());
                audit.log("RENEW_LICENSE", "LICENSE", license.getReference(), "source=STORE");
            }
        }
        store.setTaxRegime(request.taxRegime());
        if (request.commercialProfile() != null) store.setCommercialProfile(request.commercialProfile());
        store.updateServicePrice(request.servicePrice(), request.billingPeriod());
        if (configurationChanged) store.updateLicenseConfiguration(request.validUntil(), request.maxWindows(), request.maxPda());
        validate(() -> { store.updateAdministration(request.name(), address, request.timeZoneId(), request.active()); return store; });
        stores.flush();
        audit.log("UPDATE_STORE", "STORE", id.toString(), "active=" + request.active());
        return get(id);
    }

    private static void validatePrice(BigDecimal price, StoreBillingPeriod period) {
        if (price == null || price.signum() < 0 || price.scale() > 2
                || price.precision() - price.scale() > 17 || period == null) {
            throw badRequest("Indica el precio SaaS en euros con hasta dos decimales y su periodicidad");
        }
    }

    private void validateLicenseConfiguration(Instant validUntil, int maxWindows, int maxPda, boolean requireFuture) {
        if ((requireFuture && (validUntil == null || !validUntil.isAfter(clock.instant()))) || maxWindows < 1 || maxPda < 0) {
            throw badRequest("Validez o cupos de licencia no validos");
        }
    }

    static List<String> associatedLicenseReferences(JdbcTemplate jdbc, UUID storeId) {
        return jdbc.queryForList("""
                select l.reference from saas_license l
                where l.store_id=? or exists(select 1 from saas_pairing_code p where p.license_id=l.id and p.store_id=?)
                    or exists(select 1 from saas_installation i where i.license_id=l.id and i.store_id=?)
                order by l.reference
                """, String.class, storeId, storeId, storeId);
    }

    public static boolean isExclusiveStoreLicense(JdbcTemplate jdbc, UUID licenseId, UUID storeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from saas_license where id=? and store_id=?)
                    and not exists(select 1 from saas_license l where l.id<>? and
                        (l.store_id=? or exists(select 1 from saas_pairing_code p where p.license_id=l.id and p.store_id=?)
                         or exists(select 1 from saas_installation i where i.license_id=l.id and i.store_id=?)))
                    and not exists(select 1 from saas_pairing_code where license_id=? and store_id<>?)
                    and not exists(select 1 from saas_installation where license_id=? and store_id<>?)
                """, Boolean.class, licenseId, storeId, licenseId, storeId, storeId, storeId, licenseId, storeId, licenseId, storeId));
    }

    @Transactional
    public StoreRow assignCode(UUID id, AssignCode request) {
        lockCompanyForStore(id);
        var store = stores.findByIdForUpdate(id).orElseThrow(() -> missing("Tienda no encontrada"));
        if (store.getInternalCode() != null) {
            if (request.internalCode() == null || store.getInternalCode().equals(request.internalCode())) return get(id);
            throw conflict("El codigo interno de tienda es permanente");
        }
        var address = requireSpanishStoreAddress(store.getStoreAddress());
        String code = request.internalCode();
        if (code != null && (!code.matches("[0-9]{7}") || code.endsWith("00000")
                || !code.startsWith(address.get("codigoPostal").substring(0, 2)))) {
            throw badRequest("Codigo interno incompatible con el codigo postal de la tienda");
        }
        try { jdbc.update("update saas_store set internal_code=? where id=?", code, id); }
        catch (DataIntegrityViolationException exception) { throw conflict("Codigo interno ocupado o numeracion agotada"); }
        audit.log("ASSIGN_STORE_CODE", "STORE", id.toString(), request.reason().trim());
        return get(id);
    }

    @Transactional
    public StoreRow updateActivity(UUID id, boolean active) {
        lockCompanyForStore(id);
        var store = stores.findByIdForUpdate(id).orElseThrow(() -> missing("Tienda no encontrada"));
        store.setActive(active);
        stores.flush();
        audit.log("UPDATE_STORE_ACTIVITY", "STORE", id.toString(), "active=" + active);
        return get(id);
    }

    public static Map<String, String> requireSpanishStoreAddress(Map<String, String> address) {
        var normalized = validate(() -> LicenseProvisioningData.fiscalAddress(address, "storeAddress"));
        if (!"ES".equals(normalized.get("pais")) || !normalized.get("codigoPostal").matches("(0[1-9]|[1-4][0-9]|5[0-2])[0-9]{3}")) {
            throw badRequest("La tienda requiere domicilio de Espana y codigo postal con prefijo entre 01 y 52; los prefijos 9 quedan reservados");
        }
        return normalized;
    }

    @Transactional(readOnly = true)
    public StoreRow get(UUID id) {
        return jdbc.query(SELECT + " where s.id=?", (rs, n) -> row(rs), id).stream().findFirst()
                .orElseThrow(() -> missing("Tienda no encontrada"));
    }
    private void lockCompanyForStore(UUID id) {
        var companyId = jdbc.query("select company_id from saas_store where id=?", (rs, n) -> rs.getObject(1, UUID.class), id)
                .stream().findFirst().orElseThrow(() -> missing("Tienda no encontrada"));
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", rs -> { }, companyId);
    }
    private StoreRow row(ResultSet rs) throws SQLException {
        Map<String, String> address = Map.of();
        String raw = rs.getString("store_address");
        if (raw != null) {
            try { address = mapper.readValue(raw, new TypeReference<Map<String, String>>() { }); }
            catch (Exception exception) { throw new SQLException("Domicilio de tienda no valido", exception); }
        }
        return new StoreRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class), rs.getString("company_name"),
                rs.getString("code"), rs.getString("name"), rs.getString("internal_code"), rs.getBoolean("active"), address,
                rs.getString("time_zone_id"), rs.getLong("installations"), rs.getLong("active_installations"), instant(rs, "last_sync_at"), instant(rs, "created_at"),
                TaxRegime.valueOf(rs.getString("tax_regime")), rs.getBoolean("tax_regime_locked"),
                rs.getBigDecimal("service_price"), rs.getString("billing_period") == null ? null : StoreBillingPeriod.valueOf(rs.getString("billing_period")),
                rs.getInt("max_windows"), rs.getInt("max_pda"), instant(rs, "valid_until"),
                CommercialProfile.valueOf(rs.getString("commercial_profile")));
    }
    static void validatePage(int page, int size) { if (page < 0 || size < 1 || size > 100) throw badRequest("Paginacion no valida"); }
    static String searchPattern(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 200) throw badRequest("Busqueda demasiado larga");
        return "%" + value.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
    static Instant instant(ResultSet rs, String column) throws SQLException { var value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }
    static <T> T validate(Supplier<T> action) { try { return action.get(); } catch (IllegalArgumentException exception) { throw badRequest(exception.getMessage()); } }
    static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    static ResponseStatusException forbidden(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN, message); }
    static ResponseStatusException missing(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
}
