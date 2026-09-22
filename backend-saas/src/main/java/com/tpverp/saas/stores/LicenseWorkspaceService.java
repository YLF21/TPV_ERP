package com.tpverp.saas.stores;

import static com.tpverp.saas.stores.StoreWorkspaceApi.*;
import static com.tpverp.saas.stores.StoreAdministrationService.*;

import com.tpverp.saas.admin.AdminAuditService;
import com.tpverp.saas.license.*;
import com.tpverp.saas.plan.*;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LicenseWorkspaceService {
    private static final String LINKS = """
            with linked_stores as (
                select id license_id, store_id from saas_license where store_id is not null
                union select license_id, store_id from saas_pairing_code
                union select license_id, store_id from saas_installation
            )
            """;
    private static final String BASE = """
            from saas_license l join saas_company c on c.id=l.company_id
            left join saas_company_operations o on o.company_id=c.id
            """;
    private static final String EFFECTIVE_STATUS = "case when l.status='VALIDA' and l.valid_until<=:now then 'CADUCADA' else l.status end";
    private static final String SELECT = LINKS + "select l.*,c.name company_name,c.tax_id,o.billing_status,"
            + EFFECTIVE_STATUS + " effective_status," + """
            (select count(*) from saas_installation i where i.license_id=l.id and i.active) active_installations,
            (select max(i.last_validated_at) from saas_installation i where i.license_id=l.id and i.active) last_validated_at,
            (select max(e.received_at) from saas_sync_event e join saas_installation i on i.id=e.installation_id where i.license_id=l.id) last_sync_at,
            (select min(coalesce(s.internal_code,s.code)) from linked_stores ls join saas_store s on s.id=ls.store_id
             where ls.license_id=l.id and s.company_id=l.company_id) store_sort_code
            """ + BASE;
    private final SaasStoreRepository stores;
    private final SaasLicenseRepository licenses;
    private final SaasPairingCodeRepository pairing;
    private final PlanLimitService limits;
    private final AdminAuditService audit;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LicenseWorkspaceService(SaasStoreRepository stores,
            SaasLicenseRepository licenses, SaasPairingCodeRepository pairing, PlanLimitService limits,
            AdminAuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.stores = stores; this.licenses = licenses; this.pairing = pairing;
        this.limits = limits; this.audit = audit; this.jdbc = new NamedParameterJdbcTemplate(jdbc); this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<LicenseRow> list(UUID companyId, String query, String status, Instant expiresBefore,
            Boolean hasConnections, String billingStatus, int page, int size) {
        return list(companyId, query, status, expiresBefore, hasConnections, billingStatus, page, size, "validUntil", "ASC");
    }

    @Transactional(readOnly = true)
    public Page<LicenseRow> list(UUID companyId, String query, String status, Instant expiresBefore,
            Boolean hasConnections, String billingStatus, int page, int size, String sortBy, String sortDirection) {
        validatePage(page, size);
        String order = sortOrder(sortBy, sortDirection);
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("now", Timestamp.from(clock.instant()));
        var where = new StringBuilder(" where 1=1");
        if (companyId != null) { where.append(" and l.company_id=:companyId"); parameters.put("companyId", companyId); }
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            validate(() -> LicenseSaasStatus.valueOf(normalized));
            where.append(" and ").append(EFFECTIVE_STATUS).append("=:status"); parameters.put("status", normalized);
        }
        if (expiresBefore != null) { where.append(" and l.valid_until<=:expiresBefore"); parameters.put("expiresBefore", Timestamp.from(expiresBefore)); }
        if (hasConnections != null) {
            where.append(hasConnections ? " and exists" : " and not exists")
                    .append(" (select 1 from saas_installation i where i.license_id=l.id and i.active)");
        }
        if (billingStatus != null && !billingStatus.isBlank()) {
            if (billingStatus.length() > 40) throw badRequest("Estado de facturacion no valido");
            where.append(" and upper(o.billing_status)=:billingStatus");
            parameters.put("billingStatus", billingStatus.trim().toUpperCase(Locale.ROOT));
        }
        String q = searchPattern(query);
        if (q != null) {
            where.append("""
                     and (l.reference ilike :q or c.name ilike :q or c.tax_id ilike :q
                          or exists(select 1 from linked_stores ls join saas_store s on s.id=ls.store_id
                                    where ls.license_id=l.id and s.company_id=l.company_id
                                      and (s.internal_code ilike :q or s.name ilike :q or s.code ilike :q)))
                    """);
            parameters.put("q", q);
        }
        long total = jdbc.queryForObject(LINKS + "select count(*) " + BASE + where, parameters, Long.class);
        parameters.put("limit", size); parameters.put("offset", (long) page * size);
        var rows = readRows(where + " order by " + order + " nulls last,l.reference,l.id limit :limit offset :offset", parameters);
        return new Page<>(enrich(rows), page, size, total, (int) Math.ceil((double) total / size));
    }

    @Transactional(readOnly = true)
    public LicenseRow get(UUID licenseId) {
        var rows = readRows(" where l.id=:licenseId",
                Map.of("licenseId", licenseId, "now", Timestamp.from(clock.instant())));
        return enrich(rows).stream().findFirst().orElseThrow(() -> missing("Licencia no encontrada"));
    }

    @Transactional(readOnly = true)
    public ActivationCodePage activationCodes(UUID companyId, int page, int size) {
        validatePage(page, size);
        Instant now = clock.instant();
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("now", Timestamp.from(now));
        String from = """
                from saas_pairing_code p
                join saas_company c on c.id=p.company_id
                join saas_store s on s.id=p.store_id and s.company_id=p.company_id
                join saas_license l on l.id=p.license_id and l.company_id=p.company_id
                where p.consumed_at is null and p.revoked_at is null and p.expires_at>:now
                  and s.active and l.status='VALIDA' and l.valid_until>:now
                """;
        if (companyId != null) {
            from += " and p.company_id=:companyId";
            parameters.put("companyId", companyId);
        }
        long total = jdbc.queryForObject("select count(*) " + from, parameters, Long.class);
        parameters.put("limit", size);
        parameters.put("offset", (long) page * size);
        var rows = jdbc.query("""
                select p.id,p.company_id,c.name company_name,p.store_id,s.name store_name,
                       s.internal_code,s.code store_code,p.license_id,l.reference,p.code,p.expires_at
                """ + from + " order by p.created_at desc,p.id limit :limit offset :offset", parameters,
                (rs, n) -> new ActivationCodeRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                        rs.getString("company_name"), rs.getObject("store_id", UUID.class), rs.getString("store_name"),
                        rs.getString("internal_code"), rs.getString("store_code"), rs.getObject("license_id", UUID.class),
                        rs.getString("reference"), rs.getString("code"), instant(rs, "expires_at")));
        return new ActivationCodePage(rows, page, size, total, (int) Math.ceil((double) total / size), now);
    }

    private List<LicenseRow> readRows(String suffix, Map<String, ?> parameters) {
        return jdbc.query(SELECT + suffix, parameters,
                (rs, n) -> new LicenseRow(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getObject("company_id", UUID.class),
                        rs.getString("company_name"), rs.getString("tax_id"), rs.getString("effective_status"), instant(rs, "valid_until"),
                        rs.getInt("max_windows"), rs.getInt("max_pda"), rs.getLong("active_installations"), instant(rs, "last_validated_at"),
                        instant(rs, "last_sync_at"), List.of(), "COMPANY", rs.getString("billing_status"), List.of()));
    }

    private List<LicenseRow> enrich(List<LicenseRow> rows) {
        if (rows.isEmpty()) return rows;
        var storeLinks = links(rows.stream().map(LicenseRow::id).toList());
        var debts = debts(rows.stream().map(LicenseRow::companyId).distinct().toList());
        return rows.stream().map(row -> new LicenseRow(row.id(), row.reference(), row.companyId(), row.companyName(), row.taxId(),
                row.status(), row.validUntil(), row.maxWindows(), row.maxPda(), row.activeInstallations(), row.lastValidatedAt(), row.lastSyncAt(),
                storeLinks.getOrDefault(row.id(), List.of()), row.billingScope(), row.companyBillingStatus(), debts.getOrDefault(row.companyId(), List.of()))).toList();
    }

    private static String sortOrder(String sortBy, String sortDirection) {
        String column = switch (sortBy == null ? "validUntil" : sortBy) {
            case "reference" -> "lower(l.reference)";
            case "companyName" -> "lower(c.name)";
            case "storeCode" -> "store_sort_code";
            case "status" -> "effective_status";
            case "validUntil" -> "l.valid_until";
            case "lastValidatedAt" -> "last_validated_at";
            case "lastSyncAt" -> "last_sync_at";
            case "maxWindows" -> "l.max_windows";
            case "maxPda" -> "l.max_pda";
            case "activeInstallations" -> "active_installations";
            case "billingStatus" -> "o.billing_status";
            default -> throw badRequest("Columna de ordenacion no valida");
        };
        String direction = sortDirection == null ? "ASC" : sortDirection.toUpperCase(Locale.ROOT);
        if (!Set.of("ASC", "DESC").contains(direction)) throw badRequest("Direccion de ordenacion no valida");
        return column + " " + direction;
    }

    private Map<UUID, List<StoreLink>> links(List<UUID> ids) {
        var result = new HashMap<UUID, List<StoreLink>>();
        jdbc.query(LINKS + """
                select ls.license_id,s.id,s.code,s.name,s.internal_code,s.active
                from linked_stores ls join saas_license l on l.id=ls.license_id
                join saas_store s on s.id=ls.store_id and s.company_id=l.company_id
                where ls.license_id in (:ids) order by s.code,s.id
                """, Map.of("ids", ids), rs -> {
            result.computeIfAbsent(rs.getObject("license_id", UUID.class), ignored -> new ArrayList<>())
                    .add(new StoreLink(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("internal_code"), rs.getBoolean("active")));
        });
        return result;
    }

    private Map<UUID, List<CompanyDebt>> debts(List<UUID> companies) {
        var result = new HashMap<UUID, List<CompanyDebt>>();
        jdbc.query("""
                with balances as (
                    select i.id,i.company_id,upper(i.currency) currency,i.due_at,
                           greatest(i.amount::numeric-coalesce(sum(p.amount::numeric),0),0) outstanding
                    from saas_billing_invoice i left join saas_billing_payment p on p.invoice_id=i.id
                    where i.company_id in (:companies)
                    group by i.id,i.company_id,i.currency,i.due_at,i.amount
                )
                select company_id,currency,sum(outstanding) outstanding,
                       sum(case when due_at<:now then outstanding else 0 end) overdue
                from balances group by company_id,currency order by currency
                """, Map.of("companies", companies, "now", Timestamp.from(clock.instant())), rs -> {
            result.computeIfAbsent(rs.getObject("company_id", UUID.class), ignored -> new ArrayList<>())
                    .add(new CompanyDebt(rs.getString("currency"), rs.getBigDecimal("outstanding"), rs.getBigDecimal("overdue")));
        });
        return result;
    }

    @Transactional
    public CreatedLicense create(CreateLicense request) {
        return create(request, false);
    }

    @Transactional
    public CreatedLicense create(CreateLicense request, boolean mayRegeneratePairing) {
        if (request.storeId() == null) throw badRequest("Selecciona una tienda");
        var companyId = jdbc.query("select company_id from saas_store where id=:id", Map.of("id", request.storeId()),
                (rs, row) -> rs.getObject(1, UUID.class)).stream().findFirst().orElseThrow(() -> missing("Tienda no encontrada"));
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:companyId::text, 0))", Map.of("companyId", companyId), rs -> { });
        var store = stores.findByIdForUpdate(request.storeId()).orElseThrow(() -> missing("Tienda no encontrada"));
        Instant now = clock.instant();
        var company = store.getCompany();
        if (!store.isActive()) throw conflict("No se puede crear una licencia para una tienda inactiva");
        requireSpanishStoreAddress(store.getStoreAddress());
        var associated = associatedLicenseReferences(jdbc.getJdbcTemplate(), store.getId());
        SaasLicense license;
        boolean reused = !associated.isEmpty();
        if (reused) {
            if (!mayRegeneratePairing) throw forbidden("Se requiere permiso para regenerar el codigo de enlace de esta licencia");
            if (associated.size() != 1) throw conflict("Gestiona las licencias historicas desde Licencias; la tienda tiene varias licencias");
            license = licenses.findByReferenceForUpdate(associated.getFirst()).orElseThrow(() -> missing("Licencia no encontrada"));
            if (!isExclusiveStoreLicense(jdbc.getJdbcTemplate(), license.getId(), store.getId())) {
                throw conflict("Gestiona la licencia historica desde Licencias; no pertenece exclusivamente a esta tienda");
            }
            if (license.getStatus() != LicenseSaasStatus.VALIDA || license.getValidUntil() == null || !license.getValidUntil().isAfter(now)) {
                throw conflict("La licencia debe estar vigente y desbloqueada para generar un codigo");
            }
        } else {
            if (store.getValidUntil() == null || !store.getValidUntil().isAfter(now)
                    || store.getServicePrice() == null || store.getBillingPeriod() == null) {
                throw conflict("Configura el precio, periodicidad y caducidad de la tienda antes de crear la licencia");
            }
            limits.requireCapacity(companyId, PlanResource.LICENSES);
            String reference = "LIC-" + company.getTaxId() + "-" + store.getCode();
            if (licenses.findByReference(reference).isPresent()) reference += "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
            license = new SaasLicense(UUID.randomUUID(), company, reference, store.getValidUntil(), store.getMaxWindows(), store.getMaxPda(), now);
            license.assignStore(store);
            try { licenses.saveAndFlush(license); }
            catch (DataIntegrityViolationException exception) { throw conflict("La referencia de licencia ya existe o el plan no admite otra licencia"); }
        }
        String code = pairingCode();
        Instant expiresAt = now.plus(PairingCodePolicy.VALIDITY);
        pairing.findByStore_IdAndConsumedAtIsNullAndRevokedAtIsNull(store.getId())
                .forEach(previous -> previous.revoke(now, "REPLACED"));
        // Hibernate inserts precede dirty updates unless the revocations are flushed first.
        pairing.flush();
        UUID pairingCodeId = UUID.randomUUID();
        try { pairing.saveAndFlush(new SaasPairingCode(pairingCodeId, company, store, license, code, expiresAt, now)); }
        catch (DataIntegrityViolationException exception) { throw conflict("No se pudo generar un codigo de enlace unico; vuelve a intentarlo"); }
        audit.log(reused ? "REGENERATE_PAIRING_CODE" : "CREATE_LICENSE", "LICENSE", license.getReference(), "companyId=" + company.getId() + ";storeId=" + store.getId());
        return new CreatedLicense(license.getId(), license.getReference(), company.getId(), store.getId(), code, expiresAt, now, pairingCodeId);
    }

    @Transactional
    public void revokeActivationCode(UUID codeId) {
        UUID companyId = jdbc.query("select company_id from saas_pairing_code where id=:id", Map.of("id", codeId),
                (rs, row) -> rs.getObject(1, UUID.class)).stream().findFirst()
                .orElseThrow(() -> missing("Codigo de enlace no encontrado"));
        // Same first lock as emission and consumption; never revoke by store or license here.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:companyId::text, 0))", Map.of("companyId", companyId), rs -> { });
        var code = pairing.findByIdForUpdate(codeId).orElseThrow(() -> missing("Codigo de enlace no encontrado"));
        Instant now = clock.instant();
        if (code.usableAt(now)) {
            code.revoke(now, "ADMIN_REVOKED");
            audit.log("REVOKE_PAIRING_CODE", "PAIRING_CODE", codeId.toString(),
                    "companyId=" + companyId + ";storeId=" + code.getStore().getId());
        }
    }

    private String pairingCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var code = new StringBuilder("TPV-");
        for (int index = 0; index < 12; index++) code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return code.toString();
    }
}
