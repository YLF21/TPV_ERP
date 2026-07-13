package com.tpverp.backend.security.application;

import com.tpverp.backend.security.domain.Permission;
import com.tpverp.backend.security.domain.PermissionRepository;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

public class CorePermissionBootstrap {

    public static final String USERS_MANAGE = "USERS_MANAGE";
    public static final String GESTION_USUARIO = "GESTION_USUARIO";
    public static final String ROLES_MANAGE = "ROLES_MANAGE";
    public static final String TERMINALS_MANAGE = "TERMINALS_MANAGE";
    public static final String CONFIGURACION_TERMINAL = "CONFIGURACION_TERMINAL";
    public static final String LICENSES_MANAGE = "LICENSES_MANAGE";
    public static final String BACKUPS_MANAGE = "BACKUPS_MANAGE";
    public static final String AUDIT_READ = "AUDIT_READ";
    public static final String PRODUCTS_READ = "PRODUCTS_READ";
    public static final String PRODUCTS_WRITE = "PRODUCTS_WRITE";
    public static final String PRODUCTS_DELETE = "PRODUCTS_DELETE";
    public static final String GESTION_PRODUCTO = "GESTION_PRODUCTO";
    public static final String TAXES_MANAGE = "TAXES_MANAGE";
    public static final String WAREHOUSES_MANAGE = "WAREHOUSES_MANAGE";
    public static final String STOCK_READ = "STOCK_READ";
    public static final String STOCK_ADJUST = "STOCK_ADJUST";
    public static final String STOCK_TRANSFER = "STOCK_TRANSFER";
    public static final String WAREHOUSE_OUTPUTS_READ = "WAREHOUSE_OUTPUTS_READ";
    public static final String WAREHOUSE_OUTPUTS_EDIT = "WAREHOUSE_OUTPUTS_EDIT";
    public static final String WAREHOUSE_OUTPUTS_DELETE = "WAREHOUSE_OUTPUTS_DELETE";
    public static final String WAREHOUSE_OUTPUTS_CONFIRM = "WAREHOUSE_OUTPUTS_CONFIRM";
    public static final String CUSTOMERS_READ = "CUSTOMERS_READ";
    public static final String CUSTOMERS_WRITE = "CUSTOMERS_WRITE";
    public static final String CUSTOMERS_DELETE = "CUSTOMERS_DELETE";
    public static final String SUPPLIERS_READ = "SUPPLIERS_READ";
    public static final String SUPPLIERS_WRITE = "SUPPLIERS_WRITE";
    public static final String SUPPLIERS_DELETE = "SUPPLIERS_DELETE";
    public static final String VENTA = "VENTA";
    public static final String GESTION_VENTAS = "GESTION_VENTAS";
    public static final String CAMBIAR_PRECIO = "CAMBIAR_PRECIO";
    public static final String APLICAR_DESCUENTO = "APLICAR_DESCUENTO";
    public static final String GESTION_CUENTAS = "GESTION_CUENTAS";
    public static final String CASH_READ = "CASH_READ";
    public static final String CASH_OPERATE = "CASH_OPERATE";
    public static final String CASH_CONFIGURE = "CASH_CONFIGURE";
    public static final String DELIVERY_NOTES_READ = "DELIVERY_NOTES_READ";
    public static final String DELIVERY_NOTES_WRITE = "DELIVERY_NOTES_WRITE";
    public static final String DELIVERY_NOTES_CONFIRM = "DELIVERY_NOTES_CONFIRM";
    public static final String TICKETS_READ = "TICKETS_READ";
    public static final String TICKETS_CREATE = "TICKETS_CREATE";
    public static final String TICKETS_CANCEL = "TICKETS_CANCEL";
    public static final String INVOICES_READ = "INVOICES_READ";
    public static final String INVOICES_WRITE = "INVOICES_WRITE";
    public static final String INVOICES_CONFIRM = "INVOICES_CONFIRM";
    public static final String INVOICES_PAY = "INVOICES_PAY";
    public static final String PAYMENT_TERMINAL_VOID = "PAYMENT_TERMINAL_VOID";
    public static final String PAYMENT_TERMINAL_REFUND = "PAYMENT_TERMINAL_REFUND";
    public static final String PAYMENT_TERMINAL_SECRETS = "PAYMENT_TERMINAL_SECRETS";
    private final PermissionRepository permisoRepository;

    public CorePermissionBootstrap(PermissionRepository permisoRepository) {
        this.permisoRepository = permisoRepository;
    }

