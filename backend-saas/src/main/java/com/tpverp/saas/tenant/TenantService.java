package com.tpverp.saas.tenant;

import com.tpverp.saas.admin.CompanyOperationsResponse;
import com.tpverp.saas.admin.BillingInvoiceResponse;
import com.tpverp.saas.admin.CreateErpCustomerRequest;
import com.tpverp.saas.admin.CreateErpProductRequest;
import com.tpverp.saas.admin.CreateErpSupplierRequest;
import com.tpverp.saas.admin.CreateErpWarehouseRequest;
import com.tpverp.saas.admin.CreateSupportTicketCommentRequest;
import com.tpverp.saas.admin.CreateSupportTicketRequest;
import com.tpverp.saas.admin.ErpCustomerResponse;
import com.tpverp.saas.admin.ErpProductResponse;
import com.tpverp.saas.admin.ErpSupplierResponse;
import com.tpverp.saas.admin.ErpWarehouseResponse;
import com.tpverp.saas.admin.LicenseSummaryResponse;
import com.tpverp.saas.admin.SupportTicketCommentResponse;
import com.tpverp.saas.admin.SupportTicketResponse;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasCompanyRepository;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.SaasLicense;
import com.tpverp.saas.license.SaasLicenseRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TenantService {

    private final SaasCompanyRepository companies;
    private final SaasLicenseRepository licenses;
    private final SaasInstallationRepository installations;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TenantService(
            SaasCompanyRepository companies,
            SaasLicenseRepository licenses,
            SaasInstallationRepository installations,
            JdbcTemplate jdbc,
            Clock clock) {
        this.companies = companies;
        this.licenses = licenses;
        this.installations = installations;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TenantSessionResponse session() {
        TenantContext context = TenantContextHolder.current();
        SaasCompany company = company(context.companyId());
        return new TenantSessionResponse(context.username(), company.getId(), company.getName(), context.roleName());
    }

    @Transactional(readOnly = true)
    public TenantDashboardResponse dashboard() {
        TenantContext context = TenantContextHolder.current();
        SaasCompany company = company(context.companyId());
        CompanyOperationsResponse operations = companyOperations(company.getId());
        Long stores = jdbc.queryForObject(
                "select count(*) from saas_store where company_id = ?",
                Long.class,
                company.getId());
        Long openTickets = jdbc.queryForObject(
                "select count(*) from saas_support_ticket where company_id = ? and status <> 'CERRADO'",
                Long.class,
                company.getId());
        return new TenantDashboardResponse(
                company.getId(),
                company.getName(),
                licenses.findByCompany_Id(company.getId()).size(),
                stores == null ? 0 : stores,
                installations.findByCompany_Id(company.getId()).size(),
                openTickets == null ? 0 : openTickets,
                operations.billingStatus(),
                operations.renewalDate(),
                operations.monthlyPrice());
    }

    @Transactional(readOnly = true)
    public List<LicenseSummaryResponse> licenses() {
        UUID companyId = TenantContextHolder.current().companyId();
        return licenses.findByCompany_Id(companyId).stream()
                .sorted(Comparator.comparing(SaasLicense::getReference))
                .map(TenantService::licenseSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TenantStoreResponse> stores() {
        UUID companyId = TenantContextHolder.current().companyId();
        return jdbc.query("""
                select id, code, name, created_at
                from saas_store
                where company_id = ?
                order by code asc
                """, (rs, rowNum) -> new TenantStoreResponse(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getTimestamp("created_at").toInstant()), companyId);
    }

    @Transactional(readOnly = true)
    public List<SupportTicketResponse> tickets() {
        UUID companyId = TenantContextHolder.current().companyId();
        return jdbc.query(ticketSql("where t.company_id = ?"), (rs, rowNum) -> supportTicket(rs), companyId);
    }

    @Transactional(readOnly = true)
    public List<BillingInvoiceResponse> invoices() {
        UUID companyId = TenantContextHolder.current().companyId();
        try {
            return jdbc.query(invoiceSql("where i.company_id = ?"), (rs, rowNum) -> billingInvoice(rs), companyId);
        } catch (BadSqlGrammarException exception) {
            if (missingBillingTables(exception)) {
                return List.of();
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<ErpCustomerResponse> erpCustomers() {
        UUID companyId = TenantContextHolder.current().companyId();
        return jdbc.query("""
                select id, company_id, code, name, tax_id, email, phone, active, created_at
                from saas_erp_customer
                where company_id = ?
                order by code asc
                """, (rs, rowNum) -> erpCustomer(rs), companyId);
    }

    @Transactional
    public ErpCustomerResponse createErpCustomer(CreateErpCustomerRequest request) {
        UUID companyId = TenantContextHolder.current().companyId();
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
        return erpCustomer(id);
    }

    @Transactional(readOnly = true)
    public List<ErpProductResponse> erpProducts() {
        UUID companyId = TenantContextHolder.current().companyId();
        return jdbc.query("""
                select id, company_id, sku, name, category, price, tax_rate, min_stock, active, created_at
                from saas_erp_product
                where company_id = ?
                order by sku asc
                """, (rs, rowNum) -> erpProduct(rs), companyId);
    }

    @Transactional
    public ErpProductResponse createErpProduct(CreateErpProductRequest request) {
        UUID companyId = TenantContextHolder.current().companyId();
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
        return erpProduct(id);
    }

    @Transactional(readOnly = true)
    public List<ErpSupplierResponse> erpSuppliers() {
        UUID companyId = TenantContextHolder.current().companyId();
        return jdbc.query("""
                select id, company_id, code, name, tax_id, email, phone, active, created_at
                from saas_erp_supplier
                where company_id = ?
                order by code asc
                """, (rs, rowNum) -> erpSupplier(rs), companyId);
    }

    @Transactional
    public ErpSupplierResponse createErpSupplier(CreateErpSupplierRequest request) {
        UUID companyId = TenantContextHolder.current().companyId();
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
        return erpSupplier(id);
    }

    @Transactional(readOnly = true)
    public List<ErpWarehouseResponse> erpWarehouses() {
        UUID companyId = TenantContextHolder.current().companyId();
        return jdbc.query("""
                select id, company_id, code, name, address, active, created_at
                from saas_erp_warehouse
                where company_id = ?
                order by code asc
                """, (rs, rowNum) -> erpWarehouse(rs), companyId);
    }

    @Transactional
    public ErpWarehouseResponse createErpWarehouse(CreateErpWarehouseRequest request) {
        UUID companyId = TenantContextHolder.current().companyId();
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
        return erpWarehouse(id);
    }

    @Transactional
    public SupportTicketResponse createTicket(CreateSupportTicketRequest request) {
        TenantContext context = TenantContextHolder.current();
        Instant now = clock.instant();
        UUID ticketId = UUID.randomUUID();
        jdbc.update("""
                insert into saas_support_ticket(
                    id, company_id, title, description, status, priority, created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                ticketId,
                context.companyId(),
                request.title().trim(),
                blankToNull(request.description()),
                "ABIERTO",
                defaultText(request.priority(), "NORMAL"),
                "tenant:" + context.username().toLowerCase(),
                now,
                now);
        return ticket(ticketId, context.companyId());
    }

    @Transactional(readOnly = true)
    public List<SupportTicketCommentResponse> ticketComments(UUID ticketId) {
        ticket(ticketId, TenantContextHolder.current().companyId());
        return jdbc.query("""
                select id, ticket_id, author, message, created_at
                from saas_support_ticket_comment
                where ticket_id = ?
                order by created_at asc
                """, (rs, rowNum) -> supportTicketComment(rs), ticketId);
    }

    @Transactional
    public SupportTicketCommentResponse createTicketComment(UUID ticketId, CreateSupportTicketCommentRequest request) {
        TenantContext context = TenantContextHolder.current();
        ticket(ticketId, context.companyId());
        Instant now = clock.instant();
        UUID commentId = UUID.randomUUID();
        jdbc.update("""
                insert into saas_support_ticket_comment(id, ticket_id, author, message, created_at)
                values (?, ?, ?, ?, ?)
                """, commentId, ticketId, "tenant:" + context.username().toLowerCase(), request.message().trim(), now);
        jdbc.update("update saas_support_ticket set updated_at = ? where id = ?", now, ticketId);
        return supportTicketComment(commentId);
    }

    private SaasCompany company(UUID companyId) {
        return companies.findById(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa cliente no existe"));
    }

    private CompanyOperationsResponse companyOperations(UUID companyId) {
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
                .orElse(new CompanyOperationsResponse(companyId, "STANDARD", "PENDIENTE", null, null, "NORMAL", null, null, null));
    }

    private SupportTicketResponse ticket(UUID ticketId, UUID companyId) {
        return jdbc.query(ticketSql("where t.id = ? and t.company_id = ?"), (rs, rowNum) -> supportTicket(rs), ticketId, companyId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket no existe para este cliente"));
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

    private static String ticketSql(String where) {
        return """
                select t.id, t.company_id, c.name as company_name, t.title, t.description,
                       t.status, t.priority, t.created_by, t.created_at, t.updated_at
                from saas_support_ticket t
                join saas_company c on c.id = t.company_id
                """ + where + " order by t.updated_at desc";
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

    private static SupportTicketCommentResponse supportTicketComment(ResultSet rs) throws SQLException {
        return new SupportTicketCommentResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("ticket_id", UUID.class),
                rs.getString("author"),
                rs.getString("message"),
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

    private static boolean missingBillingTables(BadSqlGrammarException exception) {
        String message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        boolean billingTable = message.contains("saas_billing_invoice") || message.contains("saas_billing_payment");
        boolean missingRelation = message.contains("does not exist")
                || message.contains("not found")
                || message.contains("no existe");
        return billingTable && missingRelation;
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

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String money(String value) {
        if (value == null || value.isBlank()) {
            return "0.00";
        }
        try {
            return new java.math.BigDecimal(value.trim().replace(",", "."))
                    .setScale(2, java.math.RoundingMode.HALF_UP)
                    .toPlainString();
        } catch (NumberFormatException exception) {
            return "0.00";
        }
    }
}
