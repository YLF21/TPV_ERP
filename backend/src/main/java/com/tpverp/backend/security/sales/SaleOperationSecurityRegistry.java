package com.tpverp.backend.security.sales;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.ABRIR_CAJON;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.APLICAR_DESCUENTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.CAMBIAR_PRECIO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.CUSTOMER_CREDIT_OVERRIDE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.CUSTOMER_RECEIVABLES_CREATE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_CUENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PAYMENT_TERMINAL_REFUND;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PAYMENT_TERMINAL_VOID;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SaleOperationSecurityRegistry {

    private static final List<SaleOperationDefinition> DEFINITIONS = List.of(
            definition(
                    SaleOperationCode.OPEN_CASH_DRAWER,
                    SaleOperationCategory.CASH,
                    List.of("F3"),
                    List.of(ABRIR_CAJON),
                    true,
                    false),
            definition(
                    SaleOperationCode.EDIT_CATALOG_PRODUCT,
                    SaleOperationCategory.PRODUCT,
                    List.of("F7"),
                    List.of(GESTION_PRODUCTO),
                    true,
                    false),
            definition(
                    SaleOperationCode.GENERATE_PRODUCT_EAN,
                    SaleOperationCategory.PRODUCT,
                    List.of("Ctrl+F2"),
                    List.of(GESTION_PRODUCTO),
                    true,
                    false),
            definition(
                    SaleOperationCode.CLOSE_CASH_SESSION,
                    SaleOperationCategory.CASH,
                    List.of("F8"),
                    List.of(GESTION_VENTAS, GESTION_CUENTAS),
                    false,
                    true),
            definition(
                    SaleOperationCode.CASH_MOVEMENT,
                    SaleOperationCategory.CASH,
                    List.of("F9"),
                    List.of(GESTION_VENTAS, GESTION_CUENTAS),
                    true,
                    true),
            definition(
                    SaleOperationCode.RETURN_TICKET,
                    SaleOperationCategory.TICKET,
                    List.of("F10"),
                    List.of(GESTION_VENTAS),
                    false,
                    false),
            definition(
                    SaleOperationCode.CANCEL_TICKET,
                    SaleOperationCategory.TICKET,
                    List.of("F11", "Ctrl+F11"),
                    List.of(GESTION_VENTAS, GESTION_CUENTAS),
                    true,
                    true),
            definition(
                    SaleOperationCode.CONVERT_TICKET_TO_INVOICE,
                    SaleOperationCategory.TICKET,
                    List.of("F12"),
                    List.of(GESTION_VENTAS),
                    false,
                    false),
            definition(
                    SaleOperationCode.MANUAL_RETURN_WITHOUT_TICKET,
                    SaleOperationCategory.TICKET,
                    List.of("-1+Pausa"),
                    List.of(GESTION_VENTAS),
                    true,
                    false),
            definition(
                    SaleOperationCode.DELETE_PARKED_SALE,
                    SaleOperationCategory.TICKET,
                    List.of(),
                    List.of(GESTION_VENTAS),
                    false,
                    false),
            definition(
                    SaleOperationCode.TEMPORARY_NAME,
                    SaleOperationCategory.PRODUCT,
                    List.of("Inicio"),
                    List.of(GESTION_VENTAS),
                    false,
                    false),
            definition(
                    SaleOperationCode.TEMPORARY_PRICE_CHANGE,
                    SaleOperationCategory.PRODUCT,
                    List.of("Ctrl+RePag"),
                    List.of(CAMBIAR_PRECIO, GESTION_VENTAS),
                    true,
                    true),
            definition(
                    SaleOperationCode.OPEN_PRICE_PRODUCT,
                    SaleOperationCategory.PRODUCT,
                    List.of(),
                    List.of(CAMBIAR_PRECIO, GESTION_VENTAS),
                    false,
                    false),
            definition(
                    SaleOperationCode.APPLY_SALE_DISCOUNT,
                    SaleOperationCategory.DISCOUNT,
                    List.of("/", "Ctrl+/", "RePag"),
                    List.of(APLICAR_DESCUENTO),
                    true,
                    false),
            definition(
                    SaleOperationCode.APPLY_CHECKOUT_DISCOUNT,
                    SaleOperationCategory.DISCOUNT,
                    List.of("F11"),
                    List.of(APLICAR_DESCUENTO),
                    true,
                    false),
            definition(
                    SaleOperationCode.CREATE_PENDING_RECEIVABLE,
                    SaleOperationCategory.CREDIT,
                    List.of("F8"),
                    List.of(CUSTOMER_RECEIVABLES_CREATE),
                    true,
                    false),
            definition(
                    SaleOperationCode.CREDIT_OVERRIDE,
                    SaleOperationCategory.CREDIT,
                    List.of(),
                    List.of(CUSTOMER_CREDIT_OVERRIDE),
                    true,
                    false),
            definition(
                    SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT,
                    SaleOperationCategory.PAYMENT,
                    List.of("+"),
                    List.of(GESTION_VENTAS, GESTION_CUENTAS),
                    true,
                    false),
            definition(
                    SaleOperationCode.CONFIRM_TRANSFER_PAYMENT,
                    SaleOperationCategory.PAYMENT,
                    List.of("F7"),
                    List.of(GESTION_VENTAS, GESTION_CUENTAS),
                    true,
                    false),
            definition(
                    SaleOperationCode.PAYMENT_TERMINAL_VOID,
                    SaleOperationCategory.PAYMENT_TERMINAL,
                    List.of(),
                    List.of(PAYMENT_TERMINAL_VOID),
                    true,
                    true),
            definition(
                    SaleOperationCode.PAYMENT_TERMINAL_REFUND,
                    SaleOperationCategory.PAYMENT_TERMINAL,
                    List.of(),
                    List.of(PAYMENT_TERMINAL_REFUND),
                    true,
                    true),
            definition(
                    SaleOperationCode.PAYMENT_COMPENSATION_ACK,
                    SaleOperationCategory.PAYMENT_TERMINAL,
                    List.of(),
                    List.of(PAYMENT_TERMINAL_REFUND),
                    true,
                    true));

    private final Map<SaleOperationCode, SaleOperationDefinition> byCode;

    public SaleOperationSecurityRegistry() {
        var values = new EnumMap<SaleOperationCode, SaleOperationDefinition>(
                SaleOperationCode.class);
        for (var definition : DEFINITIONS) {
            if (values.put(definition.code(), definition) != null) {
                throw new IllegalStateException(
                        "Duplicate sale operation definition: " + definition.code());
            }
        }
        if (values.size() != SaleOperationCode.values().length) {
            throw new IllegalStateException("The sale operation registry is incomplete");
        }
        this.byCode = Map.copyOf(values);
    }

    public List<SaleOperationDefinition> definitions() {
        return DEFINITIONS;
    }

    public SaleOperationDefinition require(SaleOperationCode code) {
        var definition = byCode.get(code);
        if (definition == null) {
            throw new IllegalArgumentException("sales_operation_security_unknown_code");
        }
        return definition;
    }

    public SaleOperationDefinition require(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("sales_operation_security_code_required");
        }
        try {
            return require(SaleOperationCode.valueOf(
                    code.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "sales_operation_security_unknown_code", exception);
        }
    }

    private static SaleOperationDefinition definition(
            SaleOperationCode code,
            SaleOperationCategory category,
            List<String> shortcuts,
            List<String> permissions,
            boolean requirePermission,
            boolean requirePassword) {
        return new SaleOperationDefinition(
                code,
                category,
                shortcuts,
                permissions,
                requirePermission,
                requirePassword);
    }
}
