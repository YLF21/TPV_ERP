package com.tpverp.saas.reportexport;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpverp.saas.analytics.ReportStatistics;
import com.tpverp.saas.inventory.InventoryReport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class ReportExportLayout {
    private static final List<String> METRICS = List.of("documents", "eligibleDocuments", "cancelledDocuments",
            "syncEvents", "syncErrors", "stockMovementEvents", "stores", "licenses", "saleDocuments", "returnDocuments");
    private static final List<String> CURRENCY_METRICS = List.of("documents", "amount", "averageAmount",
            "amountPerDay", "contributingStores", "amountPerStore");
    private static final List<String> ARTICLE_METRICS = List.of("quantitySold", "quantityReturned", "netQuantity",
            "salesAmount", "returnsAmount", "netAmount", "saleDocuments", "averageQuantityPerSale", "amountSharePercent");
    private static final List<String> SNAPSHOT = List.of("snapshotId", "installationId", "capturedAt", "completedAt",
            "currentInstallation", "productCount", "activeProductCount", "warehouseCount", "balanceCount",
            "positiveCount", "zeroCount", "negativeCount");
    private static final List<String> STOCK_TOTALS = List.of("stores", "reportedStores", "products", "activeProducts",
            "warehouses", "balances", "positiveCount", "zeroCount", "negativeCount");

    private ReportExportLayout() {}

    static List<String> columns(String resource, String kind, Map<String, Object> metadata) {
        return switch (resource) {
            case "statistics" -> statisticsColumns((ReportStatistics.PeriodPair) metadata.get("totals"));
            case "articles" -> articleColumns();
            case "documents" -> List.of("documentId", "type", "number", "status", "businessDate", "companyId",
                    "companyName", "storeId", "storeName", "currency", "total", "eligible", "linesComplete",
                    "relationshipsComplete");
            case "movements" -> kind.equals("ledger")
                    ? List.of("movementId", "companyId", "companyName", "storeId", "storeName", "province", "warehouseId",
                            "warehouseName", "productId", "productName", "type", "quantity", "occurredAt", "receivedAt")
                    : List.of("key", "label", "comparisonLabel", "currentCount", "comparisonCount");
            case "comparison" -> List.of("productId", "code", "name", "warehouseId", "warehouseName", "status",
                    "previousQuantity", "currentQuantity", "delta", "percent");
            default -> switch (kind) {
                case "overview" -> {
                    var columns = new ArrayList<>(List.of("companyId", "companyName", "storeId", "storeName"));
                    prefixed(columns, "snapshot", SNAPSHOT);
                    yield columns;
                }
                case "provinces" -> {
                    var columns = new ArrayList<>(List.of("province"));
                    prefixed(columns, "totals", STOCK_TOTALS);
                    yield columns;
                }
                case "history" -> SNAPSHOT;
                case "products" -> List.of("productId", "code", "name", "barcode", "secondaryBarcode", "active",
                        "productType", "familyId", "familyName", "subfamilyId", "subfamilyName");
                case "warehouses" -> List.of("warehouseId", "name", "active", "defaultWarehouse");
                case "balances" -> List.of("productId", "code", "name", "productType", "active", "warehouseId",
                        "warehouseName", "quantity");
                default -> throw new IllegalStateException("Tipo de informe de inventario desconocido");
            };
        };
    }

    private static List<String> statisticsColumns(ReportStatistics.PeriodPair totals) {
        var columns = new ArrayList<>(List.of("key", "label", "comparisonLabel"));
        var currencies = new TreeSet<String>();
        totals.current().currencies().forEach(metric -> currencies.add(metric.currency()));
        if (totals.comparison() != null) {
            totals.comparison().currencies().forEach(metric -> currencies.add(metric.currency()));
        }
        for (String side : List.of("current", "comparison")) {
            prefixed(columns, side, METRICS);
            for (String currency : currencies) {
                prefixed(columns, side + ".currencies." + currency, CURRENCY_METRICS);
            }
        }
        for (String currency : currencies) {
            prefixed(columns, "deltas." + currency, List.of("amount", "percent"));
        }
        return columns;
    }

    private static List<String> articleColumns() {
        var columns = new ArrayList<>(List.of("rank", "companyId", "companyName", "storeId", "storeName", "productId",
                "productCode", "productName", "currency"));
        prefixed(columns, "current", ARTICLE_METRICS);
        prefixed(columns, "comparison", ARTICLE_METRICS);
        columns.addAll(List.of("deltaAmount", "deltaPercent", "deltaQuantity"));
        return columns;
    }

    private static void prefixed(List<String> columns, String prefix, List<String> keys) {
        keys.forEach(key -> columns.add(prefix + "." + key));
    }

    static Map<String, Object> stockTotals(InventoryReport.Totals totals) {
        var result = new LinkedHashMap<String, Object>();
        result.put("stores", totals.stores());
        result.put("reportedStores", totals.reportedStores());
        boolean reported = totals.reportedStores() > 0;
        result.put("products", reported ? totals.products() : null);
        result.put("activeProducts", reported ? totals.activeProducts() : null);
        result.put("warehouses", reported ? totals.warehouses() : null);
        result.put("balances", reported ? totals.balances() : null);
        result.put("positiveCount", reported ? totals.positiveCount() : null);
        result.put("zeroCount", reported ? totals.zeroCount() : null);
        result.put("negativeCount", reported ? totals.negativeCount() : null);
        return result;
    }

    static Map<String, Object> flatten(JsonNode node) {
        var cells = new LinkedHashMap<String, Object>();
        flatten("", node, cells);
        return cells;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, Object> target) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> flatten(
                    prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(), entry.getValue(), target));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                JsonNode item = node.get(index);
                String identity = item.hasNonNull("currency") ? item.get("currency").asText()
                        : item.hasNonNull("key") ? item.get("key").asText() : Integer.toString(index + 1);
                flatten(prefix + "." + identity, item, target);
            }
        } else {
            target.put(prefix, node.isNull() ? null : node.isIntegralNumber() ? node.longValue() : node.asText());
        }
    }
    private static final Map<String, String[]> LABELS = labels();

    private static Map<String, String[]> labels() {
        var out = new HashMap<String, String[]>();
        String[][] rows = {
            {"report","Informe","Report","报表"},{"asOf","Generado (UTC)","Generated (UTC)","生成时间 (UTC)"},{"timezone","Zona horaria","Time zone","时区"},{"precision","Precisión","Precision","精度"},
            {"statistics","Ventas y sincronización","Sales and synchronization","销售与同步"},{"articles","Artículos","Products","商品"},{"movements","Movimientos","Movements","库存变动"},{"inventory","Inventario","Inventory","库存"},{"comparison","Comparación","Comparison","对比"},
            {"current","Actual","Current","当前"},{"previous","Anterior","Previous","以前"},{"period","Período","Period","期间"},{"comparisonPeriod","Período comparado","Comparison period","对比期间"},{"from","Desde","From","从"},{"to","Hasta","To","至"},{"days","Días","Days","天数"},
            {"companyName","Empresa","Company","企业"},{"storeName","Tienda","Store","门店"},{"productName","Producto","Product","商品"},{"productCode","Código","Code","编码"},{"warehouseName","Almacén","Warehouse","仓库"},{"province","Provincia","Province","省份"},
            {"companyId","ID empresa","Company ID","企业ID"},{"storeId","ID tienda","Store ID","门店ID"},{"productId","ID producto","Product ID","商品ID"},{"warehouseId","ID almacén","Warehouse ID","仓库ID"},{"snapshotId","ID captura","Snapshot ID","快照ID"},{"movementId","ID movimiento","Movement ID","变动ID"},
            {"code","Código","Code","编码"},{"name","Nombre","Name","名称"},{"label","Grupo","Group","分组"},{"comparisonLabel","Grupo comparado","Compared group","对比分组"},{"status","Estado","Status","状态"},{"active","Activo","Active","启用"},{"type","Tipo","Type","类型"},{"productType","Tipo de producto","Product type","商品类型"},
            {"amount","Importe","Amount","金额"},{"averageAmount","Importe por documento","Amount per document","单据平均金额"},{"amountPerDay","Importe por día","Amount per day","每日金额"},{"amountPerStore","Importe por tienda con ventas","Amount per selling store","有销售门店平均金额"},{"currency","Moneda","Currency","币种"},{"currencies","Importes por moneda","Amounts by currency","各币种金额"},
            {"quantity","Cantidad","Quantity","数量"},{"netQuantity","Cantidad neta","Net quantity","净数量"},{"quantitySold","Cantidad vendida","Quantity sold","销售数量"},{"quantityReturned","Cantidad devuelta","Quantity returned","退货数量"},{"salesAmount","Importe de ventas","Sales amount","销售金额"},{"returnsAmount","Importe de devoluciones","Returns amount","退货金额"},{"netAmount","Importe neto","Net amount","净金额"},
            {"delta","Diferencia","Difference","差额"},{"deltaAmount","Diferencia de importe","Amount difference","金额差额"},{"deltaQuantity","Diferencia de cantidad","Quantity difference","数量差额"},{"percent","Variación %","Change %","变化百分比"},{"deltaPercent","Variación %","Change %","变化百分比"},{"previousQuantity","Cantidad anterior","Previous quantity","以前数量"},{"currentQuantity","Cantidad actual","Current quantity","当前数量"},
            {"documents","Documentos","Documents","单据"},{"eligibleDocuments","Documentos computables","Eligible documents","可统计单据"},{"cancelledDocuments","Documentos anulados","Voided documents","已作废单据"},{"saleDocuments","Ventas","Sales","销售"},{"returnDocuments","Devoluciones","Returns","退货"},{"syncEvents","Eventos recibidos","Received events","接收事件"},{"syncErrors","Fallos","Errors","错误"},{"stockMovementEvents","Envíos de movimientos","Movement receipts","变动接收记录"},
            {"stores","Tiendas","Stores","门店"},{"licenses","Licencias","Licenses","许可证"},{"contributingStores","Tiendas con ventas","Selling stores","有销售门店"},{"rows","Filas","Rows","行数"},{"total","Total","Total","合计"},{"totalRows","Filas totales","Total rows","总行数"},{"totalGroups","Grupos totales","Total groups","总分组"},{"totals","Totales","Totals","合计"},
            {"capturedAt","Capturado (UTC)","Captured (UTC)","采集时间 (UTC)"},{"completedAt","Recibido completo (UTC)","Fully received (UTC)","接收完成时间 (UTC)"},{"occurredAt","Movimiento (UTC)","Movement (UTC)","变动时间 (UTC)"},{"receivedAt","Recibido (UTC)","Received (UTC)","接收时间 (UTC)"},
            {"coverage","Cobertura","Coverage","覆盖范围"},{"unknownRelationships","Relaciones pendientes","Pending relationships","未完成关联"},{"currentIncompleteDocuments","Documentos incompletos actuales","Current incomplete documents","当前不完整单据"},{"comparisonIncompleteDocuments","Documentos incompletos comparados","Comparison incomplete documents","对比期间不完整单据"},{"malformedReceipts","Recepciones inválidas","Invalid receipts","无效接收记录"},{"conflictingMovements","Movimientos en conflicto","Conflicting movements","冲突变动"},{"duplicateReceipts","Recepciones duplicadas","Duplicate receipts","重复接收记录"},
            {"productCount","Artículos","Products","商品"},{"activeProductCount","Artículos activos","Active products","启用商品"},{"warehouseCount","Almacenes","Warehouses","仓库"},{"balanceCount","Saldos","Balances","库存记录"},{"positiveCount","Saldos positivos","Positive balances","正库存记录"},{"negativeCount","Saldos negativos","Negative balances","负库存记录"},{"zeroCount","Saldos a cero","Zero balances","零库存记录"},
            {"added","Añadidos","Added","新增"},{"removed","Retirados","Removed","移除"},{"changed","Modificados","Changed","修改"},{"unchanged","Sin cambios","Unchanged","未变更"},{"activated","Activados","Activated","启用"},{"deactivated","Desactivados","Deactivated","停用"},{"modified","Modificados","Modified","修改"},{"counts","Recuentos","Counts","计数"},{"catalogue","Catálogo","Catalogue","商品目录"},
            {"barcode","Código de barras","Barcode","条码"},{"secondaryBarcode","Código de barras secundario","Secondary barcode","备用条码"},{"familyName","Familia","Category","类别"},{"subfamilyName","Subfamilia","Subcategory","子类别"},{"rank","Posición","Rank","排名"},{"snapshot","Captura","Snapshot","快照"},{"currentCount","Cantidad actual de registros","Current record count","当前记录数"},{"comparisonCount","Cantidad comparada de registros","Compared record count","对比记录数"},
            {"kind","Vista","View","视图"}, {"overview","Resumen por tienda","Store overview","各门店汇总"},
            {"provinces","Resumen por provincia","Province overview","各省份汇总"},
            {"history","Historial de capturas","Snapshot history","快照历史"}, {"products","Artículos","Products","商品"},
            {"activeProducts","Artículos activos","Active products","启用商品"}, {"warehouses","Almacenes","Warehouses","仓库"},
            {"balances","Saldos","Balances","库存记录"}, {"ledger","Detalle de movimientos","Movement ledger","变动明细"},
            {"summary","Resumen de movimientos","Movement summary","变动汇总"}, {"reportedStores","Tiendas con captura","Stores with snapshot","有快照的门店"},
            {"installationId","ID instalación","Installation ID","安装ID"}, {"currentInstallation","Instalación actual","Current installation","当前安装"},
            {"previousSnapshotId","ID captura anterior","Previous snapshot ID","上一快照ID"}, {"currentSnapshotId","ID captura actual","Current snapshot ID","当前快照ID"},
            {"familyId","ID familia","Category ID","类别ID"}, {"subfamilyId","ID subfamilia","Subcategory ID","子类别ID"}, {"defaultWarehouse","Almacén predeterminado","Default warehouse","默认仓库"},
            {"documentId","ID documento","Document ID","单据ID"}, {"number","Número","Number","编号"}, {"businessDate","Fecha de negocio","Business date","业务日期"},
            {"eligible","Computable","Eligible","可统计"}, {"linesComplete","Líneas completas","Complete lines","行项目完整"},
            {"relationshipsComplete","Relaciones completas","Complete relationships","关联完整"},
            {"averageQuantityPerSale","Cantidad media por venta","Average quantity per sale","每笔销售平均数量"},
            {"amountSharePercent","Cuota de importe %","Amount share %","金额占比 %"},
            {"search","Búsqueda","Search","搜索"}, {"groupBy","Agrupación","Grouping","分组依据"}, {"sort","Orden","Sort","排序"},
            {"direction","Sentido del orden","Sort direction","排序方向"}, {"changedOnly","Solo con cambios","Only changed","仅变化项"},
            {"comparisonFrom","Comparación desde","Comparison from","对比开始日期"}, {"comparisonTo","Comparación hasta","Comparison to","对比结束日期"},
            {"key","Clave","Key","键"}, {"deltas","Variaciones","Changes","变化"}, {"syncByType","Sincronización por tipo","Sync by type","按类型同步统计"},
            {"syncByStatus","Sincronización por estado","Sync by status","按状态同步统计"},
            {"DAY","Día","Day","日"}, {"WEEK","Semana","Week","周"}, {"MONTH","Mes","Month","月"},
            {"COMPANY","Empresa","Company","企业"}, {"STORE","Tienda","Store","门店"}, {"PROVINCE","Provincia","Province","省份"},
            {"WAREHOUSE","Almacén","Warehouse","仓库"}, {"TYPE","Tipo","Type","类型"}, {"PRODUCT","Producto","Product","商品"},
            {"CODE","Código","Code","编码"}, {"AMOUNT","Importe","Amount","金额"}, {"QUANTITY","Cantidad","Quantity","数量"},
            {"RETURNS","Devoluciones","Returns","退货"}, {"ACTIVE","Activo","Active","启用"}, {"PRODUCTS","Artículos","Products","商品"},
            {"BALANCES","Saldos","Balances","库存记录"}, {"STATUS","Estado","Status","状态"}, {"DELTA","Diferencia","Difference","差额"},
            {"CAPTURED_AT","Fecha de captura","Captured date","采集日期"}, {"OCCURRED_AT","Fecha de movimiento","Movement date","变动日期"},
            {"RECEIVED_AT","Fecha de recepción","Received date","接收日期"}, {"CURRENCY","Moneda","Currency","币种"},
            {"DATE","Fecha","Date","日期"}, {"NUMBER","Número","Number","编号"}, {"ASC","Ascendente","Ascending","升序"}, {"DESC","Descendente","Descending","降序"},
            {"NONE","Sin comparación","No comparison","无对比"}, {"CUSTOM","Personalizada","Custom","自定义"},
            {"PREVIOUS_PERIOD","Período anterior","Previous period","上一期间"}, {"PREVIOUS_YEAR","Año anterior","Previous year","上一年"},
            {"true","Sí","Yes","是"}, {"false","No","No","否"},
            {"sales","Ventas","Sales","销售"}, {"sync","Sincronización","Synchronization","同步"},
            {"averages","Promedios de venta","Sales averages","销售平均值"}, {"provinceSales","Ventas por provincia","Sales by province","各省销售"},
            {"value","Valor","Value","值"}, {"UNIT","Unidad","Unit","按件"}, {"WEIGHT","Peso","Weight","按重量"},
            {"SERVICE","Servicio","Service","服务"}, {"ADDED","Añadido","Added","新增"}, {"REMOVED","Retirado","Removed","移除"},
            {"CHANGED","Modificado","Changed","已修改"}, {"UNCHANGED","Sin cambios","Unchanged","无变化"},
            {"AJUSTE","Ajuste","Adjustment","调整"}, {"TRANSFERENCIA_SALIDA","Salida por transferencia","Transfer out","调拨出库"},
            {"TRANSFERENCIA_ENTRADA","Entrada por transferencia","Transfer in","调拨入库"}, {"SALIDA_ALMACEN","Salida de almacén","Warehouse out","出库"},
            {"ENTRADA_ALMACEN","Entrada de almacén","Warehouse in","入库"}, {"ALBARAN_ENTRADA","Albarán de entrada","Inbound delivery note","入库单"},
            {"FACTURA_ENTRADA","Factura de entrada","Purchase invoice","采购发票"}, {"ALBARAN_VENTA","Albarán de venta","Sales delivery note","销售送货单"},
            {"TICKET","Ticket","Receipt","小票"}, {"FACTURA_VENTA","Factura de venta","Sales invoice","销售发票"},
            {"ANULACION","Anulación","Void","作废"}, {"RECTIFICATIVA_VENTA","Factura rectificativa","Corrective invoice","更正发票"},
            {"CONFIRMADO","Confirmado","Confirmed","已确认"}, {"ANULADO","Anulado","Voided","已作废"},
            {"PENDIENTE","Pendiente","Pending","待处理"}, {"PARCIAL","Parcial","Partial","部分完成"}, {"PAGADO","Pagado","Paid","已付款"}
        };
        for (String[] row : rows) {
            out.put(row[0], new String[]{row[1], row[2], row[3]});
        }
        return Map.copyOf(out);
    }

    static String label(String key, String language) {
        return Arrays.stream(key.split("\\.")).map(part -> valueLabel(part, language))
                .collect(java.util.stream.Collectors.joining(" · "));
    }

    static String valueLabel(String key, String language) {
        int index = "zh".equals(language) ? 2 : "en".equals(language) ? 1 : 0;
        var labels = LABELS.get(key);
        return labels == null ? key : labels[index];
    }

    static String tr(String language, String es, String en, String zh) {
        return "zh".equals(language) ? zh : "en".equals(language) ? en : es;
    }
}
