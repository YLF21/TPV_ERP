package com.tpverp.saas.admin;

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
import com.tpverp.saas.tenant.SaasTenantUser;
import com.tpverp.saas.tenant.SaasTenantUserRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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

    private final SaasCompanyRepository companies;
    private final SaasStoreRepository stores;
    private final SaasLicenseRepository licenses;
    private final SaasInstallationRepository installations;
    private final SaasPairingCodeRepository pairingCodes;
    private final SaasAdminUserRepository adminUsers;
    private final SaasTenantUserRepository tenantUsers;
    private final AdminPasswordHasher passwordHasher;
    private final AdminAuditService audit;
    private final JdbcTemplate jdbc;
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
            AdminAuditService audit,
            JdbcTemplate jdbc,
            Clock clock) {
        this.companies = companies;
        this.stores = stores;
        this.licenses = licenses;
        this.installations = installations;
        this.pairingCodes = pairingCodes;
        this.adminUsers = adminUsers;
        this.tenantUsers = tenantUsers;
        this.passwordHasher = passwordHasher;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public CreateCompanyResponse createCompany(CreateCompanyRequest request) {
        Instant now = clock.instant();
        var company = companies.save(new SaasCompany(
                UUID.randomUUID(),
                request.name(),
                request.taxId().toUpperCase(Locale.ROOT),
                request.taxpayerType(),
                request.impuestos(),
                now));
        var store = stores.save(new SaasStore(
                UUID.randomUUID(),
                company,
                request.storeCode(),
                request.storeName() == null || request.storeName().isBlank() ? request.storeCode() : request.storeName(),
                now));
        String licenseReference = "LIC-" + company.getTaxId() + "-" + store.getCode();
        var license = licenses.save(new SaasLicense(
                UUID.randomUUID(),
                company,
                licenseReference,
                request.validUntil(),
                Math.max(1, request.maxWindows()),
                Math.max(0, request.maxPda()),
                now));
        String pairingCode = newPairingCode();
        pairingCodes.save(new SaasPairingCode(
                UUID.randomUUID(),
                company,
                store,
                license,
                pairingCode,
                now.plus(Duration.ofDays(7)),
                now));
        TenantInitialAccess tenantAccess = createDefaultTenantUser(company, now);
        audit.log("ADD_COMPANY", "COMPANY", company.getId().toString());
        return new CreateCompanyResponse(
                company.getId(),
                store.getId(),
                licenseReference,
                pairingCode,
                license.getValidUntil(),
                tenantAccess.username(),
                tenantAccess.initialPassword());
    }

    @Transactional
    public AdminLicenseResponse block(String reference) {
        SaasLicense license = license(reference);
        license.block();
        audit.log("BLOCK_LICENSE", "LICENSE", reference);
        return response(license);
    }

    @Transactional
    public AdminLicenseResponse unblock(String reference) {
        SaasLicense license = license(reference);
        license.unblock();
        audit.log("UNBLOCK_LICENSE", "LICENSE", reference);
        return response(license);
    }

    @Transactional(readOnly = true)
    public List<LicenseSummaryResponse> licenses() {
        return licenses.findAll().stream()
                .sorted(Comparator.comparing(SaasLicense::getReference))
                .map(AdminService::licenseSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstallationSummaryResponse> installations() {
        return installations.findAllByOrderByLinkedAtDesc().stream()
                .map(AdminService::installationResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminSessionResponse session() {
        String username = currentAdminUsername();
        return new AdminSessionResponse(username, adminUsers.permissionCodes(username));
    }

    @Transactional(readOnly = true)
    public SaasStatusResponse status() {
        return new SaasStatusResponse(
                clock.instant(),
                "saas-api-v1",
                "V11__saas_phase10_operations_reports_integrations",
                List.of(
                        "licenses",
                        "installations",
                        "sync",
                        "support",
                        "health",
                        "billing",
                        "subscriptions",
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
                    if ("BLOQUEADA_MANUAL".equals(license.getStatus())) {
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
                now), now.plus(Duration.ofDays(15)));
        return java.util.stream.Stream.of(licenseNotifications, installationNotifications, billingNotifications)
                .flatMap(List::stream)
                .sorted(Comparator.comparing(AdminNotificationResponse::severity).thenComparing(AdminNotificationResponse::companyName))
                .toList();
    }

    @Transactional
    public void markNotificationRead(String notificationId) {
        jdbc.update("""
                insert into saas_admin_notification_read(username, notification_id, read_at)
                values (?, ?, ?)
                on conflict (username, notification_id) do update set read_at = excluded.read_at
                """, currentAdminUsername(), notificationId, clock.instant());
        audit.log("READ_NOTIFICATION", "ADMIN_NOTIFICATION", notificationId);
    }

    @Transactional(readOnly = true)
    public TechnicalStatusResponse technicalStatus() {
        Instant now = clock.instant();
        Long eventsToday = jdbc.queryForObject("""
                select count(*) from saas_sync_event
                where received_at >= ?
                """, Long.class, now.minus(Duration.ofDays(1)));
        Long openTickets = jdbc.queryForObject("""
                select count(*) from saas_support_ticket
                where status <> 'RESUELTO'
                """, Long.class);
        Long staleInstallations = jdbc.queryForObject("""
                select count(*) from saas_installation
                where last_validated_at is null or last_validated_at < ?
                """, Long.class, now.minus(Duration.ofHours(48)));
        Instant lastSyncAt = jdbc.query("""
                select max(received_at) as last_sync_at from saas_sync_event
                """, rs -> rs.next() && rs.getTimestamp("last_sync_at") != null
                        ? rs.getTimestamp("last_sync_at").toInstant()
                        : null);
        return new TechnicalStatusResponse(
                now,
                companies.count(),
                licenses.count(),
                installations.count(),
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
                       o.monthly_price,
                       l.reference as license_reference,
                       l.valid_until
                from saas_company c
                left join saas_company_operations o on o.company_id = c.id
                left join saas_license l on l.company_id = c.id
                order by c.name asc, l.valid_until desc
                """, (rs, rowNum) -> billingCompany(rs, now));
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
                           (select count(*) from saas_installation i where i.company_id = c.id) as installations,
                           (select count(*) from saas_installation i where i.company_id = c.id and (i.last_validated_at is null or i.last_validated_at < ?)) as stale_installations,
                           (select max(i.last_validated_at) from saas_installation i where i.company_id = c.id) as last_validation_at,
                           (select count(*) from saas_sync_event e where e.company_id = c.id and e.received_at >= ?) as events_last_7_days,
                           (select max(e.received_at) from saas_sync_event e where e.company_id = c.id) as last_event_at,
                           (select count(*) from saas_support_ticket t where t.company_id = c.id and t.status <> 'RESUELTO') as open_tickets,
                           (select count(*) from saas_support_ticket t where t.company_id = c.id and t.status <> 'RESUELTO' and t.priority = 'URGENTE') as urgent_tickets
                    from saas_company c
                    left join saas_company_operations o on o.company_id = c.id
                    order by c.name asc
                    """, (rs, rowNum) -> customerHealth(rs, now), now.minus(Duration.ofHours(48)), now.minus(Duration.ofDays(7)));
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
    public LicenseSummaryResponse editCompany(UUID companyId, EditCompanyDataRequest request) {
        SaasCompany company = companies.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no existe"));
        company.updateData(request.name(), request.taxpayerType(), request.impuestos());
        audit.log("EDIT_COMPANY_DATA", "COMPANY", companyId.toString());
        return licenses.findByCompany_Id(companyId).stream()
                .findFirst()
                .map(AdminService::licenseSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Empresa sin licencia"));
    }

    @Transactional(readOnly = true)
    public CompanyOperationsResponse companyOperations(UUID companyId) {
        ensureCompanyExists(companyId);
        return jdbc.query("""
                select company_id, plan_name, billing_status, renewal_date, monthly_price,
                       support_status, contact_name, contact_email, notes
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
                rs.getString("notes")), companyId).stream()
                .findFirst()
                .orElseGet(() -> defaultOperations(companyId));
    }

    @Transactional
    public CompanyOperationsResponse updateCompanyOperations(UUID companyId, UpdateCompanyOperationsRequest request) {
        ensureCompanyExists(companyId);
        Instant now = clock.instant();
        String planName = defaultText(request.planName(), "STANDARD");
        String billingStatus = defaultText(request.billingStatus(), "PENDIENTE");
        String supportStatus = defaultText(request.supportStatus(), "NORMAL");
        int updated = jdbc.update("""
                update saas_company_operations
                set plan_name = ?, billing_status = ?, renewal_date = ?, monthly_price = ?,
                    support_status = ?, contact_name = ?, contact_email = ?, notes = ?, updated_at = ?
                where company_id = ?
                """,
                planName,
                billingStatus,
                request.renewalDate(),
                blankToNull(request.monthlyPrice()),
                supportStatus,
                blankToNull(request.contactName()),
                blankToNull(request.contactEmail()),
                blankToNull(request.notes()),
                now,
                companyId);
        if (updated == 0) {
            jdbc.update("""
                    insert into saas_company_operations(
                        company_id, plan_name, billing_status, renewal_date, monthly_price,
                        support_status, contact_name, contact_email, notes, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    companyId,
                    planName,
                    billingStatus,
                    request.renewalDate(),
                    blankToNull(request.monthlyPrice()),
                    supportStatus,
                    blankToNull(request.contactName()),
                    blankToNull(request.contactEmail()),
                    blankToNull(request.notes()),
                    now);
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
                defaultText(request.priority(), "NORMAL"),
                currentAdminUsername(),
                now,
                now);
        audit.log("CREATE_SUPPORT_TICKET", "COMPANY", companyId.toString());
        return supportTicket(ticketId);
    }

    @Transactional
    public SupportTicketResponse updateSupportTicket(UUID ticketId, UpdateSupportTicketRequest request) {
        SupportTicketResponse existing = supportTicket(ticketId);
        String status = defaultText(request.status(), existing.status());
        String priority = defaultText(request.priority(), existing.priority());
        jdbc.update("""
                update saas_support_ticket
                set status = ?, priority = ?, updated_at = ?
                where id = ?
                """, status, priority, clock.instant(), ticketId);
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
                """, commentId, ticketId, currentAdminUsername(), request.message().trim(), now);
        jdbc.update("""
                update saas_support_ticket
                set updated_at = ?
                where id = ?
                """, now, ticketId);
        audit.log("ADD_SUPPORT_TICKET_COMMENT", "SUPPORT_TICKET", ticketId.toString());
        return supportTicketComment(commentId);
    }

    @Transactional
    public void changePassword(String username, ChangeAdminPasswordRequest request) {
        SaasAdminUser user = adminUsers.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario admin no existe"));
        user.changePasswordHash(passwordHasher.hash(request.password()));
        audit.log("CHANGE_ADMIN_PASSWORD", "ADMIN_USER", user.getUsername());
    }

    @Transactional
    public AdminUserResponse createUser(CreateAdminUserRequest request) {
        String username = request.username().trim();
        if (adminUsers.existsByUsernameIgnoreCase(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Usuario admin ya existe");
        }
        SaasAdminUser user = adminUsers.saveAndFlush(new SaasAdminUser(
                UUID.randomUUID(),
                username,
                passwordHasher.hash(request.password()),
                true,
                clock.instant()));
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
        audit.log("DEACTIVATE_ADMIN_USER", "ADMIN_USER", user.getUsername());
    }

    @Transactional(readOnly = true)
    public List<TenantUserResponse> tenantUsers(UUID companyId) {
        ensureCompanyExists(companyId);
        return tenantUsers.findByCompany_IdOrderByUsernameAsc(companyId).stream()
                .map(AdminService::tenantUserResponse)
                .toList();
    }

    @Transactional
    public TenantUserResponse createTenantUser(UUID companyId, CreateTenantUserRequest request) {
        SaasCompany company = companies.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no existe"));
        String username = request.username().trim();
        if (tenantUsers.existsByUsernameIgnoreCase(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Usuario cliente ya existe");
        }
        SaasTenantUser user = tenantUsers.save(new SaasTenantUser(
                UUID.randomUUID(),
                company,
                username,
                passwordHasher.hash(request.password()),
                defaultText(request.roleName(), "VIEWER"),
                true,
                clock.instant()));
        audit.log("CREATE_TENANT_USER", "TENANT_USER", user.getUsername());
        return tenantUserResponse(user);
    }

    @Transactional
    public void changeTenantPassword(String username, ChangeAdminPasswordRequest request) {
        SaasTenantUser user = tenantUsers.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario cliente no existe"));
        user.changePasswordHash(passwordHasher.hash(request.password()));
        audit.log("CHANGE_TENANT_PASSWORD", "TENANT_USER", user.getUsername());
    }

    @Transactional
    public void deactivateTenantUser(String username) {
        SaasTenantUser user = tenantUsers.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario cliente no existe"));
        user.deactivate();
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
                        id, company_id, number, concept, amount, currency, status, issued_at, due_at, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    invoiceId,
                    companyId,
                    request.number().trim(),
                    request.concept().trim(),
                    money(request.amount()),
                    defaultText(request.currency(), "EUR"),
                    "PENDIENTE",
                    request.issuedAt(),
                    request.dueAt(),
                    clock.instant());
            audit.log("CREATE_BILLING_INVOICE", "COMPANY", companyId.toString());
            return billingInvoice(invoiceId);
        } catch (BadSqlGrammarException exception) {
            if (missingBillingTables(exception)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Facturacion pendiente de migrar. Reinicia el backend SaaS para aplicar V9.", exception);
            }
            throw exception;
        }
    }

    @Transactional
    public BillingPaymentResponse createBillingPayment(UUID invoiceId, CreateBillingPaymentRequest request) {
        BillingInvoiceResponse invoice = billingInvoice(invoiceId);
        requirePositiveMoney(request.amount(), "Importe de pago no valido");
        UUID paymentId = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                insert into saas_billing_payment(id, invoice_id, amount, method, reference, paid_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                paymentId,
                invoiceId,
                money(request.amount()),
                defaultText(request.method(), "TRANSFERENCIA"),
                blankToNull(request.reference()),
                request.paidAt(),
                now);
        updateInvoiceStatus(invoiceId, amount(invoice.amount()));
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
                defaultText(request.currency(), "EUR"),
                defaultText(request.status(), "CONFIRMADA"),
                request.issuedAt(),
                clock.instant());
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
                defaultText(request.movementType(), "ENTRADA"),
                money(request.quantity()),
                blankToNull(request.reason()),
                request.movedAt(),
                clock.instant());
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
    public List<SubscriptionResponse> subscriptions() {
        try {
            return jdbc.query(subscriptionSql(""), (rs, rowNum) -> subscription(rs));
        } catch (BadSqlGrammarException exception) {
            if (missingPhase11Tables(exception)) {
                return List.of();
            }
            throw exception;
        }
    }

    @Transactional
    public SubscriptionResponse createSubscription(UUID companyId, CreateSubscriptionRequest request) {
        ensureCompanyExists(companyId);
        requirePositiveMoney(request.amount(), "Importe de suscripcion no valido");
        if (request.nextBillingAt() != null && request.nextBillingAt().isBefore(request.startedAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La proxima facturacion no puede ser anterior al inicio");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_subscription(
                    id, company_id, plan_name, status, billing_cycle, amount, currency, started_at, next_billing_at, cancelled_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?)
                """,
                id,
                companyId,
                request.planName().trim(),
                defaultText(request.status(), "ACTIVA"),
                defaultText(request.billingCycle(), "MENSUAL"),
                money(request.amount()),
                defaultText(request.currency(), "EUR"),
                request.startedAt(),
                request.nextBillingAt(),
                clock.instant());
        jdbc.update("""
                insert into saas_company_operations(company_id, plan_name, billing_status, renewal_date, monthly_price, support_status, updated_at)
                values (?, ?, 'PENDIENTE', ?, ?, 'NORMAL', ?)
                on conflict (company_id) do update set
                    plan_name = excluded.plan_name,
                    renewal_date = excluded.renewal_date,
                    monthly_price = excluded.monthly_price,
                    updated_at = excluded.updated_at
                """, companyId, request.planName().trim(), request.nextBillingAt(), money(request.amount()), clock.instant());
        audit.log("CREATE_SUBSCRIPTION", "COMPANY", companyId.toString());
        return subscription(id);
    }

    @Transactional
    public SubscriptionResponse cancelSubscription(UUID subscriptionId) {
        jdbc.update("""
                update saas_subscription
                set status = 'CANCELADA', cancelled_at = ?
                where id = ?
                """, clock.instant(), subscriptionId);
        audit.log("CANCEL_SUBSCRIPTION", "SUBSCRIPTION", subscriptionId.toString());
        return subscription(subscriptionId);
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
                    id, company_id, name, integration_type, status, target_url, api_key, last_sync_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, null, ?)
                """,
                id,
                request.companyId(),
                request.name().trim(),
                defaultText(request.integrationType(), "WEBHOOK"),
                defaultText(request.status(), "ACTIVA"),
                blankToNull(request.targetUrl()),
                blankToNull(request.apiKey()),
                clock.instant());
        audit.log("CREATE_INTEGRATION", "INTEGRATION", id.toString());
        return integration(id);
    }

    @Transactional
    public IntegrationEndpointResponse markIntegrationSynced(UUID integrationId) {
        jdbc.update("update saas_integration_endpoint set last_sync_at = ? where id = ?", clock.instant(), integrationId);
        audit.log("SYNC_INTEGRATION", "INTEGRATION", integrationId.toString());
        return integration(integrationId);
    }

    @Transactional(readOnly = true)
    public SaasAdvancedReportResponse advancedReports() {
        try {
            long subscriptionCount = count("select count(*) from saas_subscription");
            String subscriptionMrr = scalarMoney("""
                    select coalesce(sum(cast(amount as decimal(19,2))), 0)
                    from saas_subscription
                    where status = 'ACTIVA' and billing_cycle = 'MENSUAL'
                    """);
            String invoicedTotal = scalarMoney("select coalesce(sum(cast(amount as decimal(19,2))), 0) from saas_billing_invoice");
            String paidTotal = scalarMoney("select coalesce(sum(cast(amount as decimal(19,2))), 0) from saas_billing_payment");
            String salesTotal = scalarMoney("select coalesce(sum(cast(total as decimal(19,2))), 0) from saas_sales_document");
            long integrationCount = count("select count(*) from saas_integration_endpoint");
            long activeIntegrationCount = count("select count(*) from saas_integration_endpoint where status = 'ACTIVA'");
            return new SaasAdvancedReportResponse(
                    companies.count(),
                    subscriptionCount,
                    subscriptionMrr,
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
                return new SaasAdvancedReportResponse(companies.count(), 0, "0.00", 0, "0.00", "0.00", 0, "0.00", 0, 0, 0);
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
        ensureCompanyExists(companyId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into saas_erp_customer(id, company_id, code, name, tax_id, email, phone, active, created_at)
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
                clock.instant());
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
                clock.instant());
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
                clock.instant());
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
                clock.instant());
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
        SaasLicense license = license(reference);
        license.renew(request.validUntil(), request.maxWindows(), request.maxPda());
        audit.log("RENEW_LICENSE", "LICENSE", reference);
        return response(license);
    }

    @Transactional
    public PairingCodeResponse regeneratePairingCode(String reference) {
        Instant now = clock.instant();
        SaasLicense license = license(reference);
        pairingCodes.findByLicense_ReferenceAndConsumedAtIsNull(reference)
                .forEach(code -> code.expire(now));
        SaasStore store = stores.findByCompany_IdOrderByCodeAsc(license.getCompany().getId()).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Licencia sin tienda"));
        String code = newPairingCode();
        Instant expiresAt = now.plus(Duration.ofDays(7));
        pairingCodes.save(new SaasPairingCode(
                UUID.randomUUID(),
                license.getCompany(),
                store,
                license,
                code,
                expiresAt,
                now));
        audit.log("REGENERATE_PAIRING_CODE", "LICENSE", reference);
        return new PairingCodeResponse(reference, code, expiresAt);
    }

    private SaasLicense license(String reference) {
        return licenses.findByReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Licencia no existe"));
    }

    private void ensureCompanyExists(UUID companyId) {
        if (!companies.existsById(companyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no existe");
        }
    }

    private static CompanyOperationsResponse defaultOperations(UUID companyId) {
        return new CompanyOperationsResponse(companyId, "STANDARD", "PENDIENTE", null, "", "NORMAL", "", "", "");
    }

    private static String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
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

    private SubscriptionResponse subscription(UUID id) {
        return jdbc.query(subscriptionSql("where s.id = ?"), (rs, rowNum) -> subscription(rs), id).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suscripcion no existe"));
    }

    private static String subscriptionSql(String where) {
        return """
                select s.id, s.company_id, c.name as company_name, s.plan_name, s.status, s.billing_cycle,
                       s.amount, s.currency, s.started_at, s.next_billing_at, s.cancelled_at, s.created_at
                from saas_subscription s
                join saas_company c on c.id = s.company_id
                """ + where + " order by s.created_at desc";
    }

    private static SubscriptionResponse subscription(ResultSet rs) throws SQLException {
        return new SubscriptionResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("company_name"),
                rs.getString("plan_name"),
                rs.getString("status"),
                rs.getString("billing_cycle"),
                money(rs.getString("amount")),
                rs.getString("currency"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("next_billing_at") == null ? null : rs.getTimestamp("next_billing_at").toInstant(),
                rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toInstant(),
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
                       e.target_url, e.api_key, e.last_sync_at, e.created_at
                from saas_integration_endpoint e
                left join saas_company c on c.id = e.company_id
                """ + where + " order by e.created_at desc";
    }

    private static IntegrationEndpointResponse integration(ResultSet rs) throws SQLException {
        return new IntegrationEndpointResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("company_name"),
                rs.getString("name"),
                rs.getString("integration_type"),
                rs.getString("status"),
                rs.getString("target_url"),
                previewSecret(rs.getString("api_key")),
                rs.getTimestamp("last_sync_at") == null ? null : rs.getTimestamp("last_sync_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private static String invoiceSql(String where) {
        return """
                select i.id, i.company_id, c.name as company_name, i.number, i.concept,
                       i.amount, i.currency, i.status, i.issued_at, i.due_at, i.created_at,
                       coalesce(sum(cast(p.amount as decimal(19,2))), 0) as paid_amount
                from saas_billing_invoice i
                join saas_company c on c.id = i.company_id
                left join saas_billing_payment p on p.invoice_id = i.id
                """ + where + """
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
                rs.getString("status"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("due_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
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
        boolean table = message.contains("saas_subscription")
                || message.contains("saas_sales_document")
                || message.contains("saas_inventory_movement")
                || message.contains("saas_integration_endpoint");
        boolean missingRelation = message.contains("does not exist")
                || message.contains("not found")
                || message.contains("no existe");
        return table && missingRelation;
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

    private List<CustomerHealthResponse> fallbackCustomerHealth(Instant now) {
        return companies.findAll().stream()
                .map(company -> {
                    SaasLicense license = licenses.findByCompany_Id(company.getId()).stream()
                            .max(Comparator.comparing(SaasLicense::getValidUntil))
                            .orElse(null);
                    Instant validUntil = license == null ? null : license.getValidUntil();
                    String licenseStatus = license == null ? "SIN_LICENCIA" : license.getStatus().name();
                    boolean invalidLicense = !"VALIDA".equals(licenseStatus);
                    boolean expired = validUntil == null || validUntil.isBefore(now);
                    boolean expiresSoon = !expired && validUntil.isBefore(now.plus(Duration.ofDays(30)));
                    int score = invalidLicense ? 45 : expiresSoon ? 70 : 92;
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
        String licenseStatus = rs.getString("license_status");
        Instant validUntil = rs.getTimestamp("valid_until") == null ? null : rs.getTimestamp("valid_until").toInstant();
        Instant lastValidationAt = rs.getTimestamp("last_validation_at") == null ? null : rs.getTimestamp("last_validation_at").toInstant();
        Instant lastEventAt = rs.getTimestamp("last_event_at") == null ? null : rs.getTimestamp("last_event_at").toInstant();
        long staleInstallations = rs.getLong("stale_installations");
        long eventsLast7Days = rs.getLong("events_last_7_days");
        long openTickets = rs.getLong("open_tickets");
        long urgentTickets = rs.getLong("urgent_tickets");
        List<String> signals = new ArrayList<>();
        int score = 100;

        if (!"VALIDA".equals(licenseStatus)) {
            score -= 35;
            signals.add("Licencia no valida");
        }
        if (validUntil == null || validUntil.isBefore(now)) {
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

    private static String money(String value) {
        return amount(value).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
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
                license.getStatus(),
                license.getValidUntil(),
                license.getMaxWindows(),
                license.getMaxPda());
    }

    private static LicenseSummaryResponse licenseSummary(SaasLicense license) {
        return new LicenseSummaryResponse(
                license.getReference(),
                license.getCompany().getId(),
                license.getCompany().getName(),
                license.getCompany().getTaxId(),
                license.getStatus(),
                license.getValidUntil(),
                license.getMaxWindows(),
                license.getMaxPda());
    }

    private static InstallationSummaryResponse installationResponse(SaasInstallation installation) {
        return new InstallationSummaryResponse(
                installation.getInstallationId(),
                installation.getInstallationReference(),
                installation.getCompany().getId(),
                installation.getStore().getId(),
                installation.getLicense().getReference(),
                installation.getLinkedAt(),
                installation.getLastValidatedAt(),
                installation.getAppVersion(),
                installation.getOperatingSystem(),
                installation.getTerminalName(),
                installation.getLastIp());
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

    private TenantInitialAccess createDefaultTenantUser(SaasCompany company, Instant now) {
        String baseUsername = company.getTaxId().toLowerCase(Locale.ROOT);
        String username = baseUsername;
        int suffix = 2;
        while (tenantUsers.existsByUsernameIgnoreCase(username)) {
            username = baseUsername + "-" + suffix;
            suffix++;
        }
        String initialPassword = newInitialPassword();
        tenantUsers.save(new SaasTenantUser(
                UUID.randomUUID(),
                company,
                username,
                passwordHasher.hash(initialPassword),
                "OWNER",
                true,
                now));
        return new TenantInitialAccess(username, initialPassword);
    }

    private String newInitialPassword() {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < 14; index++) {
            value.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return value.toString();
    }

    private String newPairingCode() {
        StringBuilder value = new StringBuilder("TPV-");
        for (int index = 0; index < 8; index++) {
            value.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return value.toString();
    }

    private record TenantInitialAccess(String username, String initialPassword) {
    }
}
