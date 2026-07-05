package com.tpverp.saas.tenant;

import com.tpverp.saas.admin.CreateSupportTicketCommentRequest;
import com.tpverp.saas.admin.CreateSupportTicketRequest;
import com.tpverp.saas.admin.BillingInvoiceResponse;
import com.tpverp.saas.admin.CreateErpCustomerRequest;
import com.tpverp.saas.admin.CreateErpProductRequest;
import com.tpverp.saas.admin.CreateErpSupplierRequest;
import com.tpverp.saas.admin.CreateErpWarehouseRequest;
import com.tpverp.saas.admin.ErpCustomerResponse;
import com.tpverp.saas.admin.ErpProductResponse;
import com.tpverp.saas.admin.ErpSupplierResponse;
import com.tpverp.saas.admin.ErpWarehouseResponse;
import com.tpverp.saas.admin.LicenseSummaryResponse;
import com.tpverp.saas.admin.SupportTicketCommentResponse;
import com.tpverp.saas.admin.SupportTicketResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant")
public class TenantController {

    private final TenantService service;

    public TenantController(TenantService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public TenantSessionResponse me() {
        return service.session();
    }

    @GetMapping("/dashboard")
    public TenantDashboardResponse dashboard() {
        return service.dashboard();
    }

    @GetMapping("/licenses")
    public List<LicenseSummaryResponse> licenses() {
        return service.licenses();
    }

    @GetMapping("/stores")
    public List<TenantStoreResponse> stores() {
        return service.stores();
    }

    @GetMapping("/tickets")
    public List<SupportTicketResponse> tickets() {
        return service.tickets();
    }

    @GetMapping("/invoices")
    public List<BillingInvoiceResponse> invoices() {
        return service.invoices();
    }

    @GetMapping("/erp/customers")
    public List<ErpCustomerResponse> erpCustomers() {
        return service.erpCustomers();
    }

    @PostMapping("/erp/customers")
    public ErpCustomerResponse createErpCustomer(@Valid @RequestBody CreateErpCustomerRequest request) {
        return service.createErpCustomer(request);
    }

    @GetMapping("/erp/products")
    public List<ErpProductResponse> erpProducts() {
        return service.erpProducts();
    }

    @PostMapping("/erp/products")
    public ErpProductResponse createErpProduct(@Valid @RequestBody CreateErpProductRequest request) {
        return service.createErpProduct(request);
    }

    @GetMapping("/erp/suppliers")
    public List<ErpSupplierResponse> erpSuppliers() {
        return service.erpSuppliers();
    }

    @PostMapping("/erp/suppliers")
    public ErpSupplierResponse createErpSupplier(@Valid @RequestBody CreateErpSupplierRequest request) {
        return service.createErpSupplier(request);
    }

    @GetMapping("/erp/warehouses")
    public List<ErpWarehouseResponse> erpWarehouses() {
        return service.erpWarehouses();
    }

    @PostMapping("/erp/warehouses")
    public ErpWarehouseResponse createErpWarehouse(@Valid @RequestBody CreateErpWarehouseRequest request) {
        return service.createErpWarehouse(request);
    }

    @PostMapping("/tickets")
    public SupportTicketResponse createTicket(@Valid @RequestBody CreateSupportTicketRequest request) {
        return service.createTicket(request);
    }

    @GetMapping("/tickets/{ticketId}/comments")
    public List<SupportTicketCommentResponse> ticketComments(@PathVariable UUID ticketId) {
        return service.ticketComments(ticketId);
    }

    @PostMapping("/tickets/{ticketId}/comments")
    public SupportTicketCommentResponse createTicketComment(
            @PathVariable UUID ticketId,
            @Valid @RequestBody CreateSupportTicketCommentRequest request) {
        return service.createTicketComment(ticketId, request);
    }
}
