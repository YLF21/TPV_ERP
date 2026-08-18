package com.tpverp.backend.excel;

import com.tpverp.backend.inventory.StockTopSalesRow;
import com.tpverp.backend.inventory.StockTopSalesService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockExcelExportService {

    private static final int FETCH_SIZE = 1_000;
    private static final int ROW_WINDOW = 200;
    private static final int MAX_EXCEL_ROWS = 1_048_576;
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final Set<String> ALLOWED_COLUMNS = Set.of(
            "ranking", "soldUnits", "amount", "currentStock",
            "code", "barcode", "name", "type", "discount", "supplier",
            "family", "subfamily", "tax", "taxIncluded", "packageQuantity",
            "purchasePrice", "salePrice", "memberPrice", "wholesalePrice",
            "offerPrice", "offerActive", "offerFrom", "offerUntil", "warehouse",
            "localStock", "totalStock", "stockMin", "stockMax", "status",
            "promotion", "promotionType", "promotionStatus", "promotionValidity", "stock");
    private static final Set<String> PURCHASE_COLUMNS = Set.of("purchasePrice");
    private static final Set<String> TOP_SALES_COLUMNS = Set.of(
            "ranking", "code", "barcode", "name", "family", "subfamily", "supplier",
            "soldUnits", "amount", "currentStock", "warehouse");
    private static final Set<String> PROMOTION_COLUMNS = Set.of(
            "code", "barcode", "name", "family", "subfamily", "promotion",
            "promotionType", "promotionStatus", "promotionValidity", "warehouse", "stock");

    private final JdbcTemplate jdbc;
    private final StockTopSalesService topSales;
    private final Map<UUID, ExportJob> jobs = new ConcurrentHashMap<>();
    private final Path directory;

    public StockExcelExportService(JdbcTemplate jdbc, StockTopSalesService topSales) {
        this.jdbc = jdbc;
        this.topSales = topSales;
        this.directory = Path.of(System.getProperty("java.io.tmpdir"),
                "tpv-erp", "stock-exports").toAbsolutePath().normalize();
    }

    public JobView create(
            UUID storeId,
            String owner,
            boolean includePurchaseFields,
            ExportRequest request) {
        cleanupExpired();
        var normalized = normalize(request, includePurchaseFields);
        var normalizedOwner = owner(owner);
        var existing = jobs.values().stream()
                .filter(job -> job.storeId.equals(storeId)
                        && job.owner.equals(normalizedOwner)
                        && (job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING))
                .findFirst();
        if (existing.isPresent()) return existing.orElseThrow().view();
        var job = new ExportJob(UUID.randomUUID(), storeId, normalizedOwner,
                includePurchaseFields, normalized, Instant.now());
        jobs.put(job.id, job);
        return job.view();
    }

    @Transactional(readOnly = true)
    public void run(UUID jobId) {
        var job = require(jobId);
        if (!job.start()) return;
        Path output = null;
        try {
            Files.createDirectories(directory);
            output = Files.createTempFile(directory, "stock-", ".xlsx");
            writeWorkbook(job, output);
            job.complete(output, Files.size(output));
        } catch (RuntimeException | IOException failure) {
            delete(output);
            job.fail(failure.getMessage());
        }
    }

    public JobView status(UUID jobId, UUID storeId, String owner) {
        var job = authorized(jobId, storeId, owner);
        return job.view();
    }

    public ExportFile file(UUID jobId, UUID storeId, String owner) {
        var job = authorized(jobId, storeId, owner);
        if (job.status != JobStatus.COMPLETED || job.file == null
                || !Files.isRegularFile(job.file)) {
            throw new IllegalStateException("stock_excel_export_not_ready");
        }
        return new ExportFile(job.file, job.fileSize,
                "stock-" + LocalDate.now() + ".xlsx");
    }

    private void writeWorkbook(ExportJob job, Path output) throws IOException {
        var workbook = new SXSSFWorkbook(ROW_WINDOW);
        workbook.setCompressTempFiles(false);
        try (workbook; var stream = Files.newOutputStream(output)) {
            var sheet = workbook.createSheet("Stock");
            var styles = new Styles(workbook);
            writeHeader(sheet, job.request.columns(), styles.header());
            if (isTopSales(job.request)) {
                writeTopSalesRows(sheet, job, styles);
            } else {
                writeInventoryRows(sheet, job, styles);
            }
            finish(sheet, job.request.columns(), Math.toIntExact(job.processedRows.get()));
            workbook.write(stream);
        } finally {
            workbook.dispose();
        }
    }

    private void writeInventoryRows(Sheet sheet, ExportJob job, Styles styles) {
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    exportSql(job.request),
                    ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY);
            statement.setFetchSize(FETCH_SIZE);
            bind(statement, job);
            return statement;
        }, result -> {
            int rowNumber = 1;
            while (result.next()) {
                if (rowNumber >= MAX_EXCEL_ROWS) {
                    throw new IllegalStateException("stock_excel_export_row_limit");
                }
                writeRow(sheet.createRow(rowNumber++), result,
                        job.request.columns(), job.request.language(), styles);
                job.processedRows.incrementAndGet();
            }
            return null;
        });
    }

    private void writeTopSalesRows(Sheet sheet, ExportJob job, Styles styles) {
        var request = job.request;
        var rows = topSales.topSales(job.storeId, request.dateFrom(), request.dateTo(), request.warehouseId())
                .stream()
                .filter(row -> topSalesMatches(row, request))
                .sorted(topSalesComparator(request))
                .toList();
        for (int index = 0; index < rows.size(); index++) {
            writeTopSalesRow(sheet.createRow(index + 1), rows.get(index), index,
                    request.columns(), styles);
            job.processedRows.incrementAndGet();
        }
    }

    private static void bind(PreparedStatement statement, ExportJob job) throws SQLException {
        var request = job.request;
        bind(statement, 1, job.storeId, Types.OTHER);
        bind(statement, 2, request.warehouseId(), Types.OTHER);
        bind(statement, 3, search(request.search()), Types.VARCHAR);
        bind(statement, 4, optionalUpper(request.productType()), Types.VARCHAR);
        bind(statement, 5, priceUseMode(request), Types.VARCHAR);
        bind(statement, 6, discountType(request), Types.VARCHAR);
        statement.setBoolean(7, "OFFERS".equals(optionalUpper(request.view())));
        bind(statement, 8, request.familyId(), Types.OTHER);
        bind(statement, 9, request.taxId(), Types.OTHER);
        bind(statement, 10, request.offerActive(), Types.BOOLEAN);
        bind(statement, 11, normalizedStockStatus(request.stockStatus()), Types.VARCHAR);
        bind(statement, 12, request.supplierId(), Types.OTHER);
        statement.setBoolean(13, "PROMOTIONS".equals(optionalUpper(request.view())));
    }

    private static void bind(
            PreparedStatement statement, int index, Object value, int sqlType)
            throws SQLException {
        if (value == null) statement.setNull(index, sqlType);
        else statement.setObject(index, value);
    }

    private static String exportSql(ExportRequest request) {
        String expression = sortExpression(request.sortBy());
        String direction = "desc".equalsIgnoreCase(request.sortDirection()) ? "desc" : "asc";
        return """
                with export_filter as (
                    select cast(? as uuid) store_id,
                           cast(? as uuid) warehouse_id,
                           cast(? as text) search,
                           cast(? as varchar) product_type,
                           cast(? as varchar) price_use_mode,
                           cast(? as varchar) discount_type,
                           cast(? as boolean) offers_only,
                           cast(? as uuid) family_id,
                           cast(? as uuid) tax_id,
                           cast(? as boolean) offer_active,
                           cast(? as varchar) stock_status,
                           cast(? as uuid) supplier_id,
                           cast(? as boolean) promotions_only
                )
                select product.id,
                       code.valor as code,
                       barcode.valor as barcode,
                       product.nombre as name,
                       product.product_type,
                       product.price_use_mode,
                       product.discount_type,
                       supplier.razon_social as supplier_name,
                       family.nombre as family_name,
                       subfamily.nombre as subfamily_name,
                       tax.porcentaje as tax_percentage,
                       product.impuestos_incluidos as taxes_included,
                       product.package_quantity,
                       product.precio_compra as purchase_price,
                       sale_price.importe as sale_price,
                       member_price.importe as member_price,
                       wholesale_price.importe as wholesale_price,
                       offer_price.importe as offer_price,
                       product.oferta_activa as offer_active,
                       product.oferta_desde as offer_from,
                       product.oferta_hasta as offer_until,
                       coalesce(warehouse.nombre, 'TOTAL') as warehouse_name,
                       stock.local_stock,
                       stock.total_stock,
                       product.stock_min,
                       product.stock_max,
                       product.activo as active,
                       promotion.names as promotion_names,
                       promotion.types as promotion_types,
                       promotion.statuses as promotion_statuses,
                       promotion.validity as promotion_validity
                from producto product
                cross join export_filter filter
                join tienda store on store.id = product.tienda_id
                left join producto_identificador code
                  on code.producto_id = product.id and code.tipo = 'CODIGO'
                left join producto_identificador barcode
                  on barcode.producto_id = product.id and barcode.tipo = 'CODIGO_BARRAS'
                left join familia family on family.id = product.familia_id
                left join subfamilia subfamily on subfamily.id = product.subfamilia_id
                left join impuesto_tienda tax on tax.id = product.impuesto_id
                left join producto_precio sale_price
                  on sale_price.producto_id = product.id and sale_price.tarifa = 'VENTA'
                left join producto_precio member_price
                  on member_price.producto_id = product.id and member_price.tarifa = 'MEMBER'
                left join producto_precio wholesale_price
                  on wholesale_price.producto_id = product.id and wholesale_price.tarifa = 'MAYORISTA'
                left join producto_precio offer_price
                  on offer_price.producto_id = product.id and offer_price.tarifa = 'OFERTA'
                left join almacen warehouse on warehouse.id = filter.warehouse_id
                left join lateral (
                    select selected_supplier.razon_social
                    from producto_proveedor product_supplier
                    join proveedor selected_supplier on selected_supplier.id = product_supplier.proveedor_id
                    where product_supplier.producto_id = product.id
                    order by product_supplier.ultimo_proveedor desc,
                             product_supplier.principal desc,
                             lower(selected_supplier.razon_social),
                             product_supplier.id
                    limit 1
                ) supplier on true
                left join lateral (
                    select coalesce(sum(level.cantidad), 0) as total_stock,
                           coalesce(sum(level.cantidad) filter (
                               where filter.warehouse_id is null
                                  or level.almacen_id = filter.warehouse_id
                           ), 0) as local_stock
                    from existencia level
                    where level.producto_id = product.id
                ) stock on true
                left join lateral (
                    select string_agg(distinct selected_promotion.nombre, '; ') as names,
                           string_agg(distinct selected_promotion.tipo, '; ') as types,
                           string_agg(distinct selected_promotion.estado, '; ') as statuses,
                           string_agg(distinct concat(selected_promotion.fecha_inicio, ' / ',
                               coalesce(selected_promotion.fecha_fin::text, '-')), '; ') as validity
                    from promocion selected_promotion
                    where selected_promotion.empresa_id = store.empresa_id
                      and selected_promotion.estado = 'ACTIVE'
                      and (
                        selected_promotion.ambito = 'SALE'
                        or exists (
                          select 1 from promocion_objetivo target
                          where target.promocion_id = selected_promotion.id
                            and ((target.tipo = 'PRODUCT' and target.objetivo_id = product.id)
                              or (target.tipo = 'FAMILY' and target.objetivo_id = product.familia_id)
                              or (target.tipo = 'SUBFAMILY' and target.objetivo_id = product.subfamilia_id))
                        )
                      )
                ) promotion on true
                where product.tienda_id = filter.store_id
                  and (filter.search is null
                    or lower(product.nombre) like filter.search
                    or lower(coalesce(product.descripcion, '')) like filter.search
                    or lower(coalesce(product.comments, '')) like filter.search
                    or exists (
                      select 1 from producto_identificador identifier
                      where identifier.producto_id = product.id
                        and lower(identifier.valor) like filter.search
                    ))
                  and (filter.product_type is null or product.product_type = filter.product_type)
                  and (filter.price_use_mode is null or product.price_use_mode = filter.price_use_mode)
                  and (filter.discount_type is null or product.discount_type = filter.discount_type)
                  and (not filter.offers_only
                    or product.price_use_mode in ('OFFER_PRICE', 'OFFER_DISCOUNT')
                    or product.discount_type = 'DISCOUNT_PRICE')
                  and (filter.family_id is null
                    or product.familia_id = filter.family_id
                    or product.subfamilia_id = filter.family_id)
                  and (filter.tax_id is null or product.impuesto_id = filter.tax_id)
                  and (filter.offer_active is null or product.oferta_activa = filter.offer_active)
                  and (filter.supplier_id is null or exists (
                    select 1 from producto_proveedor filtered_supplier
                    where filtered_supplier.producto_id = product.id
                      and filtered_supplier.proveedor_id = filter.supplier_id
                  ))
                  and (filter.stock_status is null
                    or (filter.stock_status = 'INACTIVE' and not product.activo)
                    or (filter.stock_status = 'EMPTY' and product.activo and stock.local_stock <= 0)
                    or (filter.stock_status = 'LOW' and product.activo and stock.local_stock > 0 and stock.local_stock <= 5)
                    or (filter.stock_status = 'OK' and product.activo and stock.local_stock > 5))
                  and (not filter.promotions_only or promotion.names is not null)
                order by (""" + expression + " is null), " + expression + " " + direction
                + ", product.id " + direction;
    }

    private static String sortExpression(String value) {
        return switch (value == null ? "name" : value) {
            case "code" -> "lower(code.valor)";
            case "barcode" -> "lower(barcode.valor)";
            case "name" -> "lower(product.nombre)";
            case "type" -> "product.product_type";
            case "discount" -> "product.price_use_mode";
            case "supplier" -> "lower(supplier.razon_social)";
            case "family" -> "lower(family.nombre)";
            case "subfamily" -> "lower(subfamily.nombre)";
            case "tax" -> "tax.porcentaje";
            case "taxIncluded" -> "product.impuestos_incluidos";
            case "packageQuantity" -> "product.package_quantity";
            case "purchasePrice" -> "product.precio_compra";
            case "salePrice" -> "sale_price.importe";
            case "memberPrice" -> "member_price.importe";
            case "wholesalePrice" -> "wholesale_price.importe";
            case "offerPrice" -> "offer_price.importe";
            case "offerActive" -> "product.oferta_activa";
            case "offerFrom" -> "product.oferta_desde";
            case "offerUntil" -> "product.oferta_hasta";
            case "localStock" -> "stock.local_stock";
            case "totalStock" -> "stock.total_stock";
            case "stockMin" -> "product.stock_min";
            case "stockMax" -> "product.stock_max";
            case "status" -> "case when not product.activo then 0 when stock.local_stock <= 0 then 1 when stock.local_stock <= 5 then 2 else 3 end";
            case "promotion" -> "lower(promotion.names)";
            case "promotionType" -> "lower(promotion.types)";
            case "promotionStatus" -> "lower(promotion.statuses)";
            case "promotionValidity" -> "lower(promotion.validity)";
            case "stock" -> "stock.local_stock";
            default -> throw new IllegalArgumentException("stock_excel_export_sort_invalid");
        };
    }

    private static void writeHeader(
            Sheet sheet, List<ExportColumn> columns, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int index = 0; index < columns.size(); index++) {
            var cell = row.createCell(index);
            cell.setCellValue(columns.get(index).label());
            cell.setCellStyle(style);
        }
        sheet.createFreezePane(0, 1);
    }

    private static void writeRow(
            Row row,
            ResultSet result,
            List<ExportColumn> columns,
            String language,
            Styles styles) throws SQLException {
        for (int index = 0; index < columns.size(); index++) {
            String key = columns.get(index).key();
            Cell cell = row.createCell(index);
            switch (key) {
                case "code" -> text(cell, result.getString("code"));
                case "barcode" -> text(cell, result.getString("barcode"));
                case "name" -> text(cell, result.getString("name"));
                case "type" -> text(cell, productType(result.getString("product_type"), language));
                case "discount" -> text(cell, discount(result, language));
                case "supplier" -> text(cell, result.getString("supplier_name"));
                case "family" -> text(cell, result.getString("family_name"));
                case "subfamily" -> text(cell, result.getString("subfamily_name"));
                case "tax" -> percentage(cell, result.getBigDecimal("tax_percentage"));
                case "taxIncluded" -> text(cell, yesNo(result.getBoolean("taxes_included"), language));
                case "packageQuantity" -> decimal(cell, result.getBigDecimal("package_quantity"), styles.quantity());
                case "purchasePrice" -> decimal(cell, result.getBigDecimal("purchase_price"), styles.money());
                case "salePrice" -> decimal(cell, result.getBigDecimal("sale_price"), styles.money());
                case "memberPrice" -> decimal(cell, result.getBigDecimal("member_price"), styles.money());
                case "wholesalePrice" -> decimal(cell, result.getBigDecimal("wholesale_price"), styles.money());
                case "offerPrice" -> decimal(cell, result.getBigDecimal("offer_price"), styles.money());
                case "offerActive" -> text(cell, yesNo(result.getBoolean("offer_active"), language));
                case "offerFrom" -> date(cell, result.getObject("offer_from", LocalDate.class), styles.date());
                case "offerUntil" -> date(cell, result.getObject("offer_until", LocalDate.class), styles.date());
                case "warehouse" -> text(cell, result.getString("warehouse_name"));
                case "localStock" -> decimal(cell, result.getBigDecimal("local_stock"), styles.quantity());
                case "totalStock" -> decimal(cell, result.getBigDecimal("total_stock"), styles.quantity());
                case "stockMin" -> decimal(cell, result.getBigDecimal("stock_min"), styles.quantity());
                case "stockMax" -> decimal(cell, result.getBigDecimal("stock_max"), styles.quantity());
                case "status" -> text(cell, stockStatus(
                        result.getBoolean("active"), result.getBigDecimal("local_stock"), language));
                case "promotion" -> text(cell, result.getString("promotion_names"));
                case "promotionType" -> text(cell, result.getString("promotion_types"));
                case "promotionStatus" -> text(cell, result.getString("promotion_statuses"));
                case "promotionValidity" -> text(cell, result.getString("promotion_validity"));
                case "stock" -> decimal(cell, result.getBigDecimal("local_stock"), styles.quantity());
                default -> throw new IllegalArgumentException("stock_excel_export_column_invalid");
            }
        }
    }

    private static void writeTopSalesRow(
            Row row,
            StockTopSalesRow value,
            int index,
            List<ExportColumn> columns,
            Styles styles) {
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            var cell = row.createCell(columnIndex);
            switch (columns.get(columnIndex).key()) {
                case "ranking" -> decimal(cell, BigDecimal.valueOf(index + 1L), styles.quantity());
                case "code" -> text(cell, value.code());
                case "barcode" -> text(cell, value.barcode());
                case "name" -> text(cell, value.name());
                case "family" -> text(cell, value.familyName());
                case "subfamily" -> text(cell, value.subfamilyName());
                case "supplier" -> text(cell, value.suppliers().stream()
                        .map(supplier -> supplier.supplierName())
                        .filter(name -> name != null && !name.isBlank() && !"-".equals(name))
                        .distinct().reduce((left, right) -> left + "; " + right).orElse("-"));
                case "soldUnits" -> decimal(cell, value.soldQuantity(), styles.quantity());
                case "amount" -> decimal(cell, value.netAmount(), styles.money());
                case "currentStock" -> decimal(cell, value.currentStock(), styles.quantity());
                case "warehouse" -> text(cell, value.warehouseName());
                default -> throw new IllegalArgumentException("stock_excel_export_column_invalid");
            }
        }
    }

    private static boolean topSalesMatches(StockTopSalesRow row, ExportRequest request) {
        String suppliers = row.suppliers().stream()
                .map(supplier -> supplier.supplierCode() + " " + supplier.supplierName())
                .reduce((left, right) -> left + " " + right).orElse("");
        return contains(row.familyName() + " " + row.familyId(), request.topSalesFamily())
                && contains(row.subfamilyName() + " " + row.subfamilyId(), request.topSalesSubfamily())
                && contains(suppliers, request.topSalesSupplier())
                && contains(String.join(" ", row.code(), row.barcode(), row.name(), row.familyName(),
                        row.subfamilyName(), suppliers, row.warehouseName()), request.search());
    }

    private static boolean contains(String value, String expected) {
        return expected == null || expected.isBlank()
                || value.toLowerCase(Locale.ROOT).contains(expected.trim().toLowerCase(Locale.ROOT));
    }

    private static Comparator<StockTopSalesRow> topSalesComparator(ExportRequest request) {
        Comparator<StockTopSalesRow> comparator = switch (request.sortBy()) {
            case "code" -> Comparator.comparing(StockTopSalesRow::code, String.CASE_INSENSITIVE_ORDER);
            case "barcode" -> Comparator.comparing(StockTopSalesRow::barcode, String.CASE_INSENSITIVE_ORDER);
            case "name" -> Comparator.comparing(StockTopSalesRow::name, String.CASE_INSENSITIVE_ORDER);
            case "family" -> Comparator.comparing(StockTopSalesRow::familyName, String.CASE_INSENSITIVE_ORDER);
            case "subfamily" -> Comparator.comparing(StockTopSalesRow::subfamilyName, String.CASE_INSENSITIVE_ORDER);
            case "supplier" -> Comparator.comparing(row -> row.suppliers().stream()
                    .map(supplier -> supplier.supplierName()).reduce((left, right) -> left + " " + right).orElse(""),
                    String.CASE_INSENSITIVE_ORDER);
            case "amount" -> Comparator.comparing(StockTopSalesRow::netAmount);
            case "currentStock" -> Comparator.comparing(StockTopSalesRow::currentStock);
            case "warehouse" -> Comparator.comparing(StockTopSalesRow::warehouseName, String.CASE_INSENSITIVE_ORDER);
            case "ranking" -> Comparator.comparing(StockTopSalesRow::soldQuantity).reversed();
            case "soldUnits" -> Comparator.comparing(StockTopSalesRow::soldQuantity);
            default -> throw new IllegalArgumentException("stock_excel_export_sort_invalid");
        };
        if ("desc".equals(request.sortDirection())) {
            comparator = comparator.reversed();
        }
        return comparator.thenComparing(StockTopSalesRow::name, String.CASE_INSENSITIVE_ORDER);
    }

    private static void finish(Sheet sheet, List<ExportColumn> columns, int rows) {
        if (rows > 0) {
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0, rows, 0, columns.size() - 1));
        }
        for (int index = 0; index < columns.size(); index++) {
            String key = columns.get(index).key();
            int characters = switch (key) {
                case "name", "supplier", "promotion" -> 34;
                case "family", "subfamily" -> 24;
                case "code", "barcode" -> 18;
                default -> 15;
            };
            sheet.setColumnWidth(index, characters * 256);
        }
    }

    private static void text(Cell cell, String value) {
        cell.setCellValue(value == null || value.isBlank() ? "-" : value);
    }

    private static void decimal(Cell cell, BigDecimal value, CellStyle style) {
        if (value == null) return;
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void percentage(Cell cell, BigDecimal value) {
        if (value != null) cell.setCellValue(value.stripTrailingZeros().toPlainString() + "%");
    }

    private static void date(Cell cell, LocalDate value, CellStyle style) {
        if (value == null) return;
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static String discount(ResultSet result, String language) throws SQLException {
        String discountType = result.getString("discount_type");
        String value = "NONE".equals(discountType)
                ? "NONE" : result.getString("price_use_mode");
        return translate(value, language, Map.of(
                "NONE", List.of("Sin descuento", "No discount", "无折扣"),
                "NORMAL", List.of("Normal", "Normal", "普通"),
                "MEMBER_PRICE", List.of("Precio socio", "Member price", "会员价"),
                "OFFER_PRICE", List.of("Precio oferta", "Offer price", "优惠价"),
                "OFFER_DISCOUNT", List.of("Descuento oferta", "Offer discount", "优惠折扣")));
    }

    private static String productType(String value, String language) {
        return translate(value, language, Map.of(
                "UNIT", List.of("Unidad", "Unit", "件"),
                "WEIGHT", List.of("Peso", "Weight", "称重"),
                "SERVICE", List.of("Servicio", "Service", "服务")));
    }

    private static String stockStatus(boolean active, BigDecimal stock, String language) {
        String key = !active ? "INACTIVE"
                : stock == null || stock.signum() <= 0 ? "EMPTY"
                : stock.compareTo(BigDecimal.valueOf(5)) <= 0 ? "LOW" : "OK";
        return translate(key, language, Map.of(
                "INACTIVE", List.of("Desactivado", "Inactive", "已停用"),
                "EMPTY", List.of("Sin stock", "Out of stock", "无库存"),
                "LOW", List.of("Stock bajo", "Low stock", "库存低"),
                "OK", List.of("Correcto", "Correct", "正常")));
    }

    private static String yesNo(boolean value, String language) {
        int index = languageIndex(language);
        return (value ? List.of("Sí", "Yes", "是") : List.of("No", "No", "否")).get(index);
    }

    private static String translate(
            String value, String language, Map<String, List<String>> translations) {
        if (value == null) return "-";
        return translations.getOrDefault(value, List.of(value, value, value))
                .get(languageIndex(language));
    }

    private static int languageIndex(String language) {
        return "en".equals(language) ? 1 : "zh".equals(language) ? 2 : 0;
    }

    private ExportRequest normalize(ExportRequest request, boolean includePurchaseFields) {
        if (request == null) throw new IllegalArgumentException("stock_excel_export_request_required");
        String view = optionalUpper(request.view());
        Set<String> allowedColumns = "TOP_SALES".equals(view) ? TOP_SALES_COLUMNS
                : "PROMOTIONS".equals(view) ? PROMOTION_COLUMNS : ALLOWED_COLUMNS;
        var keys = new LinkedHashSet<String>();
        var columns = new ArrayList<ExportColumn>();
        for (var column : request.columns() == null ? List.<ExportColumn>of() : request.columns()) {
            if (column == null || !allowedColumns.contains(column.key())
                    || (!includePurchaseFields && PURCHASE_COLUMNS.contains(column.key()))
                    || !keys.add(column.key())) continue;
            String label = column.label() == null || column.label().isBlank()
                    ? column.key() : column.label().trim();
            columns.add(new ExportColumn(column.key(), label.substring(0, Math.min(120, label.length()))));
        }
        if (columns.isEmpty()) throw new IllegalArgumentException("stock_excel_export_columns_required");
        String sortBy = request.sortBy() == null
                ? ("TOP_SALES".equals(view) ? "ranking" : "name")
                : request.sortBy();
        if ("TOP_SALES".equals(view)) {
            topSalesComparator(new ExportRequest(view, null, null, null, null, null, null, null,
                    null, null, sortBy, "asc", "es", LocalDate.now(), LocalDate.now(),
                    null, null, null, List.copyOf(columns)));
            if (request.dateFrom() == null || request.dateTo() == null) {
                throw new IllegalArgumentException("stock_excel_export_dates_required");
            }
        } else {
            sortExpression(sortBy);
        }
        String direction = "desc".equalsIgnoreCase(request.sortDirection()) ? "desc" : "asc";
        return new ExportRequest(
                view, trim(request.search(), 200),
                optionalUpper(request.productType()), optionalUpper(request.priceUseMode()),
                request.familyId(), request.taxId(), request.offerActive(),
                normalizedStockStatus(request.stockStatus()), request.supplierId(),
                request.warehouseId(), sortBy,
                direction, "en".equals(request.language()) || "zh".equals(request.language())
                        ? request.language() : "es",
                request.dateFrom(), request.dateTo(),
                trim(request.topSalesFamily(), 160), trim(request.topSalesSubfamily(), 160),
                trim(request.topSalesSupplier(), 160), List.copyOf(columns));
    }

    private static boolean isTopSales(ExportRequest request) {
        return "TOP_SALES".equals(optionalUpper(request.view()));
    }

    private static String priceUseMode(ExportRequest request) {
        return "MEMBER_PRICE".equals(optionalUpper(request.view()))
                ? "MEMBER_PRICE" : optionalUpper(request.priceUseMode());
    }

    private static String discountType(ExportRequest request) {
        return "NO_DISCOUNT".equals(optionalUpper(request.view())) ? "NONE" : null;
    }

    private static String normalizedStockStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("INACTIVE", "EMPTY", "LOW", "OK").contains(normalized)) {
            throw new IllegalArgumentException("stock_excel_export_status_invalid");
        }
        return normalized;
    }

    private static String optionalUpper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String search(String value) {
        return value == null || value.isBlank()
                ? null : "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(max, trimmed.length()));
    }

    private static String owner(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("stock_excel_export_owner_required");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private ExportJob authorized(UUID id, UUID storeId, String owner) {
        var job = require(id);
        if (!job.storeId.equals(storeId) || !job.owner.equals(owner(owner))) {
            throw new IllegalArgumentException("stock_excel_export_not_found");
        }
        return job;
    }

    private ExportJob require(UUID id) {
        var job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("stock_excel_export_not_found");
        return job;
    }

    private void cleanupExpired() {
        Instant threshold = Instant.now().minus(RETENTION);
        jobs.entrySet().removeIf(entry -> {
            var job = entry.getValue();
            if (job.createdAt.isAfter(threshold)
                    || job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING) return false;
            delete(job.file);
            return true;
        });
    }

    private static void delete(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A later cleanup can retry the temporary file.
        }
    }

    public record ExportRequest(
            String view,
            String search,
            String productType,
            String priceUseMode,
            UUID familyId,
            UUID taxId,
            Boolean offerActive,
            String stockStatus,
            UUID supplierId,
            UUID warehouseId,
            String sortBy,
            String sortDirection,
            String language,
            LocalDate dateFrom,
            LocalDate dateTo,
            String topSalesFamily,
            String topSalesSubfamily,
            String topSalesSupplier,
            List<ExportColumn> columns) {
    }

    public record ExportColumn(String key, String label) {
    }

    public enum JobStatus { QUEUED, RUNNING, COMPLETED, FAILED }

    public record JobView(
            UUID id,
            JobStatus status,
            long processedRows,
            long fileSize,
            String error) {
    }

    public record ExportFile(Path path, long size, String fileName) {
    }

    private static final class ExportJob {
        private final UUID id;
        private final UUID storeId;
        private final String owner;
        private final boolean includePurchaseFields;
        private final ExportRequest request;
        private final Instant createdAt;
        private final AtomicLong processedRows = new AtomicLong();
        private volatile JobStatus status = JobStatus.QUEUED;
        private volatile Path file;
        private volatile long fileSize;
        private volatile String error;

        private ExportJob(
                UUID id, UUID storeId, String owner, boolean includePurchaseFields,
                ExportRequest request, Instant createdAt) {
            this.id = id;
            this.storeId = storeId;
            this.owner = owner;
            this.includePurchaseFields = includePurchaseFields;
            this.request = request;
            this.createdAt = createdAt;
        }

        private synchronized boolean start() {
            if (status != JobStatus.QUEUED) return false;
            status = JobStatus.RUNNING;
            return true;
        }

        private synchronized void complete(Path path, long size) {
            file = path;
            fileSize = size;
            status = JobStatus.COMPLETED;
        }

        private synchronized void fail(String message) {
            error = message == null || message.isBlank()
                    ? "stock_excel_export_failed" : message;
            status = JobStatus.FAILED;
        }

        private JobView view() {
            return new JobView(id, status, processedRows.get(), fileSize, error);
        }
    }

    private record Styles(CellStyle header, CellStyle money, CellStyle quantity, CellStyle date) {
        private Styles(SXSSFWorkbook workbook) {
            this(header(workbook), number(workbook, "#,##0.00 \"€\""),
                    number(workbook, "#,##0"), number(workbook, "yyyy-mm-dd"));
        }

        private static CellStyle header(SXSSFWorkbook workbook) {
            var style = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return style;
        }

        private static CellStyle number(SXSSFWorkbook workbook, String format) {
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat(format));
            return style;
        }
    }
}
