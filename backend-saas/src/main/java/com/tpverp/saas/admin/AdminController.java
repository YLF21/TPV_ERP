package com.tpverp.saas.admin;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService service;
    private final AdminAuditService audit;
    private final VerifactuActivationPolicyAdminService verifactuPolicies;

    public AdminController(
            AdminService service,
            AdminAuditService audit,
            VerifactuActivationPolicyAdminService verifactuPolicies) {
        this.service = service;
        this.audit = audit;
        this.verifactuPolicies = verifactuPolicies;
    }

    @PostMapping("/companies")
    public CreateCompanyResponse createCompany(@Valid @RequestBody CreateCompanyRequest request) {
        return service.createCompany(request);
    }

    @PutMapping("/companies/{companyId}")
    public LicenseSummaryResponse editCompany(
            @PathVariable UUID companyId,
            @Valid @RequestBody EditCompanyDataRequest request) {
        return service.editCompany(companyId, request);
    }

    @GetMapping("/companies/{companyId}/operations")
    public CompanyOperationsResponse companyOperations(@PathVariable UUID companyId) {
        return service.companyOperations(companyId);
    }

    @PutMapping("/companies/{companyId}/operations")
    public CompanyOperationsResponse updateCompanyOperations(
            @PathVariable UUID companyId,
            @Valid @RequestBody UpdateCompanyOperationsRequest request) {
        return service.updateCompanyOperations(companyId, request);
    }

    @GetMapping("/companies/{companyId}/tenant-users")
    public List<TenantUserResponse> tenantUsers(@PathVariable UUID companyId) {
        return service.tenantUsers(companyId);
    }

    @PostMapping("/companies/{companyId}/tenant-users")
    public TenantUserResponse createTenantUser(
            @PathVariable UUID companyId,
            @Valid @RequestBody CreateTenantUserRequest request) {
        return service.createTenantUser(companyId, request);
    }

    @PutMapping("/tenant-users/{username}/password")
    public void changeTenantPassword(
            @PathVariable String username,
            @Valid @RequestBody ChangeAdminPasswordRequest request) {
        service.changeTenantPassword(username, request);
    }

    @DeleteMapping("/tenant-users/{username}")
    public void deactivateTenantUser(@PathVariable String username) {
        service.deactivateTenantUser(username);
    }

    @GetMapping("/companies/{companyId}/invoices")
    public List<BillingInvoiceResponse> billingInvoices(@PathVariable UUID companyId) {
        return service.billingInvoices(companyId);
    }

    @PostMapping("/companies/{companyId}/invoices")
    public BillingInvoiceResponse createBillingInvoice(
            @PathVariable UUID companyId,
            @Valid @RequestBody CreateBillingInvoiceRequest request) {
        return service.createBillingInvoice(companyId, request);
    }

    @GetMapping("/companies/{companyId}/erp/customers")
    public List<ErpCustomerResponse> erpCustomers(@PathVariable UUID companyId) {
        return service.erpCustomers(companyId);
    }

    @PostMapping("/companies/{companyId}/erp/customers")
    public ErpCustomerResponse createErpCustomer(
            @PathVariable UUID companyId,
            @Valid @RequestBody CreateErpCustomerRequest request) {
        return service.createErpCustomer(companyId, request);
    }

    @DeleteMapping("/companies/{companyId}/erp/customers/{id}")
    public ErpCustomerResponse deactivateErpCustomer(@PathVariable UUID companyId, @PathVariable UUID id) {
        return service.deactivateErpCustomer(companyId, id);
    }

    @GetMapping("/companies/{companyId}/erp/products")
    public List<ErpProductResponse> erpProducts(@PathVariable UUID companyId) {
        return service.erpProducts(companyId);
    }

    @PostMapping("/companies/{companyId}/erp/products")
    public ErpProductResponse createErpProduct(
            @PathVariable UUID companyId,
            @Valid @RequestBody CreateErpProductRequest request) {
        return service.createErpProduct(companyId, request);
    }

    @DeleteMapping("/companies/{companyId}/erp/products/{id}")
    public ErpProductResponse deactivateErpProduct(@PathVariable UUID companyId, @PathVariable UUID id) {
        return service.deactivateErpProduct(companyId, id);
    }

    @GetMapping("/companies/{companyId}/erp/suppliers")
    public List<ErpSupplierResponse> erpSuppliers(@PathVariable UUID companyId) {
        return service.erpSuppliers(companyId);
    }

    @PostMapping("/companies/{companyId}/erp/suppliers")
    public ErpSupplierResponse createErpSupplier(
            @PathVariable UUID companyId,
            @Valid @RequestBody CreateErpSupplierRequest request) {
        return service.createErpSupplier(companyId, request);
    }

    @DeleteMapping("/companies/{companyId}/erp/suppliers/{id}")
    public ErpSupplierResponse deactivateErpSupplier(@PathVariable UUID companyId, @PathVariable UUID id) {
        return service.deactivateErpSupplier(companyId, id);
    }

    @GetMapping("/companies/{companyId}/erp/warehouses")
    public List<ErpWarehouseResponse> erpWarehouses(@PathVariable UUID companyId) {
        return service.erpWarehouses(companyId);
    }

    @PostMapping("/companies/{companyId}/erp/warehouses")
    public ErpWarehouseResponse createErpWarehouse(
            @PathVariable UUID companyId,
            @Valid @RequestBody CreateErpWarehouseRequest request) {
        return service.createErpWarehouse(companyId, request);
    }

    @DeleteMapping("/companies/{companyId}/erp/warehouses/{id}")
    public ErpWarehouseResponse deactivateErpWarehouse(@PathVariable UUID companyId, @PathVariable UUID id) {
        return service.deactivateErpWarehouse(companyId, id);
    }

    @PostMapping("/invoices/{invoiceId}/payments")
    public BillingPaymentResponse createBillingPayment(
            @PathVariable UUID invoiceId,
            @Valid @RequestBody CreateBillingPaymentRequest request) {
        return service.createBillingPayment(invoiceId, request);
    }

    @PutMapping("/users/{username}/password")
    public void changePassword(
            @PathVariable String username,
            @Valid @RequestBody ChangeAdminPasswordRequest request) {
        service.changePassword(username, request);
    }

    @PostMapping("/users")
    public AdminUserResponse createUser(@Valid @RequestBody CreateAdminUserRequest request) {
        return service.createUser(request);
    }

    @GetMapping("/users")
    public List<AdminUserResponse> users() {
        return service.users();
    }

    @DeleteMapping("/users/{username}")
    public void deactivateUser(@PathVariable String username) {
        service.deactivateUser(username);
    }

    @GetMapping("/licenses")
    public List<LicenseSummaryResponse> licenses() {
        return service.licenses();
    }

    @GetMapping("/verifactu-activation-policies")
    public List<VerifactuActivationPolicyResponse> verifactuActivationPolicies() {
        return verifactuPolicies.list();
    }

    @PutMapping("/verifactu-activation-policies/{taxpayerType}")
    public VerifactuActivationPolicyResponse updateVerifactuActivationPolicy(
            @PathVariable com.tpverp.saas.license.TaxpayerType taxpayerType,
            @Valid @RequestBody UpdateVerifactuActivationPolicyRequest request) {
        return verifactuPolicies.update(taxpayerType, request);
    }

    @GetMapping("/installations")
    public List<InstallationSummaryResponse> installations() {
        return service.installations();
    }

    @GetMapping("/audit")
    public List<AdminAuditLogResponse> audit() {
        return audit.recent();
    }

    @GetMapping("/me")
    public AdminSessionResponse me() {
        return service.session();
    }

    @GetMapping("/status")
    public SaasStatusResponse status() {
        return service.status();
    }

    @GetMapping("/notifications")
    public List<AdminNotificationResponse> notifications() {
        return service.notifications();
    }

    @PutMapping("/notifications/{notificationId}/read")
    public void markNotificationRead(@PathVariable String notificationId) {
        service.markNotificationRead(notificationId);
    }

    @GetMapping("/technical-status")
    public TechnicalStatusResponse technicalStatus() {
        return service.technicalStatus();
    }

    @GetMapping("/billing-summary")
    public BillingSummaryResponse billingSummary() {
        return service.billingSummary();
    }

    @GetMapping("/health")
    public List<CustomerHealthResponse> customerHealth() {
        return service.customerHealth();
    }

    @GetMapping("/companies/{companyId}/health")
    public CustomerHealthResponse customerHealth(@PathVariable UUID companyId) {
        return service.customerHealth(companyId);
    }

    @GetMapping("/companies/{companyId}/tickets")
    public List<SupportTicketResponse> supportTickets(@PathVariable UUID companyId) {
        return service.supportTickets(companyId);
    }

    @PostMapping("/companies/{companyId}/tickets")
    public SupportTicketResponse createSupportTicket(
            @PathVariable UUID companyId,
            @Valid @RequestBody CreateSupportTicketRequest request) {
        return service.createSupportTicket(companyId, request);
    }

    @PutMapping("/tickets/{ticketId}")
    public SupportTicketResponse updateSupportTicket(
            @PathVariable UUID ticketId,
            @Valid @RequestBody UpdateSupportTicketRequest request) {
        return service.updateSupportTicket(ticketId, request);
    }

    @GetMapping("/tickets/{ticketId}/comments")
    public List<SupportTicketCommentResponse> supportTicketComments(@PathVariable UUID ticketId) {
        return service.supportTicketComments(ticketId);
    }

    @PostMapping("/tickets/{ticketId}/comments")
    public SupportTicketCommentResponse createSupportTicketComment(
            @PathVariable UUID ticketId,
            @Valid @RequestBody CreateSupportTicketCommentRequest request) {
        return service.createSupportTicketComment(ticketId, request);
    }

    @PostMapping("/licenses/{reference}/renew")
    public AdminLicenseResponse renew(
            @PathVariable String reference,
            @Valid @RequestBody RenewLicenseRequest request) {
        return service.renew(reference, request);
    }

    @PostMapping("/licenses/{reference}/pairing-codes")
    public PairingCodeResponse regeneratePairingCode(@PathVariable String reference) {
        return service.regeneratePairingCode(reference);
    }

    @PostMapping("/licenses/{reference}/block")
    public AdminLicenseResponse block(@PathVariable String reference) {
        return service.block(reference);
    }

    @PostMapping("/licenses/{reference}/unblock")
    public AdminLicenseResponse unblock(@PathVariable String reference) {
        return service.unblock(reference);
    }
}
