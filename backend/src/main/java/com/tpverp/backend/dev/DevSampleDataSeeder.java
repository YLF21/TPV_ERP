package com.tpverp.backend.dev;

import com.tpverp.backend.document.CommercialDocumentType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
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
    private static final UUID WAREHOUSE_RESERVE = id("warehouse-reserve");
    private static final UUID WAREHOUSE_SHOWROOM = id("warehouse-showroom");
    private static final UUID WAREHOUSE_QUARANTINE = id("warehouse-quarantine");
    private static final UUID FAMILY = STORE;
    private static final UUID TAX = id("tax-iva-21");
    private static final UUID ADMIN_ROLE = id("role-admin");
    private static final UUID ADMIN_USER = id("user-admin");
    private static final UUID ROLE = id("role-ventas");
    private static final UUID USER = id("user-vendedor");
    private static final UUID TERMINAL = id("terminal-servidor");
    private static final UUID CASH_SESSION_HISTORY = id("cash-session-history");
    private static final UUID CUSTOMER = id("customer");
    private static final UUID CUSTOMER_BRONZE = id("customer-member-bronze");
    private static final UUID CUSTOMER_SILVER = id("customer-member-silver");
    private static final UUID CUSTOMER_GOLD = id("customer-member-gold");
    private static final UUID SUPPLIER = id("supplier");
    private static final UUID PRODUCT_A = id("product-cafe");
    private static final UUID PRODUCT_B = id("product-agua");
    private static final UUID PRODUCT_MEMBER = id("product-cafe-member");
    private static final UUID PRODUCT_OFFER = id("product-zumo-offer");
    private static final UUID PRODUCT_OFFER_DISCOUNT = id("product-galletas-offer-discount");
    private static final UUID PRODUCT_WHOLESALE = id("product-leche-wholesale");
    private static final UUID PRODUCT_NO_DISCOUNT = id("product-pan-no-discount");
    private static final int BULK_DOCUMENTS = 1_000;
    private static final int DEMO_PRODUCTS = 48;
    private static final int DEMO_CUSTOMERS = 12;
    private static final int DEMO_SUPPLIERS = 5;
    private static final int RECENT_SALES = 40;
    private static final List<CommercialDocumentType> TYPES = List.of(CommercialDocumentType.values());

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final LocalDate seedDate;
    private final Instant seedInstant;
    // Aliases kept local to the dataset recipes so their relative-date intent stays readable.
    private final LocalDate TODAY;
    private final Instant NOW;

    public DevSampleDataSeeder(
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            Clock clock,
            String configuredBaseDate) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.seedDate = configuredBaseDate == null || configuredBaseDate.isBlank()
                ? LocalDate.now(clock)
                : LocalDate.parse(configuredBaseDate);
        this.seedInstant = seedDate.atTime(10, 0).toInstant(ZoneOffset.UTC);
        this.TODAY = seedDate;
        this.NOW = seedInstant;
    }

    public static List<CommercialDocumentType> documentTypes() {
        return TYPES;
    }

    static UUID cashSessionHistoryId() {
        return CASH_SESSION_HISTORY;
    }

    static UUID terminalId() {
        return TERMINAL;
    }

    @Transactional
    // Loads an idempotent demo dataset only for the dev profile.
    public void seed() {
        UUID installation = installation();
        seedOrganization();
        seedSecurity();
        seedCashSessionHistory();
        seedLicense(installation);
        seedCatalog();
        seedParties();
        seedPromotions();
        seedDocuments();
        seedRecentSales();
        seedControlAlerts();
        synchronizeDocumentCounters();
        seedWarehouseDocuments();
        rebuildStockSnapshots();
    }

    LocalDate seedDate() {
        return seedDate;
    }

    private void synchronizeDocumentCounters() {
        var seededCounters = jdbc.query("""
                select tipo,
                       case when tipo = 'TICKET'
                            then to_char(fecha, 'YYYYMMDD')
                            else extract(year from fecha)::integer::text
                       end as periodo,
                       max(substring(numero from '([0-9]+)$')::integer) as ultimo_numero
                from documento
                where tienda_id = ?
                  and numero ~ '[0-9]+$'
                group by tipo,
                         case when tipo = 'TICKET'
                              then to_char(fecha, 'YYYYMMDD')
                              else extract(year from fecha)::integer::text
                         end
                """, (result, row) -> new SeededDocumentCounter(
                CommercialDocumentType.valueOf(result.getString("tipo")),
                result.getString("periodo"),
                result.getInt("ultimo_numero")), STORE);
        for (var counter : seededCounters) {
            var prefix = prefix(counter.type());
            jdbc.update("""
                    insert into contador_documento
                        (id, tienda_id, tipo, periodo, ultimo_numero, version)
                    values (?, ?, ?, ?, ?, 0)
                    on conflict (tienda_id, tipo, periodo) do update
                    set ultimo_numero = greatest(
                        contador_documento.ultimo_numero,
                        excluded.ultimo_numero)
                    """, id("document-counter-" + prefix + "-" + counter.period()),
                    STORE, prefix, counter.period(), counter.lastNumber());
        }
    }

    private record SeededDocumentCounter(
            CommercialDocumentType type,
            String period,
            int lastNumber) {
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
        seedWarehouse(WAREHOUSE_RESERVE, "RESERVA Y REPOSICION");
        seedWarehouse(WAREHOUSE_SHOWROOM, "TIENDA Y EXPOSICION");
        seedWarehouse(WAREHOUSE_QUARANTINE, "DEVOLUCIONES Y CUARENTENA");
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

    private void seedWarehouse(UUID warehouseId, String name) {
        jdbc.update("""
                insert into almacen (id, tienda_id, nombre, predeterminado, activo)
                values (?, ?, ?, false, true)
                on conflict (id) do update
                set nombre = excluded.nombre,
                    activo = true
                """, warehouseId, STORE, name);
    }

    private void seedSecurity() {
        jdbc.update("""
                insert into rol (id, tienda_id, nombre, protegido)
                values (?, ?, 'ADMIN', true)
                on conflict (id) do update
                set tienda_id = excluded.tienda_id,
                    nombre = excluded.nombre,
                    protegido = true
                """, ADMIN_ROLE, STORE);
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
                insert into usuario
                    (id, tienda_id, nombre, user_id, user_name, password_hash, rol_id,
                     protegido, activo, idioma, must_change_password)
                values (?, ?, 'ADMIN', 'E-999000', 'ADMIN', ?, ?,
                    true, true, 'ES', false)
                on conflict (id) do update
                set tienda_id = excluded.tienda_id,
                    user_name = excluded.user_name,
                    password_hash = excluded.password_hash,
                    rol_id = excluded.rol_id,
                    protegido = true,
                    activo = true,
                    must_change_password = false
                """, ADMIN_USER, STORE, passwordEncoder.encode("0000"), ADMIN_ROLE);
        jdbc.update("""
                insert into usuario_tienda (usuario_id, tienda_id)
                values (?, ?)
                on conflict do nothing
                """, USER, STORE);
        jdbc.update("""
                insert into usuario_tienda (usuario_id, tienda_id)
                values (?, ?)
                on conflict do nothing
                """, ADMIN_USER, STORE);
        jdbc.update("""
                insert into terminal (id, tienda_id, nombre, tipo, credential_hash, aprobada, activa)
                values (?, ?, 'SERVIDOR PRUEBAS', 'SERVIDOR', ?, true, true)
                on conflict (id) do update
                set aprobada = true,
                    activa = true
                """, TERMINAL, STORE, passwordEncoder.encode("DEV-SERVER"));
    }

    private void seedCashSessionHistory() {
        jdbc.update("""
                insert into sesion_caja
                    (id, tienda_id, terminal_id, usuario_apertura_id, abierta_en,
                     fondo_inicial, usuario_cierre_id, cerrada_en, efectivo_teorico,
                     fondo_dejado, descuadre, estado, cierre_tardio)
                values (?, ?, ?, ?, ?, 0.00, ?, ?, 0.00, 0.00, 0.00, 'CERRADA', false)
                on conflict (id) do nothing
                """,
                CASH_SESSION_HISTORY,
                STORE,
                TERMINAL,
                USER,
                ts(NOW.minusSeconds(7_200)),
                USER,
                ts(NOW.minusSeconds(3_600)));
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
        String[] families = {"BEBIDAS", "ALIMENTACION", "HIGIENE", "HOGAR", "PAPELERIA", "TECNOLOGIA"};
        String[] subfamilies = {"DESTACADOS", "BASICOS", "PREMIUM", "TEMPORADA", "PROFESIONAL", "ACCESORIOS"};
        for (int index = 0; index < families.length; index++) {
            seedFamily(index, families[index], subfamilies[index]);
        }
        product(PRODUCT_A, "DEV-CAFE", "8410000000011", "Cafe molido pruebas", "3.50", "12.10");
        product(PRODUCT_B, "DEV-AGUA", "8410000000028", "Agua mineral pruebas", "1.20", "6.05");
        pricedProduct(PRODUCT_MEMBER, "DEV-CAFE-SOCIO", "8410000000035",
                "Cafe premium precio socio", "4.20", "9.90", "7.50", null,
                "MEMBER_PRICE", "MEMBER_PRICE", false, null);
        pricedProduct(PRODUCT_OFFER, "DEV-ZUMO-OFERTA", "8410000000042",
                "Zumo naranja en oferta", "1.10", "2.80", null, "2.10",
                "DISCOUNT_PRICE", "OFFER_PRICE", true, null);
        pricedProduct(PRODUCT_OFFER_DISCOUNT, "DEV-GALLETAS-PROMO", "8410000000059",
                "Galletas promocion 20%", "1.25", "3.00", null, "2.40",
                "DISCOUNT_PRICE", "OFFER_DISCOUNT", true, "20.00");
        product(PRODUCT_WHOLESALE, "DEV-LECHE-MAYOR", "8410000000066",
                "Leche con precio mayorista", "0.65", "1.35");
        price(PRODUCT_WHOLESALE, "MAYORISTA", "1.05");
        pricedProduct(PRODUCT_NO_DISCOUNT, "DEV-PAN-SIN-DESCUENTO", "8410000000073",
                "Pan sin descuento manual", "0.55", "1.20", null, null,
                "NONE", "NORMAL", false, null);
        stock(PRODUCT_A, "100.000");
        stock(PRODUCT_B, "200.000");
        stock(PRODUCT_MEMBER, "60.000");
        stock(PRODUCT_OFFER, "80.000");
        stock(PRODUCT_OFFER_DISCOUNT, "45.000");
        stock(PRODUCT_WHOLESALE, "120.000");
        stock(PRODUCT_NO_DISCOUNT, "35.000");
        for (int index = 1; index <= DEMO_PRODUCTS; index++) {
            UUID productId = id("product-demo-catalog-" + index);
            int familyIndex = (index - 1) % families.length;
            BigDecimal cost = new BigDecimal("0.65").add(new BigDecimal("0.17").multiply(BigDecimal.valueOf(index)));
            BigDecimal sale = cost.multiply(new BigDecimal("2.15")).setScale(2, java.math.RoundingMode.HALF_UP);
            pricedProductInFamily(
                    productId,
                    "DEMO-%03d".formatted(index),
                    "8421000%06d".formatted(index),
                    "%s demo %02d".formatted(productNamePrefix(familyIndex), index),
                    cost.toPlainString(),
                    sale.toPlainString(),
                    index % 9 == 0 ? sale.multiply(new BigDecimal("0.90")).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : null,
                    index % 8 == 0 ? sale.multiply(new BigDecimal("0.80")).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : null,
                    index % 8 == 0 ? "DISCOUNT_PRICE" : index % 9 == 0 ? "MEMBER_PRICE" : "NORMAL",
                    index % 8 == 0 ? "OFFER_PRICE" : index % 9 == 0 ? "MEMBER_PRICE" : "NORMAL",
                    index % 8 == 0,
                    null,
                    id("family-demo-" + familyIndex),
                    id("subfamily-demo-" + familyIndex));
            stock(productId, new BigDecimal((index * 17) % 240).setScale(3).toPlainString());
            if (index % 17 == 0) {
                jdbc.update("update producto set activo = false where id = ?", productId);
            }
        }
        seedMultiWarehouseStock();
        payment("EFECTIVO", false, true);
        payment("TARJETA", false, false);
        payment("TRANSFERENCIA", false, false);
        payment("VALE", false, false);
        payment("COMPENSACION_DEVOLUCION", false, false);
    }

    private void seedFamily(int index, String familyName, String subfamilyName) {
        UUID familyId = id("family-demo-" + index);
        jdbc.update("""
                insert into familia (id, tienda_id, family_id, nombre, predeterminada)
                values (?, ?, ?, ?, false)
                on conflict (id) do update set nombre = excluded.nombre
                """, familyId, STORE, "DEMO-F%02d".formatted(index + 1), familyName);
        jdbc.update("""
                insert into subfamilia (id, familia_id, subfamily_id, nombre)
                values (?, ?, ?, ?)
                on conflict (id) do update set nombre = excluded.nombre
                """, id("subfamily-demo-" + index), familyId,
                "DEMO-SF%02d".formatted(index + 1), subfamilyName);
    }

    private String productNamePrefix(int familyIndex) {
        return switch (familyIndex) {
            case 0 -> "Bebida";
            case 1 -> "Alimento";
            case 2 -> "Higiene";
            case 3 -> "Hogar";
            case 4 -> "Papeleria";
            default -> "Accesorio tecnologico";
        };
    }

    private void product(UUID id, String code, String barcode, String name, String cost, String sale) {
        pricedProduct(id, code, barcode, name, cost, sale, sale, null,
                "NORMAL", "NORMAL", false, null);
    }

    private void pricedProduct(
            UUID id,
            String code,
            String barcode,
            String name,
            String cost,
            String sale,
            String member,
            String offer,
            String discountType,
            String priceUseMode,
            boolean offerActive,
            String offerDiscountPercent) {
        pricedProductInFamily(id, code, barcode, name, cost, sale, member, offer,
                discountType, priceUseMode, offerActive, offerDiscountPercent, FAMILY, null);
    }

    private void pricedProductInFamily(
            UUID id,
            String code,
            String barcode,
            String name,
            String cost,
            String sale,
            String member,
            String offer,
            String discountType,
            String priceUseMode,
            boolean offerActive,
            String offerDiscountPercent,
            UUID familyId,
            UUID subfamilyId) {
        jdbc.update("""
                insert into producto
                    (id, tienda_id, familia_id, subfamilia_id, impuesto_id, nombre, descripcion, precio_compra,
                     impuestos_incluidos, product_type, discount_type, price_use_mode,
                     oferta_activa, oferta_desde, oferta_hasta, oferta_descuento_porcentaje,
                     comments)
                values (?, ?, ?, ?, ?, ?, 'Producto de prueba para frontend', ?, true, 'UNIT', ?, ?,
                    ?, case when ? then date '2025-01-01' else null end,
                    case when ? then date '2035-12-31' else null end, ?,
                    'Dato generado por DevSampleDataSeeder')
                on conflict (id) do nothing
                """, id, STORE, familyId, subfamilyId, TAX, name, new BigDecimal(cost), discountType, priceUseMode,
                offerActive, offerActive, offerActive,
                offerDiscountPercent == null ? null : new BigDecimal(offerDiscountPercent));
        identifier(id, "CODIGO", code);
        identifier(id, "CODIGO_BARRAS", barcode);
        price(id, "VENTA", sale);
        if (member != null) {
            price(id, "MEMBER", member);
        }
        if (offer != null) {
            price(id, "OFERTA", offer);
        }
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
        stock(product, WAREHOUSE, quantity);
    }

    private void stock(UUID product, UUID warehouse, String quantity) {
        var openingQuantity = new BigDecimal(quantity);
        var movementId = id("opening-stock-" + product + "-" + warehouse);
        if (openingQuantity.signum() == 0) {
            // A zero is not a business movement and must not manufacture an empty snapshot.
            jdbc.update("delete from movimiento_stock where id = ?", movementId);
            return;
        }
        jdbc.update("""
                insert into movimiento_stock
                    (id, producto_id, almacen_id, usuario_id, tipo, cantidad, motivo, creado_en)
                values (?, ?, ?, ?, 'AJUSTE', ?, 'Saldo inicial de datos de demostracion', ?)
                on conflict (id) do update
                set producto_id = excluded.producto_id,
                    almacen_id = excluded.almacen_id,
                    usuario_id = excluded.usuario_id,
                    tipo = excluded.tipo,
                    cantidad = excluded.cantidad,
                    motivo = excluded.motivo,
                    creado_en = excluded.creado_en
                """, movementId, product, warehouse, USER, openingQuantity,
                ts(NOW.minusSeconds(30L * 24L * 60L * 60L)));
    }

    private void rebuildStockSnapshots() {
        jdbc.update("delete from existencia");
        jdbc.update("""
                insert into existencia (id, producto_id, almacen_id, cantidad, version)
                select md5(producto_id::text || ':' || almacen_id::text)::uuid,
                       producto_id,
                       almacen_id,
                       sum(cantidad),
                       0
                from movimiento_stock
                group by producto_id, almacen_id
                """);
    }

    private void seedMultiWarehouseStock() {
        UUID[] featuredProducts = {
                PRODUCT_A, PRODUCT_B, PRODUCT_MEMBER, PRODUCT_OFFER,
                PRODUCT_OFFER_DISCOUNT, PRODUCT_WHOLESALE, PRODUCT_NO_DISCOUNT
        };
        for (int index = 0; index < featuredProducts.length; index++) {
            stock(featuredProducts[index], WAREHOUSE_RESERVE, "%d.000".formatted(45 + index * 7));
            stock(featuredProducts[index], WAREHOUSE_SHOWROOM, "%d.000".formatted(12 + index * 3));
            stock(featuredProducts[index], WAREHOUSE_QUARANTINE, "%d.000".formatted(index % 3));
        }
        for (int index = 1; index <= 18; index++) {
            UUID productId = id("product-demo-catalog-" + index);
            stock(productId, WAREHOUSE_RESERVE, "%d.000".formatted(20 + (index * 9) % 80));
            stock(productId, WAREHOUSE_SHOWROOM, "%d.000".formatted(4 + (index * 5) % 25));
            stock(productId, WAREHOUSE_QUARANTINE, "%d.000".formatted(index % 4));
        }
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
        memberCategory("BRONCE", "Bronce", "5.00", 100);
        memberCategory("PLATA", "Plata", "10.00", 200);
        memberCategory("ORO", "Oro", "15.00", 300);
        demoMember(CUSTOMER_BRONZE, "CLIENTE BRONCE DEMO", "11111111H",
                "C-001-999002", "BRONCE", "M-001-999002", "SOCIO-BRONCE-001");
        demoMember(CUSTOMER_SILVER, "CLIENTE PLATA DEMO", "22222222J",
                "C-001-999003", "PLATA", "M-001-999003", "SOCIO-PLATA-001");
        demoMember(CUSTOMER_GOLD, "CLIENTE ORO DEMO", "33333333P",
                "C-001-999004", "ORO", "M-001-999004", "SOCIO-ORO-001");
        for (int index = 1; index <= DEMO_CUSTOMERS; index++) {
            seedDemoCustomer(index);
        }
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
        for (int index = 1; index <= DEMO_SUPPLIERS; index++) {
            seedDemoSupplier(index);
        }
        jdbc.update("""
                insert into producto_proveedor
                    (id, producto_id, proveedor_id, referencia_proveedor, ultimo_proveedor,
                     precio_compra_bruto, descuento_compra, ultima_entrada_en)
                values (?, ?, ?, 'PROV-DEV-CAFE', true, 3.50, 0.00, ?)
                on conflict (producto_id, proveedor_id) do nothing
                """, id("product-supplier"), PRODUCT_A, SUPPLIER, ts(NOW));
        for (int index = 1; index <= DEMO_PRODUCTS; index++) {
            UUID productId = id("product-demo-catalog-" + index);
            UUID supplierId = id("supplier-demo-" + (((index - 1) % DEMO_SUPPLIERS) + 1));
            jdbc.update("""
                    insert into producto_proveedor
                        (id, producto_id, proveedor_id, referencia_proveedor, ultimo_proveedor,
                         precio_compra_bruto, descuento_compra, ultima_entrada_en)
                    select ?, p.id, s.id, ?, true, p.precio_compra, ?, ?
                    from producto p, proveedor s
                    where p.id = ? and s.id = ?
                    on conflict (producto_id, proveedor_id) do update
                    set ultimo_proveedor = true,
                        precio_compra_bruto = excluded.precio_compra_bruto,
                        descuento_compra = excluded.descuento_compra,
                        ultima_entrada_en = excluded.ultima_entrada_en
                    """, id("product-supplier-demo-" + index), "REF-DEMO-%03d".formatted(index),
                    new BigDecimal(index % 4 == 0 ? "5.00" : "0.00"), ts(NOW), productId, supplierId);
        }
    }

    private void seedDemoCustomer(int index) {
        String[] cities = {"Las Palmas", "Telde", "Arucas", "Galdar", "Maspalomas", "Aguimes"};
        UUID customerId = id("customer-demo-" + index);
        jdbc.update("""
                insert into cliente
                    (id, empresa_id, nombre_fiscal, tipo_documento, numero_documento, direccion,
                     codigo_postal, poblacion, provincia, pais, telefono, email, observaciones,
                     tarifa, descuento, client_id, client_code_store_id, activo)
                values (?, ?, ?, 'NIF', ?, ?, ?, ?, 'Las Palmas', 'ES', ?, ?, ?, 'VENTA', ?, ?, ?, ?)
                on conflict (id) do update
                set nombre_fiscal = excluded.nombre_fiscal,
                    poblacion = excluded.poblacion,
                    telefono = excluded.telefono,
                    email = excluded.email,
                    activo = excluded.activo
                """, customerId, COMPANY, "CLIENTE DEMO %02d".formatted(index),
                "%08dX".formatted(40_000_000 + index), "Calle Demo " + index,
                "35%03d".formatted(index), cities[(index - 1) % cities.length],
                "610%06d".formatted(index), "cliente%02d@example.test".formatted(index),
                "Cliente generado para filtros, ventas y formularios", new BigDecimal((index % 4) * 2),
                "C-001-%06d".formatted(100 + index), STORE, index != DEMO_CUSTOMERS);
    }

    private void seedDemoSupplier(int index) {
        UUID supplierId = id("supplier-demo-" + index);
        jdbc.update("""
                insert into proveedor
                    (id, empresa_id, razon_social, nombre_comercial, tipo_documento, numero_documento,
                     direccion, codigo_postal, poblacion, provincia, pais, telefono, email,
                     observaciones, supplier_id, activo)
                values (?, ?, ?, ?, 'CIF', ?, ?, ?, ?, 'Las Palmas', 'ES', ?, ?, ?, ?, true)
                on conflict (id) do update
                set razon_social = excluded.razon_social,
                    nombre_comercial = excluded.nombre_comercial,
                    telefono = excluded.telefono,
                    email = excluded.email,
                    activo = true
                """, supplierId, COMPANY, "DISTRIBUCIONES DEMO %02d SL".formatted(index),
                "Proveedor Demo %02d".formatted(index), "B%08d".formatted(50_000_000 + index),
                "Avenida Proveedor " + index, "35%03d".formatted(100 + index),
                index % 2 == 0 ? "Telde" : "Las Palmas", "620%06d".formatted(index),
                "proveedor%02d@example.test".formatted(index), "Proveedor generado para compras y stock",
                "S-%06d".formatted(100 + index));
    }

    private void seedPromotions() {
        seedPromotion("promo-threshold", "10% en compras superiores a 30 EUR",
                "PURCHASE_THRESHOLD_DISCOUNT", "ACTIVE", "ALL", "SALE",
                new BigDecimal("30.00"), null, null, null, new BigDecimal("10.00"), null);
        seedPromotion("promo-buy-two", "Lleva 2 y paga 1",
                "BUY_X_PAY_Y", "ACTIVE", "ALL", "PRODUCT_LIST",
                null, new BigDecimal("2"), new BigDecimal("1"), null, null, id("product-demo-catalog-1"));
        seedPromotion("promo-second-unit", "Segunda unidad al 50%",
                "SECOND_UNIT_PERCENT", "ACTIVE", "IDENTIFIED_CUSTOMERS", "PRODUCT_LIST",
                null, null, null, null, new BigDecimal("50.00"), id("product-demo-catalog-2"));
        seedPromotion("promo-pack", "Pack de 3 por 9,90 EUR",
                "FIXED_PACK_PRICE", "ACTIVE", "MEMBERS_ONLY", "PRODUCT_LIST",
                null, new BigDecimal("3"), null, new BigDecimal("9.90"), null, id("product-demo-catalog-3"));
        seedPromotion("promo-draft", "Promocion borrador para editar",
                "QUANTITY_DISCOUNT", "DRAFT", "ALL", "PRODUCT_LIST",
                null, null, null, null, null, id("product-demo-catalog-4"));
    }

    private void seedPromotion(
            String key,
            String name,
            String type,
            String status,
            String customerSegment,
            String scope,
            BigDecimal minimumAmount,
            BigDecimal buyQuantity,
            BigDecimal payQuantity,
            BigDecimal packPrice,
            BigDecimal discountPercent,
            UUID targetProduct) {
        UUID promotionId = id(key);
        jdbc.update("""
                insert into promocion
                    (id, empresa_id, nombre, descripcion, tipo, estado, segmento_cliente,
                     ambito, fecha_inicio, fecha_fin, minimo_importe, compra_cantidad,
                     paga_cantidad, descuento_porcentaje, precio_lote, usada,
                     creado_en, actualizado_en, version)
                values (?, ?, ?, 'Dato de demostracion para APP GESTION', ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, false, ?, ?, 0)
                on conflict (id) do update
                set nombre = excluded.nombre,
                    estado = excluded.estado,
                    fecha_inicio = excluded.fecha_inicio,
                    fecha_fin = excluded.fecha_fin,
                    actualizado_en = excluded.actualizado_en
                """, promotionId, COMPANY, name, type, status, customerSegment, scope,
                TODAY.minusDays(15), TODAY.plusDays(45), minimumAmount, buyQuantity,
                payQuantity, discountPercent, packPrice, ts(NOW), ts(NOW));
        if (targetProduct != null) {
            jdbc.update("""
                    insert into promocion_objetivo (id, promocion_id, tipo, objetivo_id, version)
                    values (?, ?, 'PRODUCT', ?, 0)
                    on conflict (promocion_id, tipo, objetivo_id) do nothing
                    """, id(key + "-target"), promotionId, targetProduct);
        }
    }

    private void memberCategory(String code, String name, String discount, int sortOrder) {
        jdbc.update("""
                insert into member_category
                    (id, empresa_id, code, name, min_points, discount_percent,
                     discount_enabled, manual_only, active, sort_order)
                values (?, ?, ?, ?, 0, ?, true, false, true, ?)
                on conflict (empresa_id, code) do update
                set name = excluded.name,
                    discount_percent = excluded.discount_percent,
                    discount_enabled = true,
                    manual_only = false,
                    active = true,
                    sort_order = excluded.sort_order
                """, id("member-category-" + code), COMPANY, code, name,
                new BigDecimal(discount), sortOrder);
    }

    private void demoMember(
            UUID customerId,
            String name,
            String documentNumber,
            String clientId,
            String categoryCode,
            String memberId,
            String memberNumber) {
        jdbc.update("""
                insert into cliente
                    (id, empresa_id, nombre_fiscal, tipo_documento, numero_documento,
                     observaciones, tarifa, descuento, client_id, client_code_store_id)
                values (?, ?, ?, 'NIF', ?, 'Socio de demostracion para pruebas de descuento',
                        'VENTA', 0, ?, ?)
                on conflict (id) do update
                set nombre_fiscal = excluded.nombre_fiscal,
                    numero_documento = excluded.numero_documento,
                    observaciones = excluded.observaciones,
                    tarifa = 'VENTA',
                    descuento = 0
                """, customerId, COMPANY, name, documentNumber, clientId, STORE);
        jdbc.update("""
                insert into miembro
                    (id, empresa_id, cliente_id, member_id, member_code_store_id,
                     num_member, member_since, member_balance, active,
                     member_category_id, auto_category_locked)
                values (?, ?, ?, ?, ?, ?, ?, 0, true,
                        (select id from member_category where empresa_id = ? and code = ?), true)
                on conflict (id) do update
                set num_member = excluded.num_member,
                    active = true,
                    member_category_id = excluded.member_category_id,
                    auto_category_locked = true
                """, id("member-" + categoryCode), COMPANY, customerId, memberId, STORE,
                memberNumber, TODAY, COMPANY, categoryCode);
    }

    private void seedDocuments() {
        doc(CommercialDocumentType.TICKET, "001-260705-000001", "CONFIRMADO", PRODUCT_A, null, null,
                "2.000", "10.00", "20.00", "4.20", "24.20", true, "EFECTIVO");
        doc(CommercialDocumentType.ALBARAN_VENTA, "AV-001-26-000001", "PENDIENTE", PRODUCT_A, CUSTOMER, null,
                "1.000", "10.00", "10.00", "2.10", "12.10", true, null);
        doc(CommercialDocumentType.FACTURA_VENTA, "FV-001-26-000001", "PAGADO", PRODUCT_A, CUSTOMER, null,
                "1.000", "10.00", "10.00", "2.10", "12.10", false, "TARJETA");
        doc(CommercialDocumentType.RECTIFICATIVA_VENTA, "FRV-001-26-000001", "PENDIENTE", PRODUCT_A, CUSTOMER, null,
                "-1.000", "10.00", "-10.00", "-2.10", "-12.10", false, null);
        draft();
        bulkDocuments();
    }

    private void seedRecentSales() {
        for (int index = 0; index < RECENT_SALES; index++) {
            LocalDate date = TODAY.minusDays(index % 7);
            UUID product = id("product-demo-catalog-" + ((index % 12) + 1));
            UUID customer = index % 3 == 0 ? id("customer-demo-" + ((index % DEMO_CUSTOMERS) + 1)) : null;
            int quantity = (index % 4) + 1;
            BigDecimal unitPrice = new BigDecimal("1.80").add(new BigDecimal("0.45").multiply(BigDecimal.valueOf(index % 10)));
            BigDecimal base = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal tax = base.multiply(new BigDecimal("0.21")).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal total = base.add(tax);
            doc(CommercialDocumentType.TICKET,
                    "001-%02d%02d%02d-%06d".formatted(date.getYear() % 100, date.getMonthValue(), date.getDayOfMonth(), 800_001 + index),
                    "CONFIRMADO", product, customer, null, String.valueOf(quantity), unitPrice.toPlainString(),
                    base.toPlainString(), tax.toPlainString(), total.toPlainString(), true,
                    List.of("EFECTIVO", "TARJETA", "VALE").get(index % 3), date, "recent-sale-" + index);
        }
    }

    private void seedControlAlerts() {
        seedControlRule("rule-ticket-cancelled", "TICKET_CANCELLED", "Anulacion de ticket", "{}");
        seedControlRule("rule-manual-discount", "MANUAL_DISCOUNT_OVER_PERCENT", "Descuento manual superior al 10%", "{\"thresholdPercent\":10}");
        seedControlRule("rule-inactive-product", "INACTIVE_PRODUCT_SOLD", "Venta de producto desactivado", "{}");
        String[] ruleKeys = {"rule-ticket-cancelled", "rule-manual-discount", "rule-inactive-product"};
        String[] types = {"TICKET_CANCELLED", "MANUAL_DISCOUNT_OVER_PERCENT", "INACTIVE_PRODUCT_SOLD"};
        String[] names = {"Anulacion de ticket", "Descuento manual superior al 10%", "Venta de producto desactivado"};
        String[] statuses = {"NEW", "NEW", "REVIEWED", "NEW", "CLOSED", "REVIEWED", "NEW", "DISMISSED", "NEW"};
        String[] priorities = {"CRITICAL", "HIGH", "MEDIUM", "HIGH", "INFORMATIONAL", "MEDIUM", "CRITICAL", "INFORMATIONAL", "HIGH"};
        for (int index = 0; index < statuses.length; index++) {
            int ruleIndex = index % ruleKeys.length;
            UUID documentId = id("doc-recent-sale-" + index);
            UUID ruleId = id(ruleKeys[ruleIndex]);
            UUID eventId = id("control-event-demo-" + index);
            Timestamp occurredAt = ts(NOW.minusSeconds(index * 3_600L));
            jdbc.update("""
                    insert into control_evento
                        (id, tienda_id, regla_id, regla_numero_version, regla_nombre, tipo,
                         origen_tipo, origen_id, documento_id, documento_numero, terminal_id,
                         usuario_id, usuario_nombre, ocurrido_en, datos)
                    values (?, ?, ?, 1, ?, ?, 'DOCUMENT', ?, ?, ?, ?, ?, 'VENDEDOR', ?, cast(? as jsonb))
                    on conflict (id) do nothing
                    """, eventId, STORE, ruleId, names[ruleIndex], types[ruleIndex], documentId,
                    documentId, "T-DEMO-%03d".formatted(index + 1), TERMINAL, USER, occurredAt,
                    "{\"summary\":\"Alerta de demostracion %d\",\"amount\":%d}".formatted(index + 1, 15 + index));
            jdbc.update("""
                    insert into control_alerta
                        (id, tienda_id, evento_id, estado, creada_en, actualizada_en,
                         prioridad, asignada_a, vence_en, version)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    on conflict (id) do update
                    set estado = excluded.estado,
                        prioridad = excluded.prioridad,
                        asignada_a = excluded.asignada_a,
                        vence_en = excluded.vence_en,
                        actualizada_en = excluded.actualizada_en
                    """, id("control-alert-demo-" + index), STORE, eventId, statuses[index], occurredAt, occurredAt,
                    priorities[index], index % 2 == 0 ? ADMIN_USER : null,
                    ts(NOW.plusSeconds((index - 3L) * 7_200L)));
        }
    }

    private void seedControlRule(String key, String type, String name, String configuration) {
        UUID ruleId = id(key);
        jdbc.update("""
                insert into control_regla
                    (id, tienda_id, tipo, nombre, activa, configuracion, numero_version,
                     creado_por, actualizado_por, creado_en, actualizado_en, version)
                values (?, ?, ?, ?, true, cast(? as jsonb), 1, ?, ?, ?, ?, 0)
                on conflict (id) do update
                set nombre = excluded.nombre,
                    activa = true,
                    configuracion = excluded.configuracion,
                    actualizado_en = excluded.actualizado_en
                """, ruleId, STORE, type, name, configuration, ADMIN_USER, ADMIN_USER, ts(NOW), ts(NOW));
        jdbc.update("""
                insert into control_regla_version
                    (id, regla_id, tienda_id, numero_version, tipo, nombre, activa,
                     configuracion, cambiado_por, cambiado_en)
                values (?, ?, ?, 1, ?, ?, true, cast(? as jsonb), ?, ?)
                on conflict (regla_id, numero_version) do nothing
                """, id(key + "-version-1"), ruleId, STORE, type, name, configuration, ADMIN_USER, ts(NOW));
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
            case RECTIFICATIVA_VENTA -> -units;
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
            case FACTURA_VENTA -> "FV";
            case RECTIFICATIVA_VENTA -> "FRV";
            case TICKET -> "T";
            default -> throw new IllegalArgumentException("Tipo de documento no soportado por el seeder: " + type);
        };
    }

    private String status(CommercialDocumentType type, int index) {
        return switch (type) {
            case TICKET -> "CONFIRMADO";
            case FACTURA_VENTA -> List.of("PENDIENTE", "PARCIAL", "PAGADO").get(index % 3);
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
        if (type == CommercialDocumentType.FACTURA_VENTA
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
            default -> type.name();
        };
    }

    private void seedWarehouseDocuments() {
        seedWarehouseOutput(
                "warehouse-output", warehouseNumber("SAL", "000001"), TODAY,
                "MERMA PRUEBAS", "Salida de almacen de prueba frontend",
                PRODUCT_A, 1, "12.10", 0);
        seedWarehouseOutput(
                "warehouse-output-900001", warehouseNumber("SAL", "900001"), TODAY.minusDays(1),
                "CONSUMO INTERNO", "Material utilizado por el equipo",
                PRODUCT_B, 6, "6.05", 1);
        seedWarehouseOutput(
                "warehouse-output-900002", warehouseNumber("SAL", "900002"), TODAY.minusDays(2),
                "ROTURA", "Unidades dañadas durante la manipulación",
                PRODUCT_MEMBER, 2, "9.90", 2);
        seedWarehouseOutput(
                "warehouse-output-900003", warehouseNumber("SAL", "900003"), TODAY.minusDays(3),
                "CADUCIDAD", "Retirada preventiva de producto",
                PRODUCT_OFFER, 4, "2.80", 3);
        seedWarehouseOutput(
                "warehouse-output-900004", warehouseNumber("SAL", "900004"), TODAY.minusDays(5),
                "PROMOCIÓN", "Muestras entregadas en campaña",
                PRODUCT_OFFER_DISCOUNT, 8, "3.00", 5);
        seedWarehouseOutput(
                "warehouse-output-900005", warehouseNumber("SAL", "900005"), TODAY.minusDays(7),
                "TRASPASO EXTERNO", "Material enviado a exposición",
                PRODUCT_WHOLESALE, 12, "1.35", 7);
        seedWarehouseOutput(
                "warehouse-output-900006", warehouseNumber("SAL", "900006"), TODAY.minusDays(10),
                "AJUSTE INVENTARIO", "Regularización de conteo físico",
                PRODUCT_NO_DISCOUNT, 3, "1.20", 10);

        seedWarehouseInput(
                "warehouse-input-900001", warehouseNumber("ENT", "900001"), TODAY,
                "PROVEEDOR HABITUAL", "Reposición semanal de café",
                PRODUCT_A, 24, "3.50", 0);
        seedWarehouseInput(
                "warehouse-input-900002", warehouseNumber("ENT", "900002"), TODAY.minusDays(1),
                "PEDIDO PROGRAMADO", "Entrada de bebidas",
                PRODUCT_B, 48, "1.20", 1);
        seedWarehouseInput(
                "warehouse-input-900003", warehouseNumber("ENT", "900003"), TODAY.minusDays(2),
                "COMPRA PROMOCIONAL", "Entrada para campaña de socios",
                PRODUCT_MEMBER, 12, "4.20", 2);
        seedWarehouseInput(
                "warehouse-input-900004", warehouseNumber("ENT", "900004"), TODAY.minusDays(4),
                "REPOSICIÓN URGENTE", "Reposición de producto en oferta",
                PRODUCT_OFFER, 18, "1.10", 4);
        seedWarehouseInput(
                "warehouse-input-900005", warehouseNumber("ENT", "900005"), TODAY.minusDays(6),
                "PEDIDO MAYORISTA", "Entrada de leche por volumen",
                PRODUCT_WHOLESALE, 36, "0.65", 6);
        seedWarehouseInput(
                "warehouse-input-900006", warehouseNumber("ENT", "900006"), TODAY.minusDays(9),
                "REGULARIZACIÓN", "Existencias localizadas en inventario",
                PRODUCT_NO_DISCOUNT, 10, "0.55", 9);

        seedWarehouseInput(
                "warehouse-reserve-input-1", WAREHOUSE_RESERVE, warehouseNumber("ENT", "910001"), TODAY.minusDays(1),
                "PLATAFORMA LOGISTICA", "Recepcion de pedido para reserva",
                PRODUCT_A, 80, "3.50", 1);
        seedWarehouseInput(
                "warehouse-reserve-input-2", WAREHOUSE_RESERVE, warehouseNumber("ENT", "910002"), TODAY.minusDays(8),
                "PROVEEDOR BEBIDAS", "Reposicion semanal de bebidas",
                PRODUCT_B, 120, "1.20", 8);
        seedWarehouseOutput(
                "warehouse-reserve-output-1", WAREHOUSE_RESERVE, warehouseNumber("SAL", "910001"), TODAY.minusDays(2),
                "TIENDA Y EXPOSICION", "Preparacion de reposicion de lineal",
                PRODUCT_A, 18, "12.10", 2);
        seedWarehouseOutput(
                "warehouse-reserve-output-2", WAREHOUSE_RESERVE, warehouseNumber("SAL", "910002"), TODAY.minusDays(9),
                "EVENTO COMERCIAL", "Material reservado para degustacion",
                PRODUCT_B, 24, "6.05", 9);

        seedWarehouseInput(
                "warehouse-showroom-input-1", WAREHOUSE_SHOWROOM, warehouseNumber("ENT", "920001"), TODAY.minusDays(3),
                "RESERVA Y REPOSICION", "Entrada para reposicion de estanterias",
                PRODUCT_MEMBER, 20, "4.20", 3);
        seedWarehouseInput(
                "warehouse-showroom-input-2", WAREHOUSE_SHOWROOM, warehouseNumber("ENT", "920002"), TODAY.minusDays(11),
                "MONTAJE EXPOSICION", "Productos destinados a exposicion",
                PRODUCT_OFFER, 30, "1.10", 11);
        seedWarehouseOutput(
                "warehouse-showroom-output-1", WAREHOUSE_SHOWROOM, warehouseNumber("SAL", "920001"), TODAY.minusDays(4),
                "CLIENTE PROFESIONAL", "Entrega de muestras comerciales",
                PRODUCT_MEMBER, 5, "9.90", 4);
        seedWarehouseOutput(
                "warehouse-showroom-output-2", WAREHOUSE_SHOWROOM, warehouseNumber("SAL", "920002"), TODAY.minusDays(12),
                "DEVOLUCIONES Y CUARENTENA", "Retirada de producto con embalaje deteriorado",
                PRODUCT_OFFER, 3, "2.80", 12);

        seedWarehouseInput(
                "warehouse-quarantine-input-1", WAREHOUSE_QUARANTINE, warehouseNumber("ENT", "930001"), TODAY.minusDays(5),
                "DEVOLUCION DE CLIENTE", "Producto pendiente de revision",
                PRODUCT_OFFER_DISCOUNT, 6, "1.25", 5);
        seedWarehouseInput(
                "warehouse-quarantine-input-2", WAREHOUSE_QUARANTINE, warehouseNumber("ENT", "930002"), TODAY.minusDays(14),
                "CONTROL DE CALIDAD", "Lote inmovilizado preventivamente",
                PRODUCT_WHOLESALE, 8, "0.65", 14);
        seedWarehouseOutput(
                "warehouse-quarantine-output-1", WAREHOUSE_QUARANTINE, warehouseNumber("SAL", "930001"), TODAY.minusDays(6),
                "GESTOR DE RESIDUOS", "Baja definitiva por producto no apto",
                PRODUCT_OFFER_DISCOUNT, 2, "3.00", 6);
        seedWarehouseOutput(
                "warehouse-quarantine-output-2", WAREHOUSE_QUARANTINE, warehouseNumber("SAL", "930002"), TODAY.minusDays(15),
                "PROVEEDOR", "Devolucion de mercancia defectuosa",
                PRODUCT_WHOLESALE, 3, "1.35", 15);

        seedWarehouseDrafts();
        seedWarehouseTransfers();
        seedWarehouseAdjustments();
        seedWarehouseMinimums();
    }

    private String warehouseNumber(String prefix, String sequence) {
        return "%s-%d-%s".formatted(prefix, TODAY.getYear(), sequence);
    }

    private void seedWarehouseOutput(
            String key,
            String number,
            LocalDate date,
            String destination,
            String concept,
            UUID productId,
            int quantity,
            String saleUnitPrice,
            int daysAgo) {
        seedWarehouseOutput(key, WAREHOUSE, number, date, destination, concept,
                productId, quantity, saleUnitPrice, daysAgo);
    }

    private void seedWarehouseOutput(
            String key,
            UUID warehouseId,
            String number,
            LocalDate date,
            String destination,
            String concept,
            UUID productId,
            int quantity,
            String saleUnitPrice,
            int daysAgo) {
        UUID outputId = id(key);
        Timestamp occurredAt = ts(NOW.minusSeconds(daysAgo * 86_400L));
        String movementKey = key.equals("warehouse-output") ? "stock-move-output" : key + "-movement";
        jdbc.update("""
                insert into salida_almacen
                    (id, tienda_id, almacen_id, numero, fecha, estado, destino, concepto,
                     creada_por, confirmada_por, confirmada_en)
                values (?, ?, ?, ?, ?, 'CONFIRMADA', ?, ?, ?, ?, ?)
                on conflict (id) do update
                set almacen_id = excluded.almacen_id,
                    fecha = excluded.fecha,
                    destino = excluded.destino,
                    concepto = excluded.concepto
                """, outputId, STORE, warehouseId, number, date, destination, concept,
                USER, USER, occurredAt);
        jdbc.update("""
                insert into salida_almacen_linea
                    (id, salida_id, producto_id, cantidad, precio_unitario_venta)
                values (?, ?, ?, ?, ?)
                on conflict (id) do update
                set producto_id = excluded.producto_id,
                    cantidad = excluded.cantidad,
                    precio_unitario_venta = excluded.precio_unitario_venta
                """, id(key + "-line"), outputId, productId, quantity, new BigDecimal(saleUnitPrice));
        jdbc.update("""
                insert into movimiento_stock
                    (id, producto_id, almacen_id, usuario_id, salida_almacen_id, tipo, cantidad, creado_en)
                values (?, ?, ?, ?, ?, 'SALIDA_ALMACEN', ?, ?)
                on conflict (id) do update
                set producto_id = excluded.producto_id,
                    almacen_id = excluded.almacen_id,
                    cantidad = excluded.cantidad,
                    creado_en = excluded.creado_en
                """, id(movementKey), productId, warehouseId, USER, outputId,
                BigDecimal.valueOf(-quantity), occurredAt);
    }

    private void seedWarehouseInput(
            String key,
            String number,
            LocalDate date,
            String origin,
            String concept,
            UUID productId,
            int quantity,
            String purchaseUnitPrice,
            int daysAgo) {
        seedWarehouseInput(key, WAREHOUSE, number, date, origin, concept,
                productId, quantity, purchaseUnitPrice, daysAgo);
    }

    private void seedWarehouseInput(
            String key,
            UUID warehouseId,
            String number,
            LocalDate date,
            String origin,
            String concept,
            UUID productId,
            int quantity,
            String purchaseUnitPrice,
            int daysAgo) {
        UUID inputId = id(key);
        Timestamp occurredAt = ts(NOW.minusSeconds(daysAgo * 86_400L));
        jdbc.update("""
                insert into entrada_almacen
                    (id, tienda_id, almacen_id, proveedor_id, numero, fecha, estado, origen,
                     concepto, creada_por, confirmada_por, confirmada_en)
                values (?, ?, ?, ?, ?, ?, 'CONFIRMADA', ?, ?, ?, ?, ?)
                on conflict (id) do update
                set almacen_id = excluded.almacen_id,
                    fecha = excluded.fecha,
                    proveedor_id = excluded.proveedor_id,
                    origen = excluded.origen,
                    concepto = excluded.concepto
                """, inputId, STORE, warehouseId, SUPPLIER, number, date, origin, concept,
                USER, USER, occurredAt);
        jdbc.update("""
                insert into entrada_almacen_linea
                    (id, entrada_id, producto_id, cantidad, precio_unitario_compra)
                values (?, ?, ?, ?, ?)
                on conflict (id) do update
                set producto_id = excluded.producto_id,
                    cantidad = excluded.cantidad,
                    precio_unitario_compra = excluded.precio_unitario_compra
                """, id(key + "-line"), inputId, productId, quantity, new BigDecimal(purchaseUnitPrice));
        jdbc.update("""
                insert into movimiento_stock
                    (id, producto_id, almacen_id, usuario_id, entrada_almacen_id, tipo, cantidad, creado_en)
                values (?, ?, ?, ?, ?, 'ENTRADA_ALMACEN', ?, ?)
                on conflict (id) do update
                set producto_id = excluded.producto_id,
                    almacen_id = excluded.almacen_id,
                    cantidad = excluded.cantidad,
                    creado_en = excluded.creado_en
                """, id(key + "-movement"), productId, warehouseId, USER, inputId,
                BigDecimal.valueOf(quantity), occurredAt);
    }

    private void seedWarehouseDrafts() {
        UUID inputId = id("warehouse-input-draft-reserve");
        jdbc.update("""
                insert into entrada_almacen
                    (id, tienda_id, almacen_id, proveedor_id, fecha, estado, origen,
                     concepto, creada_por)
                values (?, ?, ?, ?, ?, 'BORRADOR', 'PEDIDO PENDIENTE',
                        'Borrador para comprobar la edicion antes de confirmar', ?)
                on conflict (id) do update
                set almacen_id = excluded.almacen_id,
                    fecha = excluded.fecha,
                    concepto = excluded.concepto
                """, inputId, STORE, WAREHOUSE_RESERVE, SUPPLIER, TODAY.plusDays(1), USER);
        jdbc.update("""
                insert into entrada_almacen_linea
                    (id, entrada_id, producto_id, cantidad, precio_unitario_compra)
                values (?, ?, ?, 25, 1.20)
                on conflict (id) do update
                set producto_id = excluded.producto_id,
                    cantidad = excluded.cantidad,
                    precio_unitario_compra = excluded.precio_unitario_compra
                """, id("warehouse-input-draft-reserve-line"), inputId, PRODUCT_B);

        UUID outputId = id("warehouse-output-draft-showroom");
        jdbc.update("""
                insert into salida_almacen
                    (id, tienda_id, almacen_id, fecha, estado, destino, concepto, creada_por)
                values (?, ?, ?, ?, 'BORRADOR', 'ACCION COMERCIAL',
                        'Borrador para preparar muestras de la proxima campana', ?)
                on conflict (id) do update
                set almacen_id = excluded.almacen_id,
                    fecha = excluded.fecha,
                    concepto = excluded.concepto
                """, outputId, STORE, WAREHOUSE_SHOWROOM, TODAY.plusDays(2), USER);
        jdbc.update("""
                insert into salida_almacen_linea
                    (id, salida_id, producto_id, cantidad, precio_unitario_venta)
                values (?, ?, ?, 4, 3.00)
                on conflict (id) do update
                set producto_id = excluded.producto_id,
                    cantidad = excluded.cantidad,
                    precio_unitario_venta = excluded.precio_unitario_venta
                """, id("warehouse-output-draft-showroom-line"), outputId, PRODUCT_OFFER_DISCOUNT);
    }

    private void seedWarehouseTransfers() {
        seedWarehouseTransfer("warehouse-transfer-general-reserve", PRODUCT_A,
                WAREHOUSE, WAREHOUSE_RESERVE, 15, 2);
        seedWarehouseTransfer("warehouse-transfer-reserve-showroom", PRODUCT_B,
                WAREHOUSE_RESERVE, WAREHOUSE_SHOWROOM, 20, 4);
        seedWarehouseTransfer("warehouse-transfer-showroom-quarantine", PRODUCT_OFFER,
                WAREHOUSE_SHOWROOM, WAREHOUSE_QUARANTINE, 3, 7);
        seedWarehouseTransfer("warehouse-transfer-quarantine-reserve", PRODUCT_WHOLESALE,
                WAREHOUSE_QUARANTINE, WAREHOUSE_RESERVE, 2, 13);
    }

    private void seedWarehouseTransfer(
            String key,
            UUID productId,
            UUID sourceWarehouseId,
            UUID targetWarehouseId,
            int quantity,
            int daysAgo) {
        UUID transferId = id(key);
        Timestamp occurredAt = ts(NOW.minusSeconds(daysAgo * 86_400L));
        seedWarehouseMovement(key + "-out", productId, sourceWarehouseId,
                "TRANSFERENCIA_SALIDA", BigDecimal.valueOf(-quantity),
                "Traspaso interno entre almacenes", transferId, occurredAt);
        seedWarehouseMovement(key + "-in", productId, targetWarehouseId,
                "TRANSFERENCIA_ENTRADA", BigDecimal.valueOf(quantity),
                "Traspaso interno entre almacenes", transferId, occurredAt);
    }

    private void seedWarehouseAdjustments() {
        seedWarehouseMovement("warehouse-adjustment-reserve-positive", PRODUCT_MEMBER,
                WAREHOUSE_RESERVE, "AJUSTE", new BigDecimal("4.000"),
                "Sobrante detectado en recuento", null, ts(NOW.minusSeconds(3 * 86_400L)));
        seedWarehouseMovement("warehouse-adjustment-showroom-negative", PRODUCT_NO_DISCOUNT,
                WAREHOUSE_SHOWROOM, "AJUSTE", new BigDecimal("-2.000"),
                "Regularizacion por diferencia de inventario", null, ts(NOW.minusSeconds(6 * 86_400L)));
        seedWarehouseMovement("warehouse-adjustment-quarantine-positive", PRODUCT_OFFER_DISCOUNT,
                WAREHOUSE_QUARANTINE, "AJUSTE", new BigDecimal("1.000"),
                "Unidad localizada durante la revision", null, ts(NOW.minusSeconds(10 * 86_400L)));
    }

    private void seedWarehouseMovement(
            String key,
            UUID productId,
            UUID warehouseId,
            String type,
            BigDecimal quantity,
            String reason,
            UUID transferId,
            Timestamp occurredAt) {
        jdbc.update("""
                insert into movimiento_stock
                    (id, producto_id, almacen_id, usuario_id, tipo, cantidad, motivo,
                     transferencia_id, creado_en)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (id) do update
                set producto_id = excluded.producto_id,
                    almacen_id = excluded.almacen_id,
                    tipo = excluded.tipo,
                    cantidad = excluded.cantidad,
                    motivo = excluded.motivo,
                    transferencia_id = excluded.transferencia_id,
                    creado_en = excluded.creado_en
                """, id(key), productId, warehouseId, USER, type, quantity, reason,
                transferId, occurredAt);
    }

    private void seedWarehouseMinimums() {
        seedWarehouseMinimum("warehouse-minimum-general-cafe", PRODUCT_A, WAREHOUSE, "20.000");
        seedWarehouseMinimum("warehouse-minimum-reserve-cafe", PRODUCT_A, WAREHOUSE_RESERVE, "35.000");
        seedWarehouseMinimum("warehouse-minimum-reserve-water", PRODUCT_B, WAREHOUSE_RESERVE, "50.000");
        seedWarehouseMinimum("warehouse-minimum-showroom-member", PRODUCT_MEMBER, WAREHOUSE_SHOWROOM, "8.000");
        seedWarehouseMinimum("warehouse-minimum-showroom-offer", PRODUCT_OFFER, WAREHOUSE_SHOWROOM, "10.000");
        seedWarehouseMinimum("warehouse-minimum-quarantine-offer", PRODUCT_OFFER_DISCOUNT,
                WAREHOUSE_QUARANTINE, "1.000");
    }

    private void seedWarehouseMinimum(
            String key, UUID productId, UUID warehouseId, String quantity) {
        jdbc.update("""
                insert into stock_minimo_almacen
                    (id, tienda_id, producto_id, almacen_id, cantidad_minima)
                values (?, ?, ?, ?, ?)
                on conflict (producto_id, almacen_id) do update
                set cantidad_minima = excluded.cantidad_minima
                """, id(key), STORE, productId, warehouseId, new BigDecimal(quantity));
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(("tpv-erp-dev:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }
}
