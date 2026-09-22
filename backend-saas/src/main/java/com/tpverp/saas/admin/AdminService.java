package com.tpverp.saas.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.CommercialProfile;
import com.tpverp.saas.license.TaxpayerType;
import jakarta.validation.Validator;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasCompanyRepository;
import com.tpverp.saas.license.SaasLicense;
import com.tpverp.saas.license.SaasLicenseRepository;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.SaasPairingCode;
import com.tpverp.saas.license.SaasPairingCodeRepository;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.license.SaasStoreRepository;
import com.tpverp.saas.license.LicenseProvisioningData;
import com.tpverp.saas.license.LicenseSaasStatus;
import com.tpverp.saas.license.SpanishTaxId;
import com.tpverp.saas.access.TenantAccessService;
import com.tpverp.saas.stores.StoreAdministrationService;
import com.tpverp.saas.tenant.SaasTenantUser;
import com.tpverp.saas.tenant.SaasTenantUserRepository;
import com.tpverp.saas.tenant.TenantRole;
import com.tpverp.saas.plan.PlanLimitService;
import com.tpverp.saas.plan.PlanResource;
import com.tpverp.saas.plan.PlanUsageResponse;
import com.tpverp.saas.customer.CustomerDocumentIdentity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminService {

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Set<String> PAYMENT_METHODS = Set.of(
            "TRANSFERENCIA", "TARJETA", "DOMICILIACION", "EFECTIVO", "AJUSTE");
    private static final Set<String> INTEGRATION_TYPES = Set.of("WEBHOOK", "ACCOUNTING_EXPORT");

    private final SaasCompanyRepository companies;
    private final SaasStoreRepository stores;
    private final SaasLicenseRepository licenses;
    private final SaasInstallationRepository installations;
    private final SaasPairingCodeRepository pairingCodes;
    private final SaasAdminUserRepository adminUsers;
    private final SaasTenantUserRepository tenantUsers;
    private final AdminPasswordHasher passwordHasher;
    private final IntegrationSecretCipher integrationSecrets;
    private final AdminAuditService audit;
    private final SaasSessionTokenStore sessions;
    private final PlanLimitService planLimits;
    private final TenantAccessService tenantAccess;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AdminService(
            SaasCompanyRepository companies,
            SaasStoreRepository stores,
            SaasLicenseRepository licenses,
            SaasInstallationRepository installations,
            SaasPairingCodeRepository pairingCodes,
            SaasAdminUserRepository adminUsers,
            SaasTenantUserRepository tenantUsers,
            AdminPasswordHasher passwordHasher,
            IntegrationSecretCipher integrationSecrets,
            AdminAuditService audit,
            SaasSessionTokenStore sessions,
            PlanLimitService planLimits,
            TenantAccessService tenantAccess,
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            Validator validator,
            Clock clock) {
        this.companies = companies;
        this.stores = stores;
        this.licenses = licenses;
        this.installations = installations;
        this.pairingCodes = pairingCodes;
        this.adminUsers = adminUsers;
        this.tenantUsers = tenantUsers;
        this.passwordHasher = passwordHasher;
        this.integrationSecrets = integrationSecrets;
        this.audit = audit;
        this.sessions = sessions;
        this.planLimits = planLimits;
        this.tenantAccess = tenantAccess;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional
    public CompanySummaryResponse createCompany(CreateCompanyRequest request) {
        validateCompanyRequest(request);
        String companyName = provisioningValue(() -> LicenseProvisioningData.requiredName(
                request.name(), "name", 200));
        String taxId = provisioningValue(() -> SpanishTaxId.validate(request.taxId()));
        var companyAddress = provisioningValue(() -> LicenseProvisioningData.fiscalAddress(
                request.companyAddress(), "companyAddress"));
        if (companies.existsByTaxId(taxId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una empresa con el mismo NIF");
        }
        SaasCompany company;
        try {
            company = companies.saveAndFlush(new SaasCompany(UUID.randomUUID(), companyName,
                    taxId, request.taxpayerType(), null, request.commercialProfile(), companyAddress,
                    clock.instant()));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe una empresa con el mismo NIF", exception);
        }
        writeCompanyProfileFields(company.getId(), request.contactName(), request.contactEmail(),
                request.contactPhone(), request.supportStatus() == null ? "NORMAL" : request.supportStatus(),
                request.notes(), request.owners());
        audit.log("ADD_COMPANY", "COMPANY", company.getId().toString());
        return companyProfile(company.getId());
    }

    @Transactional(readOnly = true)
    public List<CompanySummaryResponse> companies() {
        return jdbc.query(companyProfileSql() + " order by c.name, c.id", (rs, row) -> companySummary(rs));
    }

    @Transactional(readOnly = true)
    public CompanySummaryResponse companyProfile(UUID companyId) {
        return jdbc.query(companyProfileSql() + " where c.id = ?", (rs, row) -> companySummary(rs), companyId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no existe"));
    }

    @Transactional
    public CompanySummaryResponse updateCompanyProfile(UUID companyId, UpdateCompanyProfileRequest request) {
        validateCompanyRequest(request);
        lockCompanyProfile(companyId);
        SaasCompany company = companies.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no existe"));
        String name = provisioningValue(() -> LicenseProvisioningData.requiredName(request.name(), "name", 200));
        var address = provisioningValue(() -> LicenseProvisioningData.fiscalAddress(request.companyAddress(), "companyAddress"));
        company.updateData(name, company.getTaxpayerType(), company.getTaxRegime(), company.getCommercialProfile());
        company.updateFiscalAddress(address);
        companies.flush();
        writeCompanyProfileFields(companyId, request.contactName(), request.contactEmail(), request.contactPhone(),
                request.supportStatus(), request.notes(), request.owners());
        audit.log("UPDATE_COMPANY_PROFILE", "COMPANY", companyId.toString());
        return companyProfile(companyId);
    }

    private void lockCompanyProfile(UUID companyId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", rs -> { }, companyId);
        if (jdbc.query("select id from saas_company where id = ? for update", (rs, n) -> rs.getObject(1), companyId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no existe");
        }
    }

    private void validateCompanyRequest(Object request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos de empresa obligatorios");
        var violation = validator.validate(request).stream().findFirst();
        if (violation.isPresent()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                violation.get().getPropertyPath() + ": " + violation.get().getMessage());
    }

    private void writeCompanyProfileFields(UUID companyId, String contactName, String contactEmail,
            String contactPhone, String supportStatus, String notes, List<CompanyOwner> owners) {
        List<CompanyOwner> normalizedOwners = owners.stream().map(owner -> new CompanyOwner(
                provisioningValue(() -> LicenseProvisioningData.requiredName(owner.name(), "owners.name", 160)),
                ownerTaxId(owner.taxId()),
                blankToNull(owner.phone()), blankToNull(owner.email()))).toList();
        String ownersJson;
        try { ownersJson = mapper.writeValueAsString(normalizedOwners); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("No se pudieron preparar los propietarios"); }
        jdbc.update("update saas_company set owners = cast(? as jsonb) where id = ?", ownersJson, companyId);
        jdbc.update("""
                insert into saas_company_operations(company_id,plan_name,billing_status,support_status,
                    contact_name,contact_email,contact_phone,notes,updated_at)
                values (?,'STANDARD','PENDIENTE',?,?,?,?,?,?)
                on conflict (company_id) do update set support_status=excluded.support_status,
                    contact_name=excluded.contact_name,contact_email=excluded.contact_email,
                    contact_phone=excluded.contact_phone,notes=excluded.notes,updated_at=excluded.updated_at
                """, companyId, supportStatus, blankToNull(contactName), blankToNull(contactEmail),
                blankToNull(contactPhone), blankToNull(notes), sqlTimestamp(clock.instant()));
    }

    private static String companyProfileSql() {
        return """
                select c.id,c.name,c.tax_id,c.taxpayer_type,c.commercial_profile,c.company_address,c.created_at,c.owners,
                       o.contact_name,o.contact_email,o.contact_phone,coalesce(o.support_status,'NORMAL') support_status,o.notes
                from saas_company c left join saas_company_operations o on o.company_id=c.id
                """;
    }

    private String ownerTaxId(String value) {
        String normalized = provisioningValue(() -> SpanishTaxId.validate(value));
        if (!normalized.matches("(?:[0-9]{8}|[XYZ][0-9]{7})[A-Z]")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El propietario requiere un DNI o NIE personal");
        }
        return normalized;
    }

    private CompanySummaryResponse companySummary(ResultSet rs) throws SQLException {
        try {
            String address = rs.getString("company_address");
            String profile = rs.getString("commercial_profile");
            return new CompanySummaryResponse(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("tax_id"),
                    TaxpayerType.valueOf(rs.getString("taxpayer_type")), profile == null ? null : CommercialProfile.valueOf(profile),
                    address == null ? null : mapper.readValue(address, new TypeReference<Map<String, String>>() { }),
                    rs.getTimestamp("created_at").toInstant(), rs.getString("contact_name"), rs.getString("contact_email"),
                    rs.getString("contact_phone"), rs.getString("support_status"), rs.getString("notes"),
                    mapper.readValue(rs.getString("owners"), new TypeReference<List<CompanyOwner>>() { }));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Datos de ficha de empresa no validos");
        }
    }

    @Transactional
    public AdminLicenseResponse block(String reference) {
        SaasLicense license = licenseForUpdate(reference);
        license.block();
        audit.log("BLOCK_LICENSE", "LICENSE", reference);
        return response(license);
    }

    @Transactional
    public AdminLicenseResponse unblock(String reference) {
        SaasLicense license = licenseForUpdate(reference);
        license.unblock();
        audit.log("UNBLOCK_LICENSE", "LICENSE", reference);
        return response(license);
    }

    @Transactional(readOnly = true)
    public List<LicenseSummaryResponse> licenses() {
        Instant now = clock.instant();
        return licenses.findAll().stream()
                .sorted(Comparator.comparing(SaasLicense::getReference))
                .map(license -> licenseSummary(license, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstallationSummaryResponse> installations() {
        Map<UUID, Instant> syncTimes = new java.util.HashMap<>();
        jdbc.query("select i.installation_id, max(e.received_at) as last_sync from saas_sync_event e join saas_installation i on i.id=e.installation_id group by i.installation_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> syncTimes.put(rs.getObject("installation_id", UUID.class), rs.getTimestamp("last_sync").toInstant()));
        return installations.findAllByOrderByLinkedAtDesc().stream()
                .map(installation -> installationResponse(installation, syncTimes.get(installation.getInstallationId())))
                .toList();
    }

    @Transactional
    public InstallationSummaryResponse revokeInstallation(
            UUID installationId, RevokeInstallationRequest request) {
        SaasInstallation installation = installations.findByInstallationIdForUpdate(installationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Instalacion no existe"));
        if (!installation.isActive()) {
            return installationResponse(installation);
        }
        installation.revoke(clock.instant(), currentAdminUsername(), request.reason());
        installations.save(installation);
        audit.log(
                "REVOKE_INSTALLATION",
                "INSTALLATION",
                installationId.toString(),
                "storeId=" + installation.getStore().getId()
                        + "; reason=" + request.reason().trim());
        return installationResponse(installation);
    }

    @Transactional(readOnly = true)
    public AdminSessionResponse session() {
        String username = currentAdminUsername();
        return new AdminSessionResponse(username, adminUsers.permissionCodes(username));
    }

    @Transactional(readOnly = true)
    public PlanUsageResponse planUsage(UUID companyId) {
        ensureCompanyExists(companyId);
        return planLimits.usage(companyId);
    }

    @Transactional(readOnly = true)
    public SaasStatusResponse status() {
        return new SaasStatusResponse(
                clock.instant(),
                "saas-api-v1",
                currentMigration(),
                List.of(
                        "licenses",
                        "stores",
                        "tenant-access",
                        "supervision",
                        "installations",
                        "license-policies",
                        "fiscal-provisioning",
                        "fiscal-status",
                        "operational-incidents",
                        "sync",
                        "support",
                        "health",
                        "billing",
                        "tenant",
                        "erp-masters",
                        "erp-operations",
                        "reports",
                        "integrations"));
    }

    @Transactional(readOnly = true)
    public List<AdminNotificationResponse> notifications() {
        Instant now = clock.instant();
        List<AdminNotificationResponse> licenseNotifications = licenses.findAll().stream()
                .flatMap(license -> {
                    java.util.stream.Stream<AdminNotificationResponse> stream = java.util.stream.Stream.empty();
                    if (license.getStatus() == LicenseSaasStatus.BLOQUEADA_MANUAL) {
                        stream = java.util.stream.Stream.concat(stream, java.util.stream.Stream.of(new AdminNotificationResponse(
                                "license-blocked-" + license.getReference(),
                                license.getCompany().getId(),
                                license.getCompany().getName(),
                                "DANGER",
                                "Licencia bloqueada",
                                license.getReference(),
                                now)));
                    }
                    if (license.getValidUntil().isBefore(now.plus(Duration.ofDays(30)))) {
                        stream = java.util.stream.Stream.concat(stream, java.util.stream.Stream.of(new AdminNotificationResponse(
                                "license-expiring-" + license.getReference(),
                                license.getCompany().getId(),
                                license.getCompany().getName(),
                                "WARNING",
                                "Licencia próxima a caducar",
                                license.getReference() + " caduca el " + license.getValidUntil(),
                                now)));
                    }
                    return stream;
                })
                .toList();
        List<AdminNotificationResponse> installationNotifications = installations.findAll().stream()
                .filter(SaasInstallation::isActive)
                .filter(installation -> installation.getLastValidatedAt() == null
                        || installation.getLastValidatedAt().isBefore(now.minus(Duration.ofHours(48))))
                .map(installation -> new AdminNotificationResponse(
                        "installation-stale-" + installation.getInstallationId(),
                        installation.getCompany().getId(),
                        installation.getCompany().getName(),
                        "WARNING",
                        "Instalación sin validación reciente",
                        installation.getInstallationReference(),
                        now))
                .toList();
        List<AdminNotificationResponse> billingNotifications = jdbc.query("""
                select o.company_id, c.name, o.billing_status, o.renewal_date
                from saas_company_operations o
                join saas_company c on c.id = o.company_id
                where upper(o.billing_status) in ('PENDIENTE', 'VENCIDO', 'IMPAGADO')
                   or (o.renewal_date is not null and o.renewal_date < ?)
                """, (rs, rowNum) -> new AdminNotificationResponse(
                "billing-" + rs.getObject("company_id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("name"),
                "WARNING",
                "Revisar facturación",
                rs.getString("billing_status"),
                now), sqlTimestamp(now.plus(Duration.ofDays(15))));
        List<AdminNotificationResponse> overdueInvoiceNotifications = jdbc.query("""
                select i.id, i.company_id, c.name, i.number
                from saas_billing_invoice i
                join saas_company c on c.id = i.company_id
                left join saas_billing_payment p on p.invoice_id = i.id
                where i.due_at < ?
                group by i.id, i.company_id, c.name, i.number, i.amount
                having coalesce(sum(cast(p.amount as decimal(19,2))), 0) < cast(i.amount as decimal(19,2))
                """, (rs, rowNum) -> new AdminNotificationResponse(
                "invoice-overdue-" + rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getString("name"), "DANGER",
                "Factura vencida", rs.getString("number"), now), sqlTimestamp(now));
        List<AdminNotificationResponse> urgentTicketNotifications = jdbc.query("""
                select t.id, t.company_id, c.name, t.title
                from saas_support_ticket t
                join saas_company c on c.id = t.company_id
                where t.priority = 'URGENTE' and t.status <> 'CERRADO'
                """, (rs, rowNum) -> new AdminNotificationResponse(
                "ticket-urgent-" + rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getString("name"), "DANGER",
                "Ticket urgente abierto", rs.getString("title"), now));
        List<AdminNotificationResponse> staleStoreNotifications = jdbc.query("""
                select s.id, s.company_id, c.name, s.code, max(i.last_validated_at) as last_validated_at
                from saas_store s
                join saas_company c on c.id = s.company_id
                left join saas_installation i on i.store_id = s.id
                group by s.id, s.company_id, c.name, s.code
                having max(i.last_validated_at) is null or max(i.last_validated_at) < ?
                """, (rs, rowNum) -> new AdminNotificationResponse(
                "store-stale-" + rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getString("name"), "WARNING",
                "Tienda sin validacion reciente", rs.getString("code"), now),
                sqlTimestamp(now.minus(Duration.ofHours(48))));        Set<String> readIds = Set.copyOf(jdbc.queryForList(
                "select notification_id from saas_admin_notification_read where username = ?",
                String.class,
                currentAdminUsername()));
        return java.util.stream.Stream.of(licenseNotifications, installationNotifications, billingNotifications,
                        overdueInvoiceNotifications, urgentTicketNotifications, staleStoreNotifications)
                .flatMap(List::stream)
                .map(notification -> new AdminNotificationResponse(
                        notification.id(), notification.companyId(), notification.companyName(),
                        notification.severity(), notification.title(), notification.detail(),
                        notification.createdAt(), readIds.contains(notification.id())))
                .sorted(Comparator.comparing(AdminNotificationResponse::severity).thenComparing(AdminNotificationResponse::companyName))
                .toList();
    }

    @Transactional
    public void markNotificationRead(String notificationId) {
        String normalizedId = notificationId == null ? "" : notificationId.trim();
        boolean exists = notifications().stream().anyMatch(notification -> notification.id().equals(normalizedId));
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificacion no existe o ya no esta activa");
        }
        jdbc.update("""
                insert into saas_admin_notification_read(username, notification_id, read_at)
                values (?, ?, ?)
                on conflict (username, notification_id) do update set read_at = excluded.read_at
                """, currentAdminUsername(), normalizedId, sqlTimestamp(clock.instant()));
        audit.log("READ_NOTIFICATION", "ADMIN_NOTIFICATION", normalizedId);
    }

    @Transactional(readOnly = true)
    public TechnicalStatusResponse technicalStatus() {
        Instant now = clock.instant();
        Long eventsToday = jdbc.queryForObject("""
                select count(*) from saas_sync_event
                where received_at >= ?
                """, Long.class, sqlTimestamp(now.minus(Duration.ofDays(1))));
        Long openTickets = jdbc.queryForObject("""
                select count(*) from saas_support_ticket
                where status <> 'RESUELTO'
                """, Long.class);
        Long staleInstallations = jdbc.queryForObject("""
                 select count(*) from saas_installation
                 where active = true
                   and (last_validated_at is null or last_validated_at < ?)
                """, Long.class, sqlTimestamp(now.minus(Duration.ofHours(48))));
        Instant lastSyncAt = jdbc.query("""
                select max(received_at) as last_sync_at from saas_sync_event
                """, rs -> rs.next() && rs.getTimestamp("last_sync_at") != null
                        ? rs.getTimestamp("last_sync_at").toInstant()
                        : null);
        return new TechnicalStatusResponse(
                now,
                companies.count(),
                licenses.count(),
                installations.countByActiveTrue(),
                eventsToday == null ? 0 : eventsToday,
                openTickets == null ? 0 : openTickets,
                staleInstallations == null ? 0 : staleInstallations,
                lastSyncAt);
    }

    @Transactional(readOnly = true)
    public BillingSummaryResponse billingSummary() {
        Instant now = clock.instant();
        List<BillingCompanyResponse> rows = jdbc.query("""
                select c.id as company_id, c.name as company_name, c.tax_id,
                       coalesce(o.plan_name, 'STANDARD') as plan_name,
                       coalesce(o.billing_status, 'PENDIENTE') as billing_status,
                       o.renewal_date,
                       prices.monthly_equivalent as monthly_price,
                       l.reference as license_reference,
                       l.valid_until
                from saas_company c
                left join saas_company_operations o on o.company_id = c.id
                left join saas_license l on l.company_id = c.id
                left join (%s) prices on prices.company_id = c.id
                order by c.name asc, l.valid_until desc
                """.formatted(StorePricingSummary.SQL), (rs, rowNum) -> billingCompany(rs, now));
        List<BillingCompanyResponse> uniqueRows = rows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        BillingCompanyResponse::companyId,
                        row -> row,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new))
                .values()
                .stream()
                .toList();
        long paidCompanies = uniqueRows.stream().filter(row -> "PAGADO".equals(row.billingStatus())).count();
        long pendingCompanies = uniqueRows.stream()
                .filter(row -> List.of("PENDIENTE", "VENCIDO", "IMPAGADO").contains(row.billingStatus()))
                .count();
        long overdueCompanies = uniqueRows.stream().filter(BillingCompanyResponse::overdue).count();
        long renewalsNext30Days = uniqueRows.stream().filter(BillingCompanyResponse::renewalDueSoon).count();
        BigDecimal monthlyRecurringRevenue = uniqueRows.stream()
                .map(row -> amount(row.monthlyPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BillingSummaryResponse(
                uniqueRows.size(),
                paidCompanies,
                pendingCompanies,
                overdueCompanies,
                renewalsNext30Days,
                monthlyRecurringRevenue.toPlainString(),
                uniqueRows);
    }

    @Transactional(readOnly = true)
    public List<CustomerHealthResponse> customerHealth() {
        Instant now = clock.instant();
        try {
            return jdbc.query("""
                    select c.id as company_id, c.name as company_name, c.tax_id,
                           coalesce(o.plan_name, 'STANDARD') as plan_name,
                           coalesce(o.billing_status, 'PENDIENTE') as billing_status,
                           coalesce((select l.status from saas_license l where l.company_id = c.id order by l.valid_until desc limit 1), 'SIN_LICENCIA') as license_status,
                           (select min(l.valid_until) from saas_license l where l.company_id = c.id) as valid_until,
                           (select count(*) from saas_installation i where i.company_id = c.id and i.active = true) as installations,
                           (select count(*) from saas_installation i where i.company_id = c.id and i.active = true and (i.last_validated_at is null or i.last_validated_at < ?)) as stale_installations,
                           (select max(i.last_validated_at) from saas_installation i where i.company_id = c.id and i.active = true) as last_validation_at,
                           (select count(*) from saas_sync_event e where e.company_id = c.id and e.received_at >= ?) as events_last_7_days,
                           (select max(e.received_at) from saas_sync_event e where e.company_id = c.id) as last_event_at,
                           (select count(*) from saas_support_ticket t where t.company_id = c.id and t.status <> 'RESUELTO') as open_tickets,
                           (select count(*) from saas_support_ticket t where t.company_id = c.id and t.status <> 'RESUELTO' and t.priority = 'URGENTE') as urgent_tickets
                    from saas_company c
                    left join saas_company_operations o on o.company_id = c.id
                    order by c.name asc
                    """, (rs, rowNum) -> customerHealth(rs, now),
                    sqlTimestamp(now.minus(Duration.ofHours(48))),
                    sqlTimestamp(now.minus(Duration.ofDays(7))));
        } catch (BadSqlGrammarException exception) {
            if (missingOperationalTables(exception)) {
                return fallbackCustomerHealth(now);
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public CustomerHealthResponse customerHealth(UUID companyId) {
        ensureCompanyExists(companyId);
        return customerHealth().stream()
                .filter(value -> value.companyId().equals(companyId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no existe"));
    }

    @Transactional
    public CompanySummaryResponse editCompany(UUID companyId, EditCompanyDataRequest request) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", rs -> { }, companyId);
        SaasCompany company = companies.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no existe"));
        if (!licenses.findByCompany_Id(companyId).isEmpty()
                && company.getTaxpayerType() != request.taxpayerType()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El tipo de obligado no puede cambiarse despues de emitir una licencia");
        }
        String name = provisioningValue(() -> LicenseProvisioningData.requiredName(request.name(), "name", 200));
        var address = provisioningValue(() -> LicenseProvisioningData.fiscalAddress(request.companyAddress(), "companyAddress"));
        company.updateData(name, request.taxpayerType(), company.getTaxRegime(),
                request.commercialProfile() == null ? company.getCommercialProfile() : request.commercialProfile());
        company.updateFiscalAddress(address);
        companies.flush();
        audit.log("EDIT_COMPANY_DATA", "COMPANY", companyId.toString());
        return companyProfile(companyId);
    }

    @Transactional(readOnly = true)
    public FiscalProvisioningResponse fiscalProvisioning(UUID companyId) {
        SaasCompany company = companies.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no existe"));
        return fiscalProvisioningResponse(company,
                stores.findByCompany_IdOrderByCodeAsc(companyId));
    }

    @Transactional
    public FiscalProvisioningResponse updateFiscalProvisioning(
            UUID companyId, UpdateFiscalProvisioningRequest request) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", rs -> { }, companyId);
        SaasCompany company = companies.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no existe"));
        List<SaasStore> companyStores = stores.findByCompany_IdOrderByCodeAsc(companyId);
        Map<UUID, UpdateFiscalProvisioningRequest.StoreProvisioning> requested = new java.util.HashMap<>();
        for (var value : request.stores()) {
            if (requested.putIfAbsent(value.storeId(), value) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "storeId duplicado en el aprovisionamiento fiscal");
            }
        }
        var expectedIds = companyStores.stream().map(SaasStore::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (!requested.keySet().equals(expectedIds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Deben enviarse exactamente todas las tiendas de la empresa");
        }

        Map<String, String> companyAddress;
        try {
            companyAddress = com.tpverp.saas.license.LicenseProvisioningData.fiscalAddress(
                    request.companyAddress(), "companyAddress");
            for (SaasStore store : companyStores) {
                var value = requested.get(store.getId());
                store.updateFiscalProvisioning(value.storeAddress(), value.timeZoneId());
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
        company.updateFiscalAddress(companyAddress);
        audit.log("UPDATE_FISCAL_PROVISIONING", "COMPANY", companyId.toString());
        return fiscalProvisioningResponse(company, companyStores);
    }

    private static FiscalProvisioningResponse fiscalProvisioningResponse(
            SaasCompany company, List<SaasStore> companyStores) {
        return new FiscalProvisioningResponse(
                company.getId(), company.getCompanyAddress(), companyStores.stream()
                        .map(store -> new FiscalProvisioningResponse.StoreProvisioning(
                                store.getId(), store.getCode(), store.getName(),
                                store.getStoreAddress(), store.getTimeZoneId()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public CompanyOperationsResponse companyOperations(UUID companyId) {
        ensureCompanyExists(companyId);
        return jdbc.query("""
                select company_id, plan_name, billing_status, renewal_date, monthly_price,
                       support_status, contact_name, contact_email, notes, contact_phone
                from saas_company_operations
                where company_id = ?
                """, (rs, rowNum) -> new CompanyOperationsResponse(
                rs.getObject("company_id", UUID.class),
                rs.getString("plan_name"),
                rs.getString("billing_status"),
                rs.getTimestamp("renewal_date") == null ? null : rs.getTimestamp("renewal_date").toInstant(),
                rs.getString("monthly_price"),
                rs.getString("support_status"),
                rs.getString("contact_name"),
                rs.getString("contact_email"),
                rs.getString("notes"), rs.getString("contact_phone")), companyId).stream()
                .findFirst()
                .orElseGet(() -> defaultOperations(companyId));
    }

    @Transactional
    public CompanyOperationsResponse updateCompanyOperations(UUID companyId, UpdateCompanyOperationsRequest request) {
        validateCompanyRequest(request);
        // Serialize the read/modify/write operation even before its settings row exists.
        lockCompanyProfile(companyId);
        CompanyOperationsResponse existing = companyOperations(companyId);
        request = new UpdateCompanyOperationsRequest(
                request.planName() == null ? existing.planName() : request.planName(),
                request.billingStatus() == null ? existing.billingStatus() : request.billingStatus(),
                request.renewalDate() == null ? existing.renewalDate() : request.renewalDate(),
                request.monthlyPrice() == null ? existing.monthlyPrice() : request.monthlyPrice(),
                request.supportStatus() == null ? existing.supportStatus() : request.supportStatus(),
                request.contactName() == null ? existing.contactName() : request.contactName(),
                request.contactEmail() == null ? existing.contactEmail() : request.contactEmail(),
                request.notes() == null ? existing.notes() : request.notes(),
                request.contactPhone() == null ? existing.contactPhone() : request.contactPhone());
        Instant now = clock.instant();
        String planName = planLimits.requireKnownPlan(request.planName());
        String billingStatus = requireOneOf(request.billingStatus(), "PENDIENTE",
                Set.of("PAGADO", "PENDIENTE", "VENCIDO", "IMPAGADO"));
        String supportStatus = requireOneOf(request.supportStatus(), "NORMAL",
                Set.of("NORMAL", "ATENCION", "BLOQUEADO"));
        if (request.monthlyPrice() != null && !request.monthlyPrice().isBlank()) {
            requirePositiveMoney(request.monthlyPrice(), "Precio mensual no valido");
        }
        int updated = jdbc.update("""
                update saas_company_operations
                set plan_name = ?, billing_status = ?, renewal_date = ?, monthly_price = ?,
                    support_status = ?, contact_name = ?, contact_email = ?, notes = ?, contact_phone = ?, updated_at = ?
                where company_id = ?
                """,
                planName,
                billingStatus,
                sqlTimestamp(request.renewalDate()),
                blankToNull(request.monthlyPrice()),
                supportStatus,
                blankToNull(request.contactName()),
                blankToNull(request.contactEmail()),
                blankToNull(request.notes()),
                blankToNull(request.contactPhone()),
                sqlTimestamp(now),
                companyId);
        if (updated == 0) {
            jdbc.update("""
                    insert into saas_company_operations(
                        company_id, plan_name, billing_status, renewal_date, monthly_price,
                        support_status, contact_name, contact_email, notes, contact_phone, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    companyId,
                    planName,
                    billingStatus,
                    sqlTimestamp(request.renewalDate()),
                    blankToNull(request.monthlyPrice()),
                    supportStatus,
                    blankToNull(request.contactName()),
                    blankToNull(request.contactEmail()),
                    blankToNull(request.notes()),
                    blankToNull(request.contactPhone()),
                    sqlTimestamp(now));
        }
        audit.log("UPDATE_COMPANY_OPERATIONS", "COMPANY", companyId.toString());
        return companyOperations(companyId);
    }

    @Transactional(readOnly = true)
    public List<SupportTicketResponse> supportTickets(UUID companyId) {
        ensureCompanyExists(companyId);
        return jdbc.query("""
                select t.id, t.company_id, c.name as company_name, t.title, t.description,
                       t.status, t.priority, t.created_by, t.created_at, t.updated_at
                from saas_support_ticket t
                join saas_company c on c.id = t.company_id
                where t.company_id = ?
                order by t.updated_at desc
                """, (rs, rowNum) -> supportTicket(rs), companyId);
    }

    @Transactional
    public SupportTicketResponse createSupportTicket(UUID companyId, CreateSupportTicketRequest request) {
        ensureCompanyExists(companyId);
        Instant now = clock.instant();
        UUID ticketId = UUID.randomUUID();
        jdbc.update("""
                insert into saas_support_ticket(
                    id, company_id, title, description, status, priority, created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                ticketId,
                companyId,
                request.title().trim(),
                blankToNull(request.description()),
                "ABIERTO",
                requireOneOf(request.priority(), "NORMAL", Set.of("NORMAL", "ALTA", "URGENTE")),
                currentAdminUsername(),
                sqlTimestamp(now),
                sqlTimestamp(now));
        audit.log("CREATE_SUPPORT_TICKET", "COMPANY", companyId.toString());
        return supportTicket(ticketId);
    }

    @Transactional
    public SupportTicketResponse updateSupportTicket(UUID ticketId, UpdateSupportTicketRequest request) {
        SupportTicketResponse existing = supportTicket(ticketId);
        String status = requireOneOf(request.status(), existing.status(),
                Set.of("ABIERTO", "EN_CURSO", "RESUELTO"));
        String priority = requireOneOf(request.priority(), existing.priority(),
                Set.of("NORMAL", "ALTA", "URGENTE"));
        jdbc.update("""
                update saas_support_ticket
                set status = ?, priority = ?, updated_at = ?
                where id = ?
                """, status, priority, sqlTimestamp(clock.instant()), ticketId);
        audit.log("UPDATE_SUPPORT_TICKET", "SUPPORT_TICKET", ticketId.toString());
        return supportTicket(ticketId);
    }

    @Transactional(readOnly = true)
    public List<SupportTicketCommentResponse> supportTicketComments(UUID ticketId) {
        supportTicket(ticketId);
        return jdbc.query("""
                select id, ticket_id, author, message, created_at
                from saas_support_ticket_comment
                where ticket_id = ?
                order by created_at asc
                """, (rs, rowNum) -> supportTicketComment(rs), ticketId);
    }

    @Transactional
    public SupportTicketCommentResponse createSupportTicketComment(UUID ticketId, CreateSupportTicketCommentRequest request) {
        supportTicket(ticketId);
        Instant now = clock.instant();
        UUID commentId = UUID.randomUUID();
        jdbc.update("""
                insert into saas_support_ticket_comment(id, ticket_id, author, message, created_at)
                values (?, ?, ?, ?, ?)
                """, commentId, ticketId, currentAdminUsername(), request.message().trim(), sqlTimestamp(now));
        jdbc.update("""
                update saas_support_ticket
                set updated_at = ?
                where id = ?
                """, sqlTimestamp(now), ticketId);
        audit.log("ADD_SUPPORT_TICKET_COMMENT", "SUPPORT_TICKET", ticketId.toString());
        return supportTicketComment(commentId);
    }

    @Transactional
    public void changePassword(String username, ChangeAdminPasswordRequest request) {
        SaasAdminUser user = adminUsers.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario admin no existe"));
        user.changePasswordHash(passwordHasher.hash(request.password()));
        sessions.revokeByUser("admin", user.getUsername());
        audit.log("CHANGE_ADMIN_PASSWORD", "ADMIN_USER", user.getUsername());
    }

    @Transactional
    public AdminUserResponse createUser(CreateAdminUserRequest request) {
        String username = request.username().trim();
        if (usernameExistsInAnyRealm(username)) {
            throw usernameConflict();
        }
        SaasAdminUser user;
        try {
            user = adminUsers.saveAndFlush(new SaasAdminUser(
                    UUID.randomUUID(),
                    username,
                    passwordHasher.hash(request.password()),
                    true,
                    clock.instant()));
        } catch (DataIntegrityViolationException exception) {
            throw usernameConflict(exception);
        }
        int assigned = jdbc.update("""
                insert into saas_admin_user_role(user_id, role_id)
                select ?, id from saas_admin_role where upper(name) = upper(?)
                """, user.getId(), request.roleName());
        if (assigned == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rol admin no existe");
        }
        audit.log("CREATE_ADMIN_USER", "ADMIN_USER", user.getUsername());
        return userResponse(user);
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> users() {
        return adminUsers.findAll().stream()
                .sorted(Comparator.comparing(SaasAdminUser::getUsername))
                .map(AdminService::userResponse)
                .toList();
    }

    @Transactional
    public void deactivateUser(String username) {
        SaasAdminUser user = adminUsers.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario admin no existe"));
        user.deactivate();
        sessions.revokeByUser("admin", user.getUsername());
        audit.log("DEACTIVATE_ADMIN_USER", "ADMIN_USER", user.getUsername());
    }

    @Transactional
    public void activateUser(String username, ChangeAdminPasswordRequest request) {
        SaasAdminUser user = adminUsers.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario admin no existe"));
        user.changePasswordHash(passwordHasher.hash(request.password()));
        user.passwordChanged();
        user.activate();
        sessions.revokeByUser("admin", user.getUsername());
        audit.log("ACTIVATE_ADMIN_USER", "ADMIN_USER", user.getUsername());
    }

    @Transactional(readOnly = true)
    public List<TenantUserResponse> tenantUsers(UUID companyId) {
        ensureCompanyExists(companyId);
        return jdbc.query("""
                select u.id, a.company_id, u.username, a.role_name, u.active, u.created_at
                from saas_tenant_user u join saas_tenant_company_access a on a.user_id = u.id
                where a.company_id = ? order by lower(u.username)
                """, (rs, row) -> new TenantUserResponse(rs.getObject("id", UUID.class),
                        rs.getObject("company_id", UUID.class), rs.getString("username"),
                        rs.getString("role_name"), rs.getBoolean("active"),
                        rs.getTimestamp("created_at").toInstant()), companyId);
    }

    @Transactional
    public TenantUserResponse createTenantUser(UUID companyId, CreateTenantUserRequest request) {
        planLimits.requireCapacity(companyId, PlanResource.TENANT_USERS);
        SaasCompany company = companies.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no existe"));
        String username = request.username().trim();
        if (usernameExistsInAnyRealm(username)) {
            throw usernameConflict();
        }
        TenantRole role = tenantRole(request.roleName());
        if (!role.canBeAssignedByAdmin()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "El rol OWNER esta reservado al acceso inicial de la empresa");
        }
        SaasTenantUser user;
        try {
            user = tenantUsers.saveAndFlush(new SaasTenantUser(
                    UUID.randomUUID(),
                    company,
                    username,
                    passwordHasher.hash(request.password()),
                    role.name(),
                    true,
                    clock.instant()));
        } catch (DataIntegrityViolationException exception) {
            throw usernameConflict(exception);
        }
        tenantAccess.replace(user.getUsername(), companyId, new com.tpverp.saas.access.UpdateTenantAccessRequest(
                role.name(), request.companyPrivileges() == null ? Set.of() : request.companyPrivileges(),
                request.storeIds() == null ? Set.of() : request.storeIds()));
        audit.log("CREATE_TENANT_USER", "TENANT_USER", user.getUsername());
        return tenantUserResponse(user);
    }

    @Transactional
    public void changeTenantPassword(String username, ChangeAdminPasswordRequest request) {
        SaasTenantUser user = tenantUsers.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario cliente no existe"));
        user.changePasswordHash(passwordHasher.hash(request.password()));
        sessions.revokeByUser("tenant", user.getUsername());
        audit.log("CHANGE_TENANT_PASSWORD", "TENANT_USER", user.getUsername());
    }

    @Transactional
    public void deactivateTenantUser(String username) {
        SaasTenantUser user = tenantUsers.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario cliente no existe"));
        user.deactivate();
        sessions.revokeByUser("tenant", user.getUsername());
        audit.log("DEACTIVATE_TENANT_USER", "TENANT_USER", user.getUsername());
    }

    @Transactional(readOnly = true)
    public List<BillingInvoiceResponse> billingInvoices(UUID companyId) {
        ensureCompanyExists(companyId);
        try {
            return jdbc.query(invoiceSql("where i.company_id = ?"), (rs, rowNum) -> billingInvoice(rs), companyId);
        } catch (BadSqlGrammarException exception) {
            if (missingBillingTables(exception)) {
                return List.of();
            }
            throw exception;
        }
    }

    @Transactional
    public BillingInvoiceResponse createBillingInvoice(UUID companyId, CreateBillingInvoiceRequest request) {
        ensureCompanyExists(companyId);
        requirePositiveMoney(request.amount(), "Importe de factura no valido");
        if (request.dueAt().isBefore(request.issuedAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El vencimiento no puede ser anterior a la emision");
        }
        UUID invoiceId = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into saas_billing_invoice(
                        id, company_id, number, concept, amount, currency, status, issued_at, due_at, created_at,
                        series, fiscal_year, tax_regime, tax_base, tax_rate, tax_amount)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    invoiceId,
                    companyId,
                    request.number().trim(),
                    request.concept().trim(),
                    money(request.amount()),
                    requireCurrency(request.currency()),
                    "PENDIENTE",
                    sqlTimestamp(request.issuedAt()),
                    sqlTimestamp(request.dueAt()),
                    sqlTimestamp(clock.instant()),
                    invoiceSeries(request.number()),
                    request.issuedAt().atZone(ZoneOffset.UTC).getYear(),
                    jdbc.queryForObject("select tax_regime from saas_company where id = ?", String.class, companyId),
                    null, null, null);
            audit.log("CREATE_BILLING_INVOICE", "COMPANY", companyId.toString());
            return billingInvoice(invoiceId);
        } catch (BadSqlGrammarException exception) {
            if (missingBillingTables(exception)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Facturacion pendiente de migrar. Reinicia el backend SaaS para aplicar las migraciones.", exception);
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public InvoiceFiscalDetailResponse invoiceFiscalDetail(UUID invoiceId) {
        return jdbc.query("""
                select id, company_id, number, series, fiscal_year, tax_regime,
                       fiscal_status, tax_base, tax_rate, tax_amount,
                       fiscal_reason, fiscal_legal_basis, fiscal_evidence_reference,
                       amount, currency
                from saas_billing_invoice where id = ?
                """, (rs, rowNum) -> new InvoiceFiscalDetailResponse(
                rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("number"), rs.getString("series"), rs.getInt("fiscal_year"),
                rs.getString("tax_regime"), rs.getString("fiscal_status"), nullableMoney(rs.getString("tax_base")),
                nullableMoney(rs.getString("tax_rate")), nullableMoney(rs.getString("tax_amount")),
                rs.getString("fiscal_reason"), rs.getString("fiscal_legal_basis"),
                rs.getString("fiscal_evidence_reference"),
                money(rs.getString("amount")), rs.getString("currency")), invoiceId).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura no existe"));
    }
    @Transactional
    public InvoiceFiscalDetailResponse updateInvoiceFiscal(UUID invoiceId, UpdateInvoiceFiscalRequest request) {
        InvoiceFiscalState current = jdbc.query(
                "select amount, fiscal_status, tax_regime from saas_billing_invoice where id = ? for update",
                rs -> rs.next() ? new InvoiceFiscalState(
                        amount(rs.getString("amount")), rs.getString("fiscal_status"), rs.getString("tax_regime")) : null, invoiceId);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura no existe");
        }
        String status = requireOneOf(request.fiscalStatus(), "CALCULATED",
                Set.of("CALCULATED", "NOT_APPLICABLE"));
        String taxRegime = current.taxRegime();
        if (taxRegime == null) {
            if (request.taxRegime() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El regimen fiscal es obligatorio para completar la revision");
            }
            taxRegime = request.taxRegime().name();
        } else if (request.taxRegime() != null && !taxRegime.equals(request.taxRegime().name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El regimen fiscal registrado en la factura no se puede reemplazar");
        }
        String taxBase = null;
        String taxRate = null;
        String taxAmount = null;
        String reason = null;
        String legalBasis = null;
        String evidenceReference = null;
        if ("CALCULATED".equals(status)) {
            if (request.taxBase() == null || request.taxRate() == null || request.taxAmount() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Base, tipo y cuota fiscal son obligatorios");
            }
            BigDecimal base = amount(request.taxBase());
            BigDecimal rate = amount(request.taxRate());
            BigDecimal tax = amount(request.taxAmount());
            if (base.signum() < 0 || rate.signum() < 0 || tax.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Importes fiscales no validos");
            }
            BigDecimal expectedTax = base.multiply(rate)
                    .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
            if (expectedTax.compareTo(tax.setScale(2, java.math.RoundingMode.HALF_UP)) != 0
                    || base.add(tax).setScale(2, java.math.RoundingMode.HALF_UP)
                    .compareTo(current.amount()) != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El desglose fiscal no coincide con el total de la factura");
            }
            taxBase = money(base.toPlainString());
            taxRate = money(rate.toPlainString());
            taxAmount = money(tax.toPlainString());
        } else {
            if (request.taxBase() != null || request.taxRate() != null || request.taxAmount() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Una factura no sujeta no debe incluir importes fiscales");
            }
            reason = FiscalEvidencePolicy.require(request.reason(), "Motivo fiscal", 10);
            legalBasis = FiscalEvidencePolicy.require(request.legalBasis(), "Base legal", 8);
            evidenceReference = FiscalEvidencePolicy.require(
                    request.evidenceReference(), "Referencia de evidencia", 8);
        }
        jdbc.update("""
                update saas_billing_invoice
                   set fiscal_status = ?, tax_regime = ?, tax_base = ?, tax_rate = ?, tax_amount = ?,
                       fiscal_reason = ?, fiscal_legal_basis = ?, fiscal_evidence_reference = ?
                 where id = ?
                """, status, taxRegime, taxBase, taxRate, taxAmount, reason, legalBasis, evidenceReference, invoiceId);
        jdbc.update("""
                insert into saas_invoice_fiscal_decision_audit(
                    id, invoice_id, previous_status, new_status, reason, legal_basis,
                    evidence_reference, changed_by, changed_at, previous_tax_regime, new_tax_regime)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), invoiceId, current.status(), status, reason, legalBasis,
                evidenceReference, audit.currentUsername(), sqlTimestamp(clock.instant()), current.taxRegime(), taxRegime);
        audit.log("UPDATE_INVOICE_FISCAL", "BILLING_INVOICE", invoiceId.toString());
        return invoiceFiscalDetail(invoiceId);
    }
    @Transactional
    public BillingPaymentResponse createBillingPayment(UUID invoiceId, CreateBillingPaymentRequest request) {
        InvoicePaymentState invoice = jdbc.query("""
                select amount, fiscal_status, fiscal_reason, fiscal_legal_basis,
                       fiscal_evidence_reference
                from saas_billing_invoice where id = ? for update
                """,
                rs -> rs.next() ? new InvoicePaymentState(amount(rs.getString("amount")),
                        rs.getString("fiscal_status"), rs.getString("fiscal_reason"),
                        rs.getString("fiscal_legal_basis"),
                        rs.getString("fiscal_evidence_reference")) : null, invoiceId);
        if (invoice == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura no existe");
        }
        if ("PENDING_TAX_DATA".equals(invoice.fiscalStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La factura no puede cobrarse hasta completar sus datos fiscales");
        }
        if ("NOT_APPLICABLE".equals(invoice.fiscalStatus())
                && (blankToNull(invoice.reason()) == null
                || blankToNull(invoice.legalBasis()) == null
                || blankToNull(invoice.evidenceReference()) == null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La factura no sujeta requiere motivo, base legal y evidencia antes del cobro");
        }
        BigDecimal invoiceAmount = invoice.amount();
        requirePositiveMoney(request.amount(), "Importe de pago no valido");
        String reference = blankToNull(request.reference());
        if (reference != null) {
            BillingPaymentResponse existing = paymentByReference(invoiceId, reference);
            if (existing != null) {
                if (!money(request.amount()).equals(existing.amount())
                        || !requireOneOf(request.method(), "TRANSFERENCIA", PAYMENT_METHODS).equals(existing.method())
                        || !request.paidAt().equals(existing.paidAt())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "La referencia de pago ya existe con datos diferentes");
                }
                return existing;
            }
        }
        BigDecimal paidAmount = paidAmount(invoiceId);
        BigDecimal paymentAmount = amount(request.amount());
        if (paidAmount.add(paymentAmount).compareTo(invoiceAmount) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El pago supera el saldo pendiente de la factura");
        }
        UUID paymentId = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            jdbc.update("""
                    insert into saas_billing_payment(id, invoice_id, amount, method, reference, paid_at, created_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, paymentId, invoiceId, money(request.amount()),
                    requireOneOf(request.method(), "TRANSFERENCIA", PAYMENT_METHODS), reference,
                    sqlTimestamp(request.paidAt()), sqlTimestamp(now));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Pago duplicado o superior al saldo pendiente", exception);
        }
        updateInvoiceStatus(invoiceId, invoiceAmount);
        audit.log("CREATE_BILLING_PAYMENT", "BILLING_INVOICE", invoiceId.toString());
        return billingPayment(paymentId);
    }

    @Transactional(readOnly = true)
    public List<SalesDocumentResponse> salesDocuments(UUID companyId) {
        ensureCompanyExists(companyId);
        try {
            return jdbc.query("""
                    select id, company_id, store_id, document_number, customer_code, total, currency, status, issued_at, created_at
                    from saas_sales_document
                    where company_id = ?
                    order by issued_at desc, document_number desc
                    """, (rs, rowNum) -> salesDocument(rs), companyId);
        } catch (BadSqlGrammarException exception) {
            if (missingPhase11Tables(exception)) {
                return List.of();
            }
            throw exception;
        }
    }

    @Transactional
    public SalesDocumentResponse createSalesDocument(UUID companyId, CreateSalesDocumentRequest request) {
        ensureCompanyExists(companyId);
        requirePositiveMoney(request.total(), "Total de venta no valido");
        if (request.storeId() != null) {
            ensureStoreBelongsToCompany(request.storeId(), companyId);
        }
        String saleStatus = requireOneOf(request.status(), "CONFIRMADA",
                Set.of("BORRADOR", "CONFIRMADA", "ANULADA"));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_sales_document(
                    id, company_id, store_id, document_number, customer_code, total, currency, status, issued_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                companyId,
                request.storeId(),
                request.documentNumber().trim(),
                blankToNull(request.customerCode()),
                money(request.total()),
                requireCurrency(request.currency()),
                saleStatus,
                sqlTimestamp(request.issuedAt()),
                sqlTimestamp(clock.instant()));
        audit.log("CREATE_SALES_DOCUMENT", "COMPANY", companyId.toString());
        return salesDocument(id);
    }

    @Transactional(readOnly = true)
    public List<InventoryMovementResponse> inventoryMovements(UUID companyId) {
        ensureCompanyExists(companyId);
        try {
            return jdbc.query("""
                    select id, company_id, warehouse_code, product_sku, movement_type, quantity, reason, moved_at, created_at
                    from saas_inventory_movement
                    where company_id = ?
                    order by moved_at desc
                    """, (rs, rowNum) -> inventoryMovement(rs), companyId);
        } catch (BadSqlGrammarException exception) {
            if (missingPhase11Tables(exception)) {
                return List.of();
            }
            throw exception;
        }
    }

    @Transactional
    public InventoryMovementResponse createInventoryMovement(UUID companyId, CreateInventoryMovementRequest request) {
        ensureCompanyExists(companyId);
        requirePositiveMoney(request.quantity(), "Cantidad de inventario no valida");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_inventory_movement(
                    id, company_id, warehouse_code, product_sku, movement_type, quantity, reason, moved_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                companyId,
                request.warehouseCode().trim(),
                request.productSku().trim(),
                requireOneOf(request.movementType(), "ENTRADA",
                        Set.of("ENTRADA", "SALIDA", "VENTA", "AJUSTE_POSITIVO", "AJUSTE_NEGATIVO")),
                money(request.quantity()),
                blankToNull(request.reason()),
                sqlTimestamp(request.movedAt()),
                sqlTimestamp(clock.instant()));
        audit.log("CREATE_INVENTORY_MOVEMENT", "COMPANY", companyId.toString());
        return inventoryMovement(id);
    }

    @Transactional(readOnly = true)
    public List<InventoryStockResponse> inventoryStock(UUID companyId) {
        ensureCompanyExists(companyId);
        try {
            return jdbc.query("""
                    select warehouse_code, product_sku,
                           sum(case when upper(movement_type) in ('SALIDA', 'VENTA', 'AJUSTE_NEGATIVO')
                               then -cast(quantity as decimal(19,2))
                               else cast(quantity as decimal(19,2))
                           end) as quantity
                    from saas_inventory_movement
                    where company_id = ?
                    group by warehouse_code, product_sku
                    order by warehouse_code asc, product_sku asc
                    """, (rs, rowNum) -> new InventoryStockResponse(
                    rs.getString("warehouse_code"),
                    rs.getString("product_sku"),
                    money(rs.getString("quantity"))), companyId);
        } catch (BadSqlGrammarException exception) {
            if (missingPhase11Tables(exception)) {
                return List.of();
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<IntegrationEndpointResponse> integrations() {
        try {
            return jdbc.query(integrationSql(""), (rs, rowNum) -> integration(rs));
        } catch (BadSqlGrammarException exception) {
            if (missingPhase11Tables(exception)) {
                return List.of();
            }
            throw exception;
        }
    }

    @Transactional
    public IntegrationEndpointResponse createIntegration(CreateIntegrationRequest request) {
        if (request.companyId() != null) {
            ensureCompanyExists(request.companyId());
        }
        if (request.targetUrl() != null && !request.targetUrl().isBlank()) {
            requireValidUrl(request.targetUrl());
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_integration_endpoint(
                    id, company_id, name, integration_type, status, target_url,
                    api_key, api_key_encrypted, last_sync_at, created_at)
                values (?, ?, ?, ?, ?, ?, null, ?, null, ?)
                """,
                id,
                request.companyId(),
                request.name().trim(),
                requireOneOf(request.integrationType(), "WEBHOOK", INTEGRATION_TYPES),
                requireOneOf(request.status(), "ACTIVA", Set.of("ACTIVA", "PAUSADA")),
                blankToNull(request.targetUrl()),
                integrationSecrets.encrypt(request.apiKey()),
                sqlTimestamp(clock.instant()));
        audit.log("CREATE_INTEGRATION", "INTEGRATION", id.toString());
        return integration(id);
    }

    public IntegrationEndpointResponse executeIntegration(UUID integrationId, String idempotencyKey) {
        IntegrationEndpointResponse endpoint = integration(integrationId);
        String key = normalizeIdempotencyKey(idempotencyKey);
        IntegrationRunResponse latest = latestIntegrationRun(integrationId, key);
        if (latest != null && Set.of("PENDING", "PROCESSING", "SUCCEEDED").contains(latest.status())) {
            return endpoint;
        }
        int attempt = latest == null ? 1 : latest.attempt() + 1;
        UUID runId = UUID.randomUUID();
        Instant startedAt = clock.instant();
        try {
            jdbc.update("""
                    insert into saas_integration_run(
                        id, integration_id, idempotency_key, attempt, status, delivery_mode, started_at)
                    values (?, ?, ?, ?, 'RUNNING', 'LOCAL_OUTBOX', ?)
                    """, runId, integrationId, key, attempt, sqlTimestamp(startedAt));
        } catch (DataIntegrityViolationException exception) {
            IntegrationRunResponse concurrent = latestIntegrationRun(integrationId, key);
            if (concurrent != null && Set.of("PENDING", "PROCESSING", "SUCCEEDED").contains(concurrent.status())) {
                return integration(integrationId);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La ejecucion de integracion ya esta en curso", exception);
        }
        if (!"ACTIVA".equals(endpoint.status())) {
            failIntegrationRun(runId, "INTEGRATION_INACTIVE", "La integracion no esta activa");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La integracion no esta activa");
        }
        try {
            String payload = buildLocalIntegrationPayload(endpoint);
            Instant completedAt = clock.instant();
            jdbc.update("""
                    update saas_integration_run
                    set status = 'PENDING', payload = ?, completed_at = null, next_attempt_at = ?
                    where id = ? and status = 'RUNNING'
                    """, integrationSecrets.encrypt(payload), sqlTimestamp(completedAt), runId);

            audit.log("EXECUTE_LOCAL_INTEGRATION", "INTEGRATION",
                    integrationId + "; run=" + runId + "; key=" + key);
            return integration(integrationId);
        } catch (ResponseStatusException exception) {
            failIntegrationRun(runId, "VALIDATION_ERROR", "No se pudo generar la entrega local");
            throw exception;
        } catch (RuntimeException exception) {
            failIntegrationRun(runId, "LOCAL_EXECUTION_ERROR", "No se pudo generar la entrega local");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se pudo ejecutar la integracion local", exception);
        }
    }

    @Transactional(readOnly = true)
    public List<IntegrationRunResponse> integrationRuns(UUID integrationId) {
        integration(integrationId);
        return jdbc.query(integrationRunSql("where integration_id = ?"),
                (rs, rowNum) -> integrationRun(rs), integrationId);
    }

    @Transactional(readOnly = true)
    public SaasAdvancedReportResponse advancedReports() {
        try {
            String invoicedTotal = scalarMoney("select coalesce(sum(cast(amount as decimal(19,2))), 0) from saas_billing_invoice");
            String paidTotal = scalarMoney("select coalesce(sum(cast(amount as decimal(19,2))), 0) from saas_billing_payment");
            String salesTotal = scalarMoney("select coalesce(sum(cast(total as decimal(19,2))), 0) from saas_sales_document");
            long integrationCount = count("select count(*) from saas_integration_endpoint");
            long activeIntegrationCount = count("select count(*) from saas_integration_endpoint where status = 'ACTIVA'");
            return new SaasAdvancedReportResponse(
                    companies.count(),
                    count("select count(*) from saas_billing_invoice"),
                    invoicedTotal,
                    paidTotal,
                    count("select count(*) from saas_sales_document"),
                    salesTotal,
                    count("select count(*) from saas_inventory_movement"),
                    integrationCount,
                    activeIntegrationCount);
        } catch (BadSqlGrammarException exception) {
            if (missingPhase11Tables(exception)) {
                return new SaasAdvancedReportResponse(companies.count(), 0, "0.00", "0.00", 0, "0.00", 0, 0, 0);
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<ErpCustomerResponse> erpCustomers(UUID companyId) {
        ensureCompanyExists(companyId);
        return jdbc.query("""
                select id, company_id, code, name, tax_id, email, phone, active, created_at
                from saas_erp_customer
                where company_id = ?
                order by code asc
                """, (rs, rowNum) -> erpCustomer(rs), companyId);
    }

    @Transactional
    public ErpCustomerResponse createErpCustomer(UUID companyId, CreateErpCustomerRequest request) {
        CustomerDocumentIdentity identity = CustomerDocumentIdentity.validate(request.documentType(), request.taxId());
        planLimits.requireCapacity(companyId, PlanResource.MASTER_RECORDS);
        ensureCompanyExists(companyId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_erp_customer(id, company_id, code, name, tax_id, email, phone, active, created_at, document_type)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                companyId,
                request.code().trim(),
                request.name().trim(),
                identity.documentNumber(),
                blankToNull(request.email()),
                blankToNull(request.phone()),
                true,
                sqlTimestamp(clock.instant()), identity.documentType());
        audit.log("CREATE_ERP_CUSTOMER", "COMPANY", companyId.toString());
        return erpCustomer(id);
    }

    @Transactional
    public ErpCustomerResponse deactivateErpCustomer(UUID companyId, UUID id) {
        ensureCompanyExists(companyId);
        int updated = jdbc.update("update saas_erp_customer set active = false where company_id = ? and id = ?", companyId, id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente ERP no existe");
        }
        audit.log("DEACTIVATE_ERP_CUSTOMER", "ERP_CUSTOMER", id.toString());
        return erpCustomer(id);
    }

    @Transactional(readOnly = true)
    public List<ErpProductResponse> erpProducts(UUID companyId) {
        ensureCompanyExists(companyId);
        return jdbc.query("""
                select id, company_id, sku, name, category, price, tax_rate, min_stock, active, created_at
                from saas_erp_product
                where company_id = ?
                order by sku asc
                """, (rs, rowNum) -> erpProduct(rs), companyId);
    }

    @Transactional
    public ErpProductResponse createErpProduct(UUID companyId, CreateErpProductRequest request) {
        planLimits.requireCapacity(companyId, PlanResource.MASTER_RECORDS);
        ensureCompanyExists(companyId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_erp_product(id, company_id, sku, name, category, price, tax_rate, min_stock, active, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                companyId,
                request.sku().trim(),
                request.name().trim(),
                blankToNull(request.category()),
                money(request.price()),
                money(request.taxRate()),
                money(request.minStock()),
                true,
                sqlTimestamp(clock.instant()));
        audit.log("CREATE_ERP_PRODUCT", "COMPANY", companyId.toString());
        return erpProduct(id);
    }

    @Transactional
    public ErpProductResponse deactivateErpProduct(UUID companyId, UUID id) {
        ensureCompanyExists(companyId);
        int updated = jdbc.update("update saas_erp_product set active = false where company_id = ? and id = ?", companyId, id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto ERP no existe");
        }
        audit.log("DEACTIVATE_ERP_PRODUCT", "ERP_PRODUCT", id.toString());
        return erpProduct(id);
    }

    @Transactional(readOnly = true)
    public List<ErpSupplierResponse> erpSuppliers(UUID companyId) {
        ensureCompanyExists(companyId);
        return jdbc.query("""
                select id, company_id, code, name, tax_id, email, phone, active, created_at
                from saas_erp_supplier
                where company_id = ?
                order by code asc
                """, (rs, rowNum) -> erpSupplier(rs), companyId);
    }

    @Transactional
    public ErpSupplierResponse createErpSupplier(UUID companyId, CreateErpSupplierRequest request) {
        planLimits.requireCapacity(companyId, PlanResource.MASTER_RECORDS);
        ensureCompanyExists(companyId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_erp_supplier(id, company_id, code, name, tax_id, email, phone, active, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                companyId,
                request.code().trim(),
                request.name().trim(),
                blankToNull(request.taxId()),
                blankToNull(request.email()),
                blankToNull(request.phone()),
                true,
                sqlTimestamp(clock.instant()));
        audit.log("CREATE_ERP_SUPPLIER", "COMPANY", companyId.toString());
        return erpSupplier(id);
    }

    @Transactional
    public ErpSupplierResponse deactivateErpSupplier(UUID companyId, UUID id) {
        ensureCompanyExists(companyId);
        int updated = jdbc.update("update saas_erp_supplier set active = false where company_id = ? and id = ?", companyId, id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proveedor ERP no existe");
        }
        audit.log("DEACTIVATE_ERP_SUPPLIER", "ERP_SUPPLIER", id.toString());
        return erpSupplier(id);
    }

    @Transactional(readOnly = true)
    public List<ErpWarehouseResponse> erpWarehouses(UUID companyId) {
        ensureCompanyExists(companyId);
        return jdbc.query("""
                select id, company_id, code, name, address, active, created_at
                from saas_erp_warehouse
                where company_id = ?
                order by code asc
                """, (rs, rowNum) -> erpWarehouse(rs), companyId);
    }

    @Transactional
    public ErpWarehouseResponse createErpWarehouse(UUID companyId, CreateErpWarehouseRequest request) {
        planLimits.requireCapacity(companyId, PlanResource.MASTER_RECORDS);
        ensureCompanyExists(companyId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_erp_warehouse(id, company_id, code, name, address, active, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                companyId,
                request.code().trim(),
                request.name().trim(),
                blankToNull(request.address()),
                true,
                sqlTimestamp(clock.instant()));
        audit.log("CREATE_ERP_WAREHOUSE", "COMPANY", companyId.toString());
        return erpWarehouse(id);
    }

    @Transactional
    public ErpWarehouseResponse deactivateErpWarehouse(UUID companyId, UUID id) {
        ensureCompanyExists(companyId);
        int updated = jdbc.update("update saas_erp_warehouse set active = false where company_id = ? and id = ?", companyId, id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Almacen ERP no existe");
        }
        audit.log("DEACTIVATE_ERP_WAREHOUSE", "ERP_WAREHOUSE", id.toString());
        return erpWarehouse(id);
    }

    @Transactional
    public AdminLicenseResponse renew(String reference, RenewLicenseRequest request) {
        validateLicenseTerms(
                request.validUntil(), request.maxWindows(), request.maxPda(), clock.instant());
        SaasLicense license = licenseForUpdate(reference);
        license.renew(request.validUntil(), request.maxWindows(), request.maxPda());
        if (license.getStore() != null && StoreAdministrationService.isExclusiveStoreLicense(
                jdbc, license.getId(), license.getStore().getId())) {
            license.getStore().updateLicenseConfiguration(request.validUntil(), request.maxWindows(), request.maxPda());
        }
        audit.log("RENEW_LICENSE", "LICENSE", reference);
        return response(license);
    }

    @Transactional
    public PairingCodeResponse regeneratePairingCode(String reference) {
        SaasLicense license = licenseForUpdate(reference);
        Instant now = clock.instant();
        if (license.getStatus() != LicenseSaasStatus.VALIDA || license.getValidUntil() == null || !license.getValidUntil().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La licencia debe estar vigente y desbloqueada para generar un codigo");
        }
        SaasStore store = license.getStore();
        if (store == null) {
            List<UUID> associated = jdbc.queryForList("""
                    select store_id from saas_pairing_code where license_id = ?
                    union select store_id from saas_installation where license_id = ?
                    """, UUID.class, license.getId(), license.getId());
            if (associated.size() != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "La licencia debe tener una unica tienda vinculada para generar un codigo");
            }
            store = stores.findById(associated.getFirst()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "Licencia sin tienda"));
            ensureStoreBelongsToCompany(store.getId(), license.getCompany().getId());
            license.assignStore(store);
        }
        if (!store.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Tienda inactiva");
        pairingCodes.findByStore_IdAndConsumedAtIsNullAndRevokedAtIsNull(store.getId())
                .forEach(code -> code.revoke(now, "REPLACED"));
        pairingCodes.flush();
        String code = newPairingCode();
        Instant expiresAt = now.plus(com.tpverp.saas.license.PairingCodePolicy.VALIDITY);
        try { pairingCodes.saveAndFlush(new SaasPairingCode(
                UUID.randomUUID(),
                license.getCompany(),
                store,
                license,
                code,
                expiresAt,
                now)); }
        catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se pudo generar un codigo de enlace unico; vuelve a intentarlo");
        }
        audit.log("REGENERATE_PAIRING_CODE", "LICENSE", reference);
        return new PairingCodeResponse(reference, code, expiresAt);
    }

    private SaasLicense license(String reference) {
        return licenses.findByReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Licencia no existe"));
    }

    private SaasLicense licenseForUpdate(String reference) {
        var scope = jdbc.queryForList("select company_id, store_id from saas_license where reference = ?", reference);
        if (scope.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Licencia no existe");
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", rs -> { }, scope.getFirst().get("company_id"));
        if (scope.getFirst().get("store_id") != null) {
            jdbc.queryForObject("select id from saas_store where id = ? for update", UUID.class, scope.getFirst().get("store_id"));
        }
        return licenses.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Licencia no existe"));
    }

    private void ensureCompanyExists(UUID companyId) {
        if (!companies.existsById(companyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no existe");
        }
    }

    private void ensureStoreBelongsToCompany(UUID storeId, UUID companyId) {
        SaasStore store = stores.findById(storeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tienda no existe"));
        if (!store.getCompany().getId().equals(companyId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La tienda no pertenece a la empresa indicada");
        }
    }
    private static CompanyOperationsResponse defaultOperations(UUID companyId) {
        return new CompanyOperationsResponse(companyId, "STANDARD", "PENDIENTE", null, "", "NORMAL", "", "", "");
    }

    private static String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String requireOneOf(String value, String defaultValue, Set<String> allowed) {
        String normalized = defaultText(value, defaultValue);
        if (!allowed.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Valor no permitido: " + normalized);
        }
        return normalized;
    }

    private static String requireCurrency(String value) {
        String currency = defaultText(value, "EUR");
        if (!currency.matches("[A-Z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Moneda no valida");
        }
        return currency;
    }
    private static TenantRole tenantRole(String value) {
        try {
            return TenantRole.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private SupportTicketResponse supportTicket(UUID ticketId) {
        return jdbc.query("""
                select t.id, t.company_id, c.name as company_name, t.title, t.description,
                       t.status, t.priority, t.created_by, t.created_at, t.updated_at
                from saas_support_ticket t
                join saas_company c on c.id = t.company_id
                where t.id = ?
                """, (rs, rowNum) -> supportTicket(rs), ticketId).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket no existe"));
    }

    private static SupportTicketResponse supportTicket(ResultSet rs) throws SQLException {
        return new SupportTicketResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("company_name"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getString("priority"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private SupportTicketCommentResponse supportTicketComment(UUID commentId) {
        return jdbc.query("""
                select id, ticket_id, author, message, created_at
                from saas_support_ticket_comment
                where id = ?
                """, (rs, rowNum) -> supportTicketComment(rs), commentId).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comentario no existe"));
    }

    private static SupportTicketCommentResponse supportTicketComment(ResultSet rs) throws SQLException {
        return new SupportTicketCommentResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("ticket_id", UUID.class),
                rs.getString("author"),
                rs.getString("message"),
                rs.getTimestamp("created_at").toInstant());
    }

    private BillingInvoiceResponse billingInvoice(UUID invoiceId) {
        return jdbc.query(invoiceSql("where i.id = ?"), (rs, rowNum) -> billingInvoice(rs), invoiceId).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura no existe"));
    }

    private BillingPaymentResponse billingPayment(UUID paymentId) {
        return jdbc.query("""
                select id, invoice_id, amount, method, reference, paid_at, created_at
                from saas_billing_payment
                where id = ?
                """, (rs, rowNum) -> new BillingPaymentResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("invoice_id", UUID.class),
                money(rs.getString("amount")),
                rs.getString("method"),
                rs.getString("reference"),
                rs.getTimestamp("paid_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()), paymentId).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pago no existe"));
    }

    private SalesDocumentResponse salesDocument(UUID id) {
        return jdbc.query("""
                select id, company_id, store_id, document_number, customer_code, total, currency, status, issued_at, created_at
                from saas_sales_document
                where id = ?
                """, (rs, rowNum) -> salesDocument(rs), id).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento de venta no existe"));
    }

    private static SalesDocumentResponse salesDocument(ResultSet rs) throws SQLException {
        return new SalesDocumentResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getString("document_number"),
                rs.getString("customer_code"),
                money(rs.getString("total")),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private InventoryMovementResponse inventoryMovement(UUID id) {
        return jdbc.query("""
                select id, company_id, warehouse_code, product_sku, movement_type, quantity, reason, moved_at, created_at
                from saas_inventory_movement
                where id = ?
                """, (rs, rowNum) -> inventoryMovement(rs), id).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movimiento de inventario no existe"));
    }

    private static InventoryMovementResponse inventoryMovement(ResultSet rs) throws SQLException {
        return new InventoryMovementResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("warehouse_code"),
                rs.getString("product_sku"),
                rs.getString("movement_type"),
                money(rs.getString("quantity")),
                rs.getString("reason"),
                rs.getTimestamp("moved_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private IntegrationEndpointResponse integration(UUID id) {
        return jdbc.query(integrationSql("where e.id = ?"), (rs, rowNum) -> integration(rs), id).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Integracion no existe"));
    }

    private static String integrationSql(String where) {
        return """
                select e.id, e.company_id, c.name as company_name, e.name, e.integration_type, e.status,
                       e.target_url, e.api_key, e.api_key_encrypted, e.last_sync_at, e.created_at
                from saas_integration_endpoint e
                left join saas_company c on c.id = e.company_id
                """ + where + " order by e.created_at desc";
    }

    private IntegrationEndpointResponse integration(ResultSet rs) throws SQLException {
        return new IntegrationEndpointResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("company_name"),
                rs.getString("name"),
                rs.getString("integration_type"),
                rs.getString("status"),
                rs.getString("target_url"),
                previewSecret(integrationSecret(rs)),
                rs.getTimestamp("last_sync_at") == null ? null : rs.getTimestamp("last_sync_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private IntegrationRunResponse latestIntegrationRun(UUID integrationId, String idempotencyKey) {
        return jdbc.query(integrationRunSql("where integration_id = ? and idempotency_key = ?"),
                (rs, rowNum) -> integrationRun(rs), integrationId, idempotencyKey).stream()
                .findFirst()
                .orElse(null);
    }

    private static String integrationRunSql(String where) {
        return """
                select id, integration_id, idempotency_key, attempt, status, delivery_mode,
                       error_code, error_message, started_at, completed_at
                from saas_integration_run
                """ + where + " order by attempt desc, started_at desc";
    }

    private static IntegrationRunResponse integrationRun(ResultSet rs) throws SQLException {
        return new IntegrationRunResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("integration_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getInt("attempt"),
                rs.getString("status"),
                rs.getString("delivery_mode"),
                null,
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    }

    private void failIntegrationRun(UUID runId, String errorCode, String message) {
        jdbc.update("""
                update saas_integration_run
                set status = 'FAILED', error_code = ?, error_message = ?, completed_at = ?
                where id = ? and status = 'RUNNING'
                """, errorCode, message, sqlTimestamp(clock.instant()), runId);
    }

    private String buildLocalIntegrationPayload(IntegrationEndpointResponse endpoint) {
        UUID companyId = endpoint.companyId();
        long invoices = scopedCount("saas_billing_invoice", companyId);
        long sales = scopedCount("saas_sales_document", companyId);
        long movements = scopedCount("saas_inventory_movement", companyId);
        String invoiced = scopedMoney("saas_billing_invoice", "amount", companyId);
        String sold = scopedMoney("saas_sales_document", "total", companyId);
        return String.format(Locale.ROOT,
                "{\"schemaVersion\":1,\"mode\":\"LOCAL_OUTBOX\",\"integrationType\":\"%s\","
                        + "\"companyId\":%s,\"invoices\":%d,\"invoicedTotal\":\"%s\","
                        + "\"sales\":%d,\"salesTotal\":\"%s\",\"inventoryMovements\":%d}",
                endpoint.integrationType(),
                companyId == null ? "null" : "\"" + companyId + "\"",
                invoices, invoiced, sales, sold, movements);
    }

    private long scopedCount(String table, UUID companyId) {
        String sql = "select count(*) from " + table + (companyId == null ? "" : " where company_id = ?");
        Long value = companyId == null
                ? jdbc.queryForObject(sql, Long.class)
                : jdbc.queryForObject(sql, Long.class, companyId);
        return value == null ? 0 : value;
    }

    private String scopedMoney(String table, String column, UUID companyId) {
        String sql = "select coalesce(sum(cast(" + column + " as decimal(19,2))), 0) from " + table
                + (companyId == null ? "" : " where company_id = ?");
        Object value = companyId == null
                ? jdbc.queryForObject(sql, Object.class)
                : jdbc.queryForObject(sql, Object.class, companyId);
        return money(value == null ? null : value.toString());
    }

    private static String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String normalized = value.trim();
        if (normalized.length() > 120 || !normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key no valido");
        }
        return normalized;
    }
    private static String invoiceSeries(String number) {
        String value = number == null ? "GENERAL" : number.trim().toUpperCase(Locale.ROOT);
        int separator = value.indexOf('-');
        String series = separator > 0 ? value.substring(0, separator) : "GENERAL";
        series = series.replaceAll("[^A-Z0-9_]", "");
        if (series.isBlank()) {
            return "GENERAL";
        }
        return series.substring(0, Math.min(series.length(), 24));
    }
    private static String invoiceSql(String where) {
        return """
                select i.id, i.company_id, c.name as company_name, i.number, i.concept,
                       i.amount, i.currency, i.status, i.issued_at, i.due_at, i.created_at,
                       coalesce(sum(cast(p.amount as decimal(19,2))), 0) as paid_amount,
                       case
                           when coalesce(sum(cast(p.amount as decimal(19,2))), 0) >= cast(i.amount as decimal(19,2)) then 'PAGADA'
                           when coalesce(sum(cast(p.amount as decimal(19,2))), 0) > 0 then 'PARCIAL'
                           when i.due_at < current_timestamp then 'VENCIDA'
                           else 'PENDIENTE'
                       end as effective_status
                from saas_billing_invoice i
                join saas_company c on c.id = i.company_id
                left join saas_billing_payment p on p.invoice_id = i.id
                """ + where + "\n" + """
                group by i.id, i.company_id, c.name, i.number, i.concept, i.amount, i.currency,
                         i.status, i.issued_at, i.due_at, i.created_at
                order by i.issued_at desc, i.number desc
                """;
    }

    private static BillingInvoiceResponse billingInvoice(ResultSet rs) throws SQLException {
        return new BillingInvoiceResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("company_name"),
                rs.getString("number"),
                rs.getString("concept"),
                money(rs.getString("amount")),
                money(rs.getString("paid_amount")),
                rs.getString("currency"),
                rs.getString("effective_status"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("due_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private BillingPaymentResponse paymentByReference(UUID invoiceId, String reference) {
        return jdbc.query("""
                select id, invoice_id, amount, method, reference, paid_at, created_at
                from saas_billing_payment
                where invoice_id = ? and reference = ?
                """, (rs, rowNum) -> new BillingPaymentResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("invoice_id", UUID.class),
                money(rs.getString("amount")),
                rs.getString("method"),
                rs.getString("reference"),
                rs.getTimestamp("paid_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()), invoiceId, reference).stream()
                .findFirst()
                .orElse(null);
    }

    private BigDecimal paidAmount(UUID invoiceId) {
        BigDecimal value = jdbc.queryForObject("""
                select coalesce(sum(cast(amount as decimal(19,2))), 0)
                from saas_billing_payment
                where invoice_id = ?
                """, (rs, rowNum) -> amount(rs.getString(1)), invoiceId);
        return value == null ? BigDecimal.ZERO : value;
    }
    private void updateInvoiceStatus(UUID invoiceId, BigDecimal invoiceAmount) {
        BigDecimal paidAmount = jdbc.queryForObject("""
                select coalesce(sum(cast(amount as decimal(19,2))), 0)
                from saas_billing_payment
                where invoice_id = ?
                """, (rs, rowNum) -> amount(rs.getString(1)), invoiceId);
        String status = paidAmount != null && paidAmount.compareTo(invoiceAmount) >= 0 ? "PAGADA" : "PARCIAL";
        jdbc.update("update saas_billing_invoice set status = ? where id = ?", status, invoiceId);
    }

    private static boolean missingBillingTables(BadSqlGrammarException exception) {
        String message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        boolean billingTable = message.contains("saas_billing_invoice") || message.contains("saas_billing_payment");
        boolean missingRelation = message.contains("does not exist")
                || message.contains("not found")
                || message.contains("no existe");
        return billingTable && missingRelation;
    }

    private static boolean missingOperationalTables(BadSqlGrammarException exception) {
        String message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        boolean operationalTable = message.contains("saas_company_operations")
                || message.contains("saas_sync_event")
                || message.contains("saas_support_ticket")
                || message.contains("saas_installation");
        boolean missingRelation = message.contains("does not exist")
                || message.contains("not found")
                || message.contains("no existe");
        return operationalTable && missingRelation;
    }

    private static boolean missingPhase11Tables(BadSqlGrammarException exception) {
        String message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        boolean table = message.contains("saas_sales_document")
                || message.contains("saas_inventory_movement")
                || message.contains("saas_integration_endpoint");
        boolean missingRelation = message.contains("does not exist")
                || message.contains("not found")
                || message.contains("no existe");
        return table && missingRelation;
    }

    private String currentMigration() {
        try {
            String script = jdbc.query("""
                    select script from flyway_schema_history
                    where success = true and version is not null
                    order by installed_rank desc
                    limit 1
                    """, rs -> rs.next() ? rs.getString("script") : null);
            if (script == null || script.isBlank()) {
                return "UNAVAILABLE";
            }
            return script.endsWith(".sql") ? script.substring(0, script.length() - 4) : script;
        } catch (RuntimeException exception) {
            return "UNAVAILABLE";
        }
    }
    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private String scalarMoney(String sql) {
        Object value = jdbc.queryForObject(sql, Object.class);
        return money(value == null ? null : value.toString());
    }

    private static String previewSecret(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 6) {
            return "******";
        }
        return trimmed.substring(0, 3) + "..." + trimmed.substring(trimmed.length() - 3);
    }

    private String integrationSecret(ResultSet rs) throws SQLException {
        String encrypted = rs.getString("api_key_encrypted");
        if (encrypted != null && !encrypted.isBlank()) {
            return integrationSecrets.decrypt(encrypted);
        }
        return rs.getString("api_key");
    }

    private List<CustomerHealthResponse> fallbackCustomerHealth(Instant now) {
        return companies.findAll().stream()
                .map(company -> {
                    SaasLicense license = licenses.findByCompany_Id(company.getId()).stream()
                            .max(Comparator.comparing(SaasLicense::getValidUntil))
                            .orElse(null);
                    Instant validUntil = license == null ? null : license.getValidUntil();
                    String persistedLicenseStatus = license == null
                            ? "SIN_LICENCIA" : license.getStatus().name();
                    boolean expired = validUntil == null || !now.isBefore(validUntil);
                    String licenseStatus = "VALIDA".equals(persistedLicenseStatus) && expired
                            ? LicenseSaasStatus.CADUCADA.name()
                            : persistedLicenseStatus;
                    boolean invalidLicense = !"VALIDA".equals(persistedLicenseStatus);
                    boolean expiresSoon = !expired && validUntil.isBefore(now.plus(Duration.ofDays(30)));
                    int score = invalidLicense ? 45 : expired ? 62 : expiresSoon ? 70 : 92;
                    List<String> signals = new ArrayList<>();
                    if (invalidLicense) {
                        signals.add("Licencia no valida");
                    } else if (expired) {
                        signals.add("Licencia caducada");
                    } else if (expiresSoon) {
                        signals.add("Licencia proxima a caducar");
                    } else {
                        signals.add("Operativa estable");
                    }
                    return new CustomerHealthResponse(
                            company.getId(),
                            company.getName(),
                            company.getTaxId(),
                            "STANDARD",
                            "PENDIENTE",
                            licenseStatus,
                            validUntil,
                            0,
                            0,
                            null,
                            0,
                            null,
                            0,
                            0,
                            score,
                            score < 50 ? "DANGER" : score < 75 ? "WARNING" : "OK",
                            signals);
                })
                .toList();
    }

    private ErpCustomerResponse erpCustomer(UUID id) {
        return jdbc.query("""
                select id, company_id, code, name, tax_id, email, phone, active, created_at
                from saas_erp_customer
                where id = ?
                """, (rs, rowNum) -> erpCustomer(rs), id).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente ERP no existe"));
    }

    private static ErpCustomerResponse erpCustomer(ResultSet rs) throws SQLException {
        return new ErpCustomerResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("tax_id"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant());
    }

    private ErpProductResponse erpProduct(UUID id) {
        return jdbc.query("""
                select id, company_id, sku, name, category, price, tax_rate, min_stock, active, created_at
                from saas_erp_product
                where id = ?
                """, (rs, rowNum) -> erpProduct(rs), id).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto ERP no existe"));
    }

    private static ErpProductResponse erpProduct(ResultSet rs) throws SQLException {
        return new ErpProductResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("category"),
                money(rs.getString("price")),
                money(rs.getString("tax_rate")),
                money(rs.getString("min_stock")),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant());
    }

    private ErpSupplierResponse erpSupplier(UUID id) {
        return jdbc.query("""
                select id, company_id, code, name, tax_id, email, phone, active, created_at
                from saas_erp_supplier
                where id = ?
                """, (rs, rowNum) -> erpSupplier(rs), id).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proveedor ERP no existe"));
    }

    private static ErpSupplierResponse erpSupplier(ResultSet rs) throws SQLException {
        return new ErpSupplierResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("tax_id"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant());
    }

    private ErpWarehouseResponse erpWarehouse(UUID id) {
        return jdbc.query("""
                select id, company_id, code, name, address, active, created_at
                from saas_erp_warehouse
                where id = ?
                """, (rs, rowNum) -> erpWarehouse(rs), id).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Almacen ERP no existe"));
    }

    private static ErpWarehouseResponse erpWarehouse(ResultSet rs) throws SQLException {
        return new ErpWarehouseResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("address"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static CustomerHealthResponse customerHealth(ResultSet rs, Instant now) throws SQLException {
        String billingStatus = rs.getString("billing_status");
        String persistedLicenseStatus = rs.getString("license_status");
        Instant validUntil = rs.getTimestamp("valid_until") == null ? null : rs.getTimestamp("valid_until").toInstant();
        boolean expiredLicense = validUntil == null || !now.isBefore(validUntil);
        String licenseStatus = "VALIDA".equals(persistedLicenseStatus) && expiredLicense
                ? LicenseSaasStatus.CADUCADA.name()
                : persistedLicenseStatus;
        Instant lastValidationAt = rs.getTimestamp("last_validation_at") == null ? null : rs.getTimestamp("last_validation_at").toInstant();
        Instant lastEventAt = rs.getTimestamp("last_event_at") == null ? null : rs.getTimestamp("last_event_at").toInstant();
        long staleInstallations = rs.getLong("stale_installations");
        long eventsLast7Days = rs.getLong("events_last_7_days");
        long openTickets = rs.getLong("open_tickets");
        long urgentTickets = rs.getLong("urgent_tickets");
        List<String> signals = new ArrayList<>();
        int score = 100;

        if (!"VALIDA".equals(persistedLicenseStatus)) {
            score -= 35;
            signals.add("Licencia no valida");
        }
        if (expiredLicense) {
            score -= 30;
            signals.add("Licencia caducada");
        } else if (validUntil.isBefore(now.plus(Duration.ofDays(30)))) {
            score -= 15;
            signals.add("Licencia proxima a caducar");
        }
        if (List.of("PENDIENTE", "VENCIDO", "IMPAGADO").contains(billingStatus)) {
            score -= "IMPAGADO".equals(billingStatus) ? 30 : 15;
            signals.add("Facturacion pendiente");
        }
        if (staleInstallations > 0) {
            score -= 15;
            signals.add("Instalaciones sin validar");
        }
        if (eventsLast7Days == 0) {
            score -= 10;
            signals.add("Sin actividad reciente");
        }
        if (openTickets > 0) {
            score -= 10;
            signals.add("Tickets abiertos");
        }
        if (urgentTickets > 0) {
            score -= 20;
            signals.add("Tickets urgentes");
        }

        int finalScore = Math.max(0, score);
        String riskLevel = finalScore < 50 ? "DANGER" : finalScore < 75 ? "WARNING" : "OK";
        if (signals.isEmpty()) {
            signals.add("Operativa estable");
        }
        return new CustomerHealthResponse(
                rs.getObject("company_id", UUID.class),
                rs.getString("company_name"),
                rs.getString("tax_id"),
                rs.getString("plan_name"),
                billingStatus,
                licenseStatus,
                validUntil,
                rs.getLong("installations"),
                staleInstallations,
                lastValidationAt,
                eventsLast7Days,
                lastEventAt,
                openTickets,
                urgentTickets,
                finalScore,
                riskLevel,
                signals);
    }

    private static BillingCompanyResponse billingCompany(ResultSet rs, Instant now) throws SQLException {
        Instant renewalDate = rs.getTimestamp("renewal_date") == null ? null : rs.getTimestamp("renewal_date").toInstant();
        Instant validUntil = rs.getTimestamp("valid_until") == null ? null : rs.getTimestamp("valid_until").toInstant();
        boolean renewalDueSoon = (renewalDate != null && !renewalDate.isAfter(now.plus(Duration.ofDays(30))))
                || (validUntil != null && !validUntil.isAfter(now.plus(Duration.ofDays(30))));
        String billingStatus = rs.getString("billing_status");
        boolean overdue = List.of("VENCIDO", "IMPAGADO").contains(billingStatus)
                || (renewalDate != null && renewalDate.isBefore(now));
        return new BillingCompanyResponse(
                rs.getObject("company_id", UUID.class),
                rs.getString("company_name"),
                rs.getString("tax_id"),
                rs.getString("plan_name"),
                billingStatus,
                renewalDate,
                rs.getString("monthly_price"),
                rs.getString("license_reference"),
                validUntil,
                renewalDueSoon,
                overdue);
    }

    private static BigDecimal amount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim().replace(",", "."));
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    private static OffsetDateTime sqlTimestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static void requirePositiveMoney(String value, String message) {
        if (amount(value).compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private static void requireValidUrl(String value) {
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL de integracion no valida");
            }
        } catch (URISyntaxException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL de integracion no valida", exception);
        }
    }

    private static String nullableMoney(String value) {
        return value == null ? null : money(value);
    }
    private static String money(String value) {
        return amount(value).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static void validateLicenseTerms(
            Instant validUntil, int maxWindows, int maxPda, Instant now) {
        if (validUntil == null || !validUntil.isAfter(now)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "validUntil debe ser posterior al momento actual");
        }
        if (maxWindows < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "maxWindows debe ser al menos 1");
        }
        if (maxPda < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "maxPda no puede ser negativo");
        }
    }

    private static <T> T provisioningValue(java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private static String currentAdminUsername() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object username = attributes.getRequest().getAttribute(AdminAuditService.USERNAME_ATTRIBUTE);
            if (username != null) {
                return username.toString();
            }
        }
        return "system";
    }

    private AdminLicenseResponse response(SaasLicense license) {
        return new AdminLicenseResponse(
                license.getReference(),
                effectiveStatus(license, clock.instant()),
                license.getValidUntil(),
                license.getMaxWindows(),
                license.getMaxPda());
    }

    private static LicenseSummaryResponse licenseSummary(SaasLicense license, Instant now) {
        return new LicenseSummaryResponse(
                license.getReference(),
                license.getCompany().getId(),
                license.getCompany().getName(),
                license.getCompany().getTaxId(),
                license.getCompany().getTaxpayerType(),
                license.getStore() == null ? license.getCompany().getTaxRegime() : license.getStore().getTaxRegime(),
                license.getStore() == null ? null : license.getStore().getCommercialProfile(),
                effectiveStatus(license, now),
                license.getValidUntil(),
                license.getMaxWindows(),
                license.getMaxPda());
    }

    private static LicenseSaasStatus effectiveStatus(
            SaasLicense license, Instant now) {
        return license.getStatus() == LicenseSaasStatus.VALIDA
                && !now.isBefore(license.getValidUntil())
                ? LicenseSaasStatus.CADUCADA
                : license.getStatus();
    }

    private static InstallationSummaryResponse installationResponse(SaasInstallation installation) {
        return installationResponse(installation, null);
    }

    private static InstallationSummaryResponse installationResponse(SaasInstallation installation, Instant lastSyncAt) {
        return new InstallationSummaryResponse(
                installation.getInstallationId(),
                installation.getInstallationReference(),
                installation.getCompany().getId(),
                installation.getStore().getId(),
                installation.getLicense().getReference(),
                installation.getLinkedAt(),
                installation.getLastValidatedAt(),
                lastSyncAt,
                installation.getAppVersion(),
                installation.getOperatingSystem(),
                installation.getTerminalName(),
                installation.getLastIp(),
                installation.isActive(),
                installation.getRevokedAt(),
                installation.getRevokedBy(),
                installation.getRevocationReason(),
                installation.getVersion());
    }

    private static AdminUserResponse userResponse(SaasAdminUser user) {
        return new AdminUserResponse(user.getUsername(), user.isActive(), user.getCreatedAt());
    }

    private static TenantUserResponse tenantUserResponse(SaasTenantUser user) {
        return new TenantUserResponse(
                user.getId(),
                user.getCompany().getId(),
                user.getUsername(),
                user.getRoleName(),
                user.isActive(),
                user.getCreatedAt());
    }

    private boolean usernameExistsInAnyRealm(String username) {
        return adminUsers.existsByUsernameIgnoreCase(username)
                || tenantUsers.existsByUsernameIgnoreCase(username);
    }

    private static ResponseStatusException usernameConflict() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "El nombre de usuario ya pertenece a otra cuenta SaaS");
    }

    private static ResponseStatusException usernameConflict(DataIntegrityViolationException exception) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "El nombre de usuario ya pertenece a otra cuenta SaaS",
                exception);
    }

    private String newPairingCode() {
        StringBuilder value = new StringBuilder("TPV-");
        for (int index = 0; index < 12; index++) {
            value.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return value.toString();
    }

    private record InvoicePaymentState(
            BigDecimal amount,
            String fiscalStatus,
            String reason,
            String legalBasis,
            String evidenceReference) {
    }

    private record InvoiceFiscalState(BigDecimal amount, String status, String taxRegime) {
    }

}
