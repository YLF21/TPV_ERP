package com.tpverp.backend.dev;

import com.tpverp.backend.document.CommercialDocumentType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class DevSampleDataSeeder {

    private static final UUID COMPANY = id("company");
    private static final UUID STORE = id("store");
    private static final UUID WAREHOUSE = STORE;
    private static final UUID FAMILY = STORE;
    private static final UUID TAX = id("tax-iva-21");
    private static final UUID ROLE = id("role-ventas");
    private static final UUID USER = id("user-vendedor");
    private static final UUID TERMINAL = id("terminal-servidor");
    private static final UUID CUSTOMER = id("customer");
    private static final UUID SUPPLIER = id("supplier");
    private static final UUID PRODUCT_A = id("product-cafe");
    private static final UUID PRODUCT_B = id("product-agua");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 5);
    private static final Instant NOW = Instant.parse("2026-07-05T10:00:00Z");
    private static final int BULK_DOCUMENTS = 1_000;
    private static final List<CommercialDocumentType> TYPES = List.of(CommercialDocumentType.values());

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public DevSampleDataSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public static List<CommercialDocumentType> documentTypes() {
        return TYPES;
    }

    @Transactional
    // Loads an idempotent demo dataset only for the dev profile.
    public void seed() {
        UUID installation = installation();
        seedOrganization();
        seedSecurity();
        seedLicense(installation);
        seedCatalog();
        seedParties();
        seedDocuments();
        seedWarehouseOutput();
    }

    private UUID installation() {
        jdbc.update("""
                insert into instalacion (id, referencia, public_key, creada_en, demo_hasta)
                values (?, 'DEV-INSTALACION', 'DEV-PUBLIC-KEY', ?, ?)
                on conflict (singleton_key) do nothing
                """, id("installation"), ts(NOW), ts(NOW.plusSeconds(30L * 24L * 60L * 60L)));
        return jdbc.queryForObject("select id from instalacion limit 1", UUID.class);
    }

    private void seedOrganization() {
        jdbc.update("""
                insert into empresa (id, tax_id, razon_social, domicilio_fiscal)
                values (?, 'B00000000', 'EMPRESA PRUEBAS TPV ERP',
                    '{"linea1":"Calle Pruebas 1","ciudad":"Las Palmas","codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"}'::jsonb)
                on conflict (id) do nothing
                """, COMPANY);
        jdbc.update("""
                insert into tienda
                    (id, empresa_id, nombre, direccion, address_normalized_hash, telefono, email,
                     timezone, moneda, locale, codigo_tienda)
                values (?, ?, 'TIENDA PRUEBAS 001',
                    '{"linea1":"Calle Pruebas 1","ciudad":"Las Palmas","codigoPostal":"35001","provincia":"Las Palmas","pais":"ES"}'::jsonb,
                    'DEV_STORE_001', '928000000', 'tienda001@example.test',
                    'Atlantic/Canary', 'EUR', 'es-ES', '001')
                on conflict (id) do nothing
                """, STORE, COMPANY);
        jdbc.update("""
                insert into almacen (id, tienda_id, nombre, predeterminado, activo)
                values (?, ?, 'GENERAL', true, true)
                on conflict do nothing
                """, WAREHOUSE, STORE);
        jdbc.update("""
                insert into familia (id, tienda_id, family_id, nombre, predeterminada)
                values (?, ?, 'GENERAL', 'GENERAL', true)
                on conflict do nothing
                """, FAMILY, STORE);
        jdbc.update("""
                insert into impuesto_tienda (id, tienda_id, porcentaje, activo, predeterminado)
                values (?, ?, 21.00, true, true)
                on conflict do nothing
                """, TAX, STORE);
    }

    private void seedSecurity() {
        jdbc.update("""
                insert into rol (id, tienda_id, nombre, protegido)
                values (?, ?, 'VENTAS', false)
                on conflict do nothing
                """, ROLE, STORE);
        grant(ROLE, "VENTA");
        grant(ROLE, "GESTION_VENTAS");
        grant(ROLE, "PRODUCTS_READ");
        grant(ROLE, "STOCK_READ");
        grant(ROLE, "CUSTOMERS_READ");
        grant(ROLE, "CUSTOMERS_WRITE");
        grant(ROLE, "SUPPLIERS_READ");
        grant(ROLE, "SUPPLIERS_WRITE");
        grant(ROLE, "WAREHOUSE_INPUTS_READ");
        grant(ROLE, "WAREHOUSE_INPUTS_WRITE");
        grant(ROLE, "WAREHOUSE_INPUTS_DELETE");
        grant(ROLE, "WAREHOUSE_INPUTS_CONFIRM");
        grant(ROLE, "WAREHOUSE_OUTPUTS_READ");
        grant(ROLE, "WAREHOUSE_OUTPUTS_EDIT");
        grant(ROLE, "WAREHOUSE_OUTPUTS_DELETE");
        grant(ROLE, "WAREHOUSE_OUTPUTS_CONFIRM");
        grant(ROLE, "DELIVERY_NOTES_READ");
        grant(ROLE, "DELIVERY_NOTES_WRITE");
        grant(ROLE, "INVOICES_READ");
        grant(ROLE, "INVOICES_WRITE");
        jdbc.update("""
                insert into usuario
                    (id, tienda_id, nombre, user_id, user_name, password_hash, rol_id, protegido, activo, idioma)
                values (?, ?, 'VENDEDOR', 'E-999001', 'Vendedor Pruebas', ?, ?, false, true, 'ES')
                on conflict (id) do update
                set password_hash = excluded.password_hash,
                    rol_id = excluded.rol_id,
                    activo = true
                """, USER, STORE, passwordEncoder.encode("0000"), ROLE);
        jdbc.update("""
                insert into usuario_tienda (usuario_id, tienda_id)
                values (?, ?)
                on conflict do nothing
                """, USER, STORE);
        jdbc.update("""
                insert into terminal (id, tienda_id, nombre, tipo, credential_hash, aprobada, activa)
                values (?, ?, 'SERVIDOR PRUEBAS', 'SERVIDOR', ?, true, true)
                on conflict (id) do update
                set aprobada = true, activa = true
                """, TERMINAL, STORE, passwordEncoder.encode("DEV-SERVER"));
    }

    private void grant(UUID roleId, String permission) {
        jdbc.update("""
                insert into rol_permiso (rol_id, permiso_id)
                select ?, id from permiso where codigo = ?
                on conflict do nothing
                """, roleId, permission);
    }

    private void seedLicense(UUID installation) {
        jdbc.update("""
                insert into licencia
                    (id, tienda_id, instalacion_id, referencia, valida_desde, valida_hasta,
                     max_windows, max_pda, blob_original, hash, format_version, importada_en,
                     import_metadata, import_result, activa, regimen_impuesto, tax_id,
                     taxpayer_type, ultima_validacion_saas, estado_saas)
                values (?, ?, ?, 'DEV-LICENCIA', ?, ?, 10, 10, 'DEV', 'DEV-HASH', 3, ?,
                    '{"dev":true}'::jsonb, 'ACEPTADA', true, 'IVA', 'B00000000',
                    'SOCIEDAD', ?, 'VALIDA')
                on conflict (referencia) do update
                set activa = true,
                    valida_hasta = excluded.valida_hasta,
                    estado_saas = 'VALIDA'
                """, id("license"), STORE, installation, ts(NOW.minusSeconds(3600)),
                ts(NOW.plusSeconds(365L * 24L * 60L * 60L)), ts(NOW), ts(NOW));
    }

    private void seedCatalog() {
        product(PRODUCT_A, "DEV-CAFE", "8410000000011", "Cafe molido pruebas", "3.50", "12.10");
        product(PRODUCT_B, "DEV-AGUA", "8410000000028", "Agua mineral pruebas", "1.20", "6.05");
        stock(PRODUCT_A, "100.000");
        stock(PRODUCT_B, "200.000");
        payment("EFECTIVO", false, true);
        payment("TARJETA", false, false);
        payment("TRANSFERENCIA", false, false);
        payment("VALE", false, false);
    }

    private void product(UUID id, String code, String barcode, String name, String cost, String sale) {
        jdbc.update("""
                insert into producto
                    (id, tienda_id, familia_id, impuesto_id, nombre, descripcion, precio_compra,
                     impuestos_incluidos, product_type, discount_type, comments)
                values (?, ?, ?, ?, ?, 'Producto de prueba para frontend', ?, true, 'UNIT', 'NORMAL',
                    'Dato generado por DevSampleDataSeeder')
                on conflict (id) do nothing
                """, id, STORE, FAMILY, TAX, name, new BigDecimal(cost));
        identifier(id, "CODIGO", code);
        identifier(id, "CODIGO_BARRAS", barcode);
        price(id, "VENTA", sale);
        price(id, "MEMBER", sale);
    }

    private void identifier(UUID product, String type, String value) {
        jdbc.update("""
                insert into producto_identificador (id, tienda_id, producto_id, tipo, valor)
                values (?, ?, ?, ?, ?)
                on conflict (producto_id, tipo) do nothing
                """, id("identifier-" + value), STORE, product, type, value);
    }

    private void price(UUID product, String tier, String amount) {
        jdbc.update("""
                insert into producto_precio (id, producto_id, tarifa, importe)
                values (?, ?, ?, ?)
                on conflict (producto_id, tarifa) do nothing
                """, id("price-" + product + "-" + tier), product, tier, new BigDecimal(amount));
    }

    private void stock(UUID product, String quantity) {
        jdbc.update("""
                insert into existencia (id, producto_id, almacen_id, cantidad)
                values (?, ?, ?, ?)
                on conflict (producto_id, almacen_id) do update set cantidad = excluded.cantidad
                """, id("stock-" + product), product, WAREHOUSE, new BigDecimal(quantity));
    }

    private void payment(String name, boolean reference, boolean drawer) {
        jdbc.update("""
                insert into metodo_pago
                    (id, empresa_id, nombre, protegido, activo, requiere_referencia, abre_caja_registradora)
                values (?, ?, ?, true, true, ?, ?)
                on conflict (empresa_id, nombre) do update
                set activo = true,
                    requiere_referencia = excluded.requiere_referencia,
                    abre_caja_registradora = excluded.abre_caja_registradora
                """, id("payment-" + name), COMPANY, name, reference, drawer);
    }

    private void seedParties() {
        jdbc.update("""
                insert into cliente
                    (id, empresa_id, nombre_fiscal, tipo_documento, numero_documento, direccion,
                     codigo_postal, poblacion, provincia, pais, telefono, email, observaciones,
                     tarifa, descuento, client_id, client_code_store_id)
                values (?, ?, 'CLIENTE PRUEBAS SL', 'CIF', 'B11111111', 'Calle Cliente 1',
                    '35001', 'Las Palmas', 'Las Palmas', 'ES', '600000001',
                    'cliente@example.test', 'Cliente de prueba frontend', 'VENTA', 0,
                    'C-001-999001', ?)
                on conflict (id) do nothing
                """, CUSTOMER, COMPANY, STORE);
        jdbc.update("""
                insert into proveedor
                    (id, empresa_id, razon_social, nombre_comercial, tipo_documento, numero_documento,
                     direccion, codigo_postal, poblacion, provincia, pais, telefono, email,
                     observaciones, supplier_id)
                values (?, ?, 'PROVEEDOR PRUEBAS SL', 'Proveedor Pruebas', 'CIF', 'B22222222',
                    'Calle Proveedor 1', '35002', 'Las Palmas', 'Las Palmas', 'ES',
                    '600000002', 'proveedor@example.test', 'Proveedor de prueba frontend',
                    'S-999001')
                on conflict (id) do nothing
                """, SUPPLIER, COMPANY);
        jdbc.update("""
                insert into producto_proveedor
                    (id, producto_id, proveedor_id, referencia_proveedor, ultimo_proveedor,
                     precio_compra_bruto, descuento_compra, ultima_entrada_en)
                values (?, ?, ?, 'PROV-DEV-CAFE', true, 3.50, 0.00, ?)
                on conflict (producto_id, proveedor_id) do nothing
                """, id("product-supplier"), PRODUCT_A, SUPPLIER, ts(NOW));
    }

    private void seedDocuments() {
        doc(CommercialDocumentType.TICKET, "001-260705-000001", "CONFIRMADO", PRODUCT_A, null, null,
                "2.000", "10.00", "20.00", "4.20", "24.20", true, "EFECTIVO");
        doc(CommercialDocumentType.ALBARAN_VENTA, "AV-001-26-000001", "PENDIENTE", PRODUCT_A, CUSTOMER, null,
                "1.000", "10.00", "10.00", "2.10", "12.10", true, null);
        doc(CommercialDocumentType.ALBARAN_COMPRA, "AC-001-26-000001", "PENDIENTE", PRODUCT_B, null, SUPPLIER,
                "10.000", "5.00", "50.00", "10.50", "60.50", true, null);
        doc(CommercialDocumentType.FACTURA_VENTA, "FV-001-26-000001", "PAGADO", PRODUCT_A, CUSTOMER, null,
                "1.000", "10.00", "10.00", "2.10", "12.10", false, "TARJETA");
        doc(CommercialDocumentType.FACTURA_COMPRA, "FC-001-26-000001", "PARCIAL", PRODUCT_B, null, SUPPLIER,
                "4.000", "5.00", "20.00", "4.20", "24.20", true, "TRANSFERENCIA");
        doc(CommercialDocumentType.RECTIFICATIVA_VENTA, "FRV-001-26-000001", "PENDIENTE", PRODUCT_A, CUSTOMER, null,
                "-1.000", "10.00", "-10.00", "-2.10", "-12.10", false, null);
        doc(CommercialDocumentType.RECTIFICATIVA_COMPRA, "FRC-001-26-000001", "PENDIENTE", PRODUCT_B, null, SUPPLIER,
                "-1.000", "5.00", "-5.00", "-1.05", "-6.05", false, null);
        draft();
        bulkDocuments();
    }

    private void doc(
            CommercialDocumentType type,
            String number,
            String status,
            UUID product,
            UUID customer,
            UUID supplier,
            String quantity,
            String unitPrice,
            String base,
            String tax,
            String total,
            boolean stockOrigin,
            String paymentMethod) {
        UUID documentId = id("doc-" + type);
        jdbc.update("""
                insert into documento
                    (id, tienda_id, almacen_id, tipo, estado, numero, fecha, creado_en, confirmado_en,
                     creado_por, confirmado_por, cliente_id, proveedor_id, numero_externo,
                     base_total, impuesto_total, total, moneda, origen_stock)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EUR', ?)
                on conflict (id) do nothing
                """, documentId, STORE, WAREHOUSE, type.name(), status, number, TODAY, ts(NOW), ts(NOW), USER, USER,
                customer, supplier, supplier == null ? null : "EXT-" + number, new BigDecimal(base),
                new BigDecimal(tax), new BigDecimal(total), stockOrigin);
        line(documentId, product, quantity, unitPrice, base, tax, total);
        movement(type, documentId, product, quantity);
        if (paymentMethod != null) {
            payment(documentId, paymentMethod, total);
        }
    }

    private void draft() {
        UUID documentId = id("doc-borrador");
        jdbc.update("""
                insert into documento
                    (id, tienda_id, almacen_id, tipo, estado, fecha, creado_en, creado_por,
                     cliente_id, base_total, impuesto_total, total, moneda, origen_stock)
                values (?, ?, ?, 'FACTURA_VENTA', 'BORRADOR', ?, ?, ?, ?, 5.00, 1.05, 6.05, 'EUR', false)
                on conflict (id) do nothing
                """, documentId, STORE, WAREHOUSE, TODAY, ts(NOW), USER, CUSTOMER);
        line(documentId, PRODUCT_B, "1.000", "6.05", "5.00", "1.05", "6.05");
    }

    private void bulkDocuments() {
        var counters = new int[TYPES.size()];
        for (int i = 0; i < BULK_DOCUMENTS; i++) {
            CommercialDocumentType type = TYPES.get(i % TYPES.size());
            int typeIndex = TYPES.indexOf(type);
            int sequence = counters[typeIndex]++ + 2;
            LocalDate date = TODAY.minusDays(i % 180);
            UUID product = i % 2 == 0 ? PRODUCT_A : PRODUCT_B;
            String unitPrice = i % 2 == 0 ? "10.00" : "5.00";
            int quantity = quantity(type, i);
            BigDecimal base = new BigDecimal(unitPrice).multiply(BigDecimal.valueOf(quantity));
            BigDecimal tax = base.multiply(new BigDecimal("0.21")).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal total = base.add(tax);
            doc(type, number(type, date, sequence), status(type, i), product,
                    customer(type), supplier(type), String.valueOf(quantity), unitPrice,
                    base.toPlainString(), tax.toPlainString(), total.toPlainString(),
                    stockOrigin(type), paymentMethod(type, i), date, "bulk-" + i);
        }
    }

    private int quantity(CommercialDocumentType type, int index) {
        int units = (index % 5) + 1;
        return switch (type) {
            case RECTIFICATIVA_VENTA, RECTIFICATIVA_COMPRA -> -units;
            default -> units;
        };
    }

    private String number(CommercialDocumentType type, LocalDate date, int sequence) {
        return switch (type) {
            case TICKET -> "001-%02d%02d%02d-%06d".formatted(
                    date.getYear() % 100, date.getMonthValue(), date.getDayOfMonth(), sequence);
            default -> "%s-001-%02d-%06d".formatted(prefix(type), date.getYear() % 100, sequence);
        };
    }

    private String prefix(CommercialDocumentType type) {
        return switch (type) {
            case ALBARAN_VENTA -> "AV";
            case ALBARAN_COMPRA -> "AC";
            case FACTURA_VENTA -> "FV";
            case FACTURA_COMPRA -> "FC";
            case RECTIFICATIVA_VENTA -> "FRV";
            case RECTIFICATIVA_COMPRA -> "FRC";
            case TICKET -> "T";
        };
    }

    private String status(CommercialDocumentType type, int index) {
        return switch (type) {
            case TICKET -> "CONFIRMADO";
            case FACTURA_VENTA, FACTURA_COMPRA -> List.of("PENDIENTE", "PARCIAL", "PAGADO").get(index % 3);
            default -> index % 2 == 0 ? "PENDIENTE" : "CONFIRMADO";
        };
    }

    private UUID customer(CommercialDocumentType type) {
        return switch (type) {
            case ALBARAN_VENTA, FACTURA_VENTA, RECTIFICATIVA_VENTA -> CUSTOMER;
            default -> null;
        };
    }

    private UUID supplier(CommercialDocumentType type) {
        return switch (type) {
            case ALBARAN_COMPRA, FACTURA_COMPRA, RECTIFICATIVA_COMPRA -> SUPPLIER;
            default -> null;
        };
    }

    private boolean stockOrigin(CommercialDocumentType type) {
        return type != CommercialDocumentType.FACTURA_VENTA
                && type != CommercialDocumentType.RECTIFICATIVA_VENTA;
    }

    private String paymentMethod(CommercialDocumentType type, int index) {
        if (type == CommercialDocumentType.TICKET) {
            return List.of("EFECTIVO", "TARJETA", "VALE").get(index % 3);
        }
        if ((type == CommercialDocumentType.FACTURA_VENTA || type == CommercialDocumentType.FACTURA_COMPRA)
                && index % 3 != 0) {
            return index % 2 == 0 ? "TARJETA" : "TRANSFERENCIA";
        }
        return null;
    }

    private void line(UUID documentId, UUID product, String quantity, String unitPrice, String base, String tax, String total) {
        jdbc.update("""
                insert into documento_linea
                    (id, documento_id, producto_id, posicion, cantidad, codigo, nombre, tarifa,
                     precio_unitario, descuento, impuestos_incluidos, regimen_impuesto,
                     porcentaje_impuesto, base, impuesto, total)
                select ?, ?, p.id, 1, ?, pi.valor, p.nombre, 'VENTA', ?, 0, true, 'IVA', 21.00, ?, ?, ?
                from producto p
                join producto_identificador pi on pi.producto_id = p.id and pi.tipo = 'CODIGO'
                where p.id = ?
                on conflict (documento_id, posicion) do nothing
                """, id("line-" + documentId), documentId, new BigDecimal(quantity), new BigDecimal(unitPrice),
                new BigDecimal(base), new BigDecimal(tax), new BigDecimal(total), product);
    }

    private void payment(UUID documentId, String method, String total) {
        BigDecimal amount = new BigDecimal(total).abs();
        jdbc.update("""
                insert into documento_pago
                    (id, documento_id, metodo_pago_id, posicion, importe, principal, entregado,
                     cambio, creado_en, referencia)
                select ?, ?, m.id, 1, ?, true,
                    case when m.nombre = 'EFECTIVO' then ? else null end,
                    case when m.nombre = 'EFECTIVO' then 0.00 else null end,
                    ?, 'DEV-' || m.nombre
                from metodo_pago m
                where m.empresa_id = ? and m.nombre = ?
                on conflict (documento_id, posicion) do nothing
                """, id("payment-" + documentId), documentId, amount, amount, ts(NOW), COMPANY, method);
    }

    private void doc(
            CommercialDocumentType type,
            String number,
            String status,
            UUID product,
            UUID customer,
            UUID supplier,
            String quantity,
            String unitPrice,
            String base,
            String tax,
            String total,
            boolean stockOrigin,
            String paymentMethod,
            LocalDate date,
            String key) {
        UUID documentId = id("doc-" + key);
        var dateTime = ts(date.atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC));
        jdbc.update("""
                insert into documento
                    (id, tienda_id, almacen_id, tipo, estado, numero, fecha, creado_en, confirmado_en,
                     creado_por, confirmado_por, cliente_id, proveedor_id, numero_externo,
                     base_total, impuesto_total, total, moneda, origen_stock)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EUR', ?)
                on conflict (id) do nothing
                """, documentId, STORE, WAREHOUSE, type.name(), status, number, date, dateTime, dateTime, USER, USER,
                customer, supplier, supplier == null ? null : "EXT-" + number, new BigDecimal(base),
                new BigDecimal(tax), new BigDecimal(total), stockOrigin);
        line(documentId, product, quantity, unitPrice, base, tax, total);
        movement(type, documentId, product, quantity, dateTime);
        if (paymentMethod != null) {
            payment(documentId, paymentMethod, total, dateTime);
        }
    }

    private void movement(CommercialDocumentType type, UUID documentId, UUID product, String quantity) {
        movement(type, documentId, product, quantity, ts(NOW));
    }

    private void payment(UUID documentId, String method, String total, Timestamp createdAt) {
        BigDecimal amount = new BigDecimal(total).abs();
        jdbc.update("""
                insert into documento_pago
                    (id, documento_id, metodo_pago_id, posicion, importe, principal, entregado,
                     cambio, creado_en, referencia)
                select ?, ?, m.id, 1, ?, true,
                    case when m.nombre = 'EFECTIVO' then ? else null end,
                    case when m.nombre = 'EFECTIVO' then 0.00 else null end,
                    ?, 'DEV-' || m.nombre
                from metodo_pago m
                where m.empresa_id = ? and m.nombre = ?
                on conflict (documento_id, posicion) do nothing
                """, id("payment-" + documentId), documentId, amount, amount, createdAt, COMPANY, method);
    }

    private void movement(CommercialDocumentType type, UUID documentId, UUID product, String quantity, Timestamp createdAt) {
        jdbc.update("""
                insert into movimiento_stock
                    (id, producto_id, almacen_id, usuario_id, documento_id, tipo, cantidad, creado_en)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (id) do nothing
                """, id("stock-move-" + documentId), product, WAREHOUSE, USER, documentId,
                stockMovementType(type), new BigDecimal(quantity), createdAt);
    }

    private static String stockMovementType(CommercialDocumentType type) {
        return switch (type) {
            case RECTIFICATIVA_VENTA -> CommercialDocumentType.FACTURA_VENTA.name();
            case RECTIFICATIVA_COMPRA -> CommercialDocumentType.FACTURA_COMPRA.name();
            default -> type.name();
        };
    }

    private void seedWarehouseOutput() {
        UUID outputId = id("warehouse-output");
        jdbc.update("""
                insert into salida_almacen
                    (id, tienda_id, almacen_id, numero, fecha, estado, destino, concepto,
                     creada_por, confirmada_por, confirmada_en)
                values (?, ?, ?, 'SAL-2026-000001', ?, 'CONFIRMADA', 'MERMA PRUEBAS',
                    'Salida de almacen de prueba frontend', ?, ?, ?)
                on conflict (id) do nothing
                """, outputId, STORE, WAREHOUSE, TODAY, USER, USER, ts(NOW));
        jdbc.update("""
                insert into salida_almacen_linea (id, salida_id, producto_id, cantidad)
                values (?, ?, ?, 1)
                on conflict (id) do nothing
                """, id("warehouse-output-line"), outputId, PRODUCT_A);
        jdbc.update("""
                insert into movimiento_stock
                    (id, producto_id, almacen_id, usuario_id, salida_almacen_id, tipo, cantidad, creado_en)
                values (?, ?, ?, ?, ?, 'SALIDA_ALMACEN', -1.000, ?)
                on conflict (id) do nothing
                """, id("stock-move-output"), PRODUCT_A, WAREHOUSE, USER, outputId, ts(NOW));
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(("tpv-erp-dev:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }
}