    @Transactional
    public void initialize() {
        List.of(
                permission(USERS_MANAGE, "security.permissions.users", "SECURITY"),
                permission(GESTION_USUARIO, "security.permissions.userManagement", "SECURITY"),
                permission(ROLES_MANAGE, "security.permissions.roles", "SECURITY"),
                permission(TERMINALS_MANAGE, "security.permissions.terminals", "SECURITY"),
                permission(CONFIGURACION_TERMINAL, "terminal.permissions.configure", "TERMINAL"),
                permission(LICENSES_MANAGE, "security.permissions.licenses", "SYSTEM"),
                permission(BACKUPS_MANAGE, "security.permissions.backups", "SYSTEM"),
                permission(AUDIT_READ, "security.permissions.audit", "SYSTEM"),
                permission(PRODUCTS_READ, "catalog.permissions.read", "CATALOG"),
                permission(PRODUCTS_WRITE, "catalog.permissions.write", "CATALOG"),
                permission(PRODUCTS_DELETE, "catalog.permissions.delete", "CATALOG"),
                permission(GESTION_PRODUCTO, "catalog.permissions.productManagement", "CATALOG"),
                permission(TAXES_MANAGE, "catalog.permissions.taxes", "CATALOG"),
                permission(WAREHOUSES_MANAGE, "inventory.permissions.warehouses", "INVENTORY"),
                permission(STOCK_READ, "inventory.permissions.read", "INVENTORY"),
                permission(STOCK_ADJUST, "inventory.permissions.adjust", "INVENTORY"),
                permission(STOCK_TRANSFER, "inventory.permissions.transfer", "INVENTORY"),
                permission(WAREHOUSE_OUTPUTS_READ, "inventory.permissions.outputs.read", "INVENTORY"),
                permission(WAREHOUSE_OUTPUTS_EDIT, "inventory.permissions.outputs.edit", "INVENTORY"),
                permission(WAREHOUSE_OUTPUTS_DELETE, "inventory.permissions.outputs.delete", "INVENTORY"),
                permission(WAREHOUSE_OUTPUTS_CONFIRM, "inventory.permissions.outputs.confirm", "INVENTORY"),
                permission(CUSTOMERS_READ, "party.permissions.customers.read", "PARTY"),
                permission(CUSTOMERS_WRITE, "party.permissions.customers.write", "PARTY"),
                permission(CUSTOMERS_DELETE, "party.permissions.customers.delete", "PARTY"),
                permission(SUPPLIERS_READ, "party.permissions.suppliers.read", "PARTY"),
                permission(SUPPLIERS_WRITE, "party.permissions.suppliers.write", "PARTY"),
                permission(SUPPLIERS_DELETE, "party.permissions.suppliers.delete", "PARTY"),
                permission(VENTA, "document.permissions.sales.operate", "DOCUMENTS"),
                permission(GESTION_VENTAS, "document.permissions.sales.manage", "DOCUMENTS"),
                permission(CAMBIAR_PRECIO, "document.permissions.sales.changePrice", "DOCUMENTS"),
                permission(APLICAR_DESCUENTO, "document.permissions.sales.applyDiscount", "DOCUMENTS"),
                permission(GESTION_CUENTAS, "cash.permissions.accounting", "CASH"),
                permission(CASH_READ, "cash.permissions.read", "CASH"),
                permission(CASH_OPERATE, "cash.permissions.operate", "CASH"),
                permission(CASH_CONFIGURE, "cash.permissions.configure", "CASH"),
                permission(DELIVERY_NOTES_READ, "document.permissions.deliveryNotes.read", "DOCUMENTS"),
                permission(DELIVERY_NOTES_WRITE, "document.permissions.deliveryNotes.write", "DOCUMENTS"),
                permission(DELIVERY_NOTES_CONFIRM, "document.permissions.deliveryNotes.confirm", "DOCUMENTS"),
                permission(TICKETS_READ, "document.permissions.tickets.read", "DOCUMENTS"),
                permission(TICKETS_CREATE, "document.permissions.tickets.create", "DOCUMENTS"),
                permission(TICKETS_CANCEL, "document.permissions.tickets.cancel", "DOCUMENTS"),
                permission(INVOICES_READ, "document.permissions.invoices.read", "DOCUMENTS"),
                permission(INVOICES_WRITE, "document.permissions.invoices.write", "DOCUMENTS"),
                permission(INVOICES_CONFIRM, "document.permissions.invoices.confirm", "DOCUMENTS"),
                permission(INVOICES_PAY, "document.permissions.invoices.pay", "DOCUMENTS"),
                permission(PAYMENT_TERMINAL_VOID, "terminal.permissions.payment.void", "TERMINAL"),
                permission(PAYMENT_TERMINAL_REFUND, "terminal.permissions.payment.refund", "TERMINAL"),
                permission(PAYMENT_TERMINAL_SECRETS, "terminal.permissions.payment.secrets", "TERMINAL"))
                .forEach(permission -> permisoRepository.findByCodigo(permission.getCodigo())
                        .orElseGet(() -> permisoRepository.save(permission)));
    }

    private Permission permission(String code, String translationKey, String group) {
        return new Permission(code, translationKey, group);
    }
}
