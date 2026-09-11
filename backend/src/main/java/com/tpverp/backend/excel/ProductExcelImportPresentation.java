package com.tpverp.backend.excel;

import java.util.List;
import java.util.Map;

/** One presentation source for preview error cells and workbook exports. */
final class ProductExcelImportPresentation {
    private ProductExcelImportPresentation() { }

    /** Identity and document quantity come from Excel, not from a master-data update. */
    static boolean isReferenceColumn(String key) {
        return "excel.code".equals(key) || "excel.barcode".equals(key) || "excel.quantity".equals(key);
    }

    static Map<String, String> errorTexts(List<ProductExcelImportPreviewService.ImportError> errors) {
        var safeErrors = errors == null ? List.<ProductExcelImportPreviewService.ImportError>of() : errors;
        if (safeErrors.isEmpty()) return Map.of();
        return Map.of("es", localizedErrorText(safeErrors, "es"), "en", localizedErrorText(safeErrors, "en"),
                "zh", localizedErrorText(safeErrors, "zh"));
    }

    static String localizedErrorText(List<ProductExcelImportPreviewService.ImportError> errors, String locale) {
        Labels labels = Labels.forLocale(locale);
        return errors.stream().map(error -> {
            ErrorCopy copy = localizedError(error, labels);
            return "[" + safe(error.code()) + "] | " + labels.row() + ": " + safe(error.row())
                + " | " + labels.column() + ": " + columnLetter(error.column())
                + " | " + labels.attribute() + ": " + (error.attribute() == null ? "-" : labels.field(error.attribute()))
                + " | " + labels.received() + ": " + safe(error.receivedValue())
                + " | " + labels.reason() + ": " + safe(copy.reason())
                + " | " + labels.accepted() + ": " + safe(copy.accepted())
                + " | " + labels.fix() + ": " + safe(copy.fix());
        })
                .reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private static ErrorCopy localizedError(ProductExcelImportPreviewService.ImportError error, Labels labels) {
        if ("es".equals(labels.locale())) return new ErrorCopy(error.reason(), error.acceptedValues(), error.recommendedFix());
        String code = error.code() == null ? "" : error.code();
        if ("en".equals(labels.locale())) {
            return switch (code) {
                case "DATE_INVALID" -> new ErrorCopy("The date is invalid", "A valid native Excel date, DD-MM-YY, DD-MM-YYYY, or ISO", "Correct the date using a real calendar day");
                case "DATE_RANGE_INVALID" -> new ErrorCopy("The end date is before the start date", "Offer until on or after Offer from", "Correct the offer interval");
                case "INVALID_BOOLEAN" -> new ErrorCopy("The boolean value is invalid", "0 or 1", "Enter 0 or 1");
                case "INVALID_PRICE_MODE" -> new ErrorCopy("The price mode is invalid", "1, 2, 3, or 4", "Enter an accepted price mode");
                case "IDENTIFIER_REQUIRED" -> new ErrorCopy("A product identifier is missing", "Code, barcode, or name", "Complete the row identifier");
                case "IDENTIFIER_DUPLICATE" -> new ErrorCopy("The identifier is duplicated", "Unique identifiers", "Correct the repeated rows");
                case "TAX_UNKNOWN", "TAX_AMBIGUOUS" -> new ErrorCopy("The tax is unknown or ambiguous", "One active tax in this store", "Select a valid tax");
                case "OFFER_REQUIRED" -> new ErrorCopy("The offer is incomplete", "Offer start and its price or discount", "Complete the offer fields");
                case "VERSION_STALE" -> new ErrorCopy("The product changed since the preview", "A current preview", "Generate the preview again");
                case "VIEW_INVALID" -> new ErrorCopy("The export view is invalid", "An importer tab", "Select the tab and export again");
                case "FILE_EMPTY" -> new ErrorCopy("The workbook is empty", "A non-empty XLS/XLSX workbook", "Select a workbook containing data");
                case "FILE_TOO_LARGE" -> new ErrorCopy("The workbook exceeds 10 MB", "A workbook up to 10 MB", "Reduce the workbook size");
                case "FILE_EXTENSION_INVALID", "FILE_SIGNATURE_INVALID" -> new ErrorCopy("The workbook format is not supported", "A real XLS or XLSX workbook", "Use the original XLS/XLSX extension");
                case "FILE_READ_FAILED", "PREVIEW_READ_FAILED", "WORKBOOK_ENCRYPTED", "WORKBOOK_CORRUPT", "WORKBOOK_UNREADABLE", "WORKBOOK_MACRO_UNSUPPORTED" -> new ErrorCopy("The workbook cannot be opened safely", "A complete macro-free workbook", "Open and save a compatible workbook, then retry");
                case "MAPPING_REQUIRED", "IDENTITY_MAPPING_REQUIRED", "MAPPING_FIELD_UNKNOWN", "COLUMN_INVALID", "COLUMN_NOT_FOUND", "COLUMN_LIMIT", "SHEET_MISSING" -> new ErrorCopy("The import mapping is invalid", "A valid A-IV column and identity mapping", "Correct the mapping and preview again");
                case "CONTRACT_LIMIT" -> new ErrorCopy("The configuration contains too many keys", "Maps within the contract limits", "Remove unused configuration keys");
                case "CONTEXT_REQUIRED", "CONTEXT_INVALID", "STORE_CONTEXT_MISMATCH", "COMPANY_CONTEXT_MISMATCH" -> new ErrorCopy("The operational context is invalid", "STOCK, WAREHOUSE_INPUT, or WAREHOUSE_OUTPUT", "Select the active context");
                case "START_ROW_INVALID", "NO_ROWS_DETECTED", "ROW_LIMIT", "ROW_INVALID", "GRID_ROW_LIMIT", "GRID_CELL_LIMIT", "CELL_LIMIT", "TEXT_LIMIT", "FORMULA_LIMIT", "WORKBOOK_LIMIT" -> new ErrorCopy("The detected rows or workbook limits are invalid", "Rows and cells within the configured limits", "Correct the workbook or split the import");
                case "EDIT_LIMIT" -> new ErrorCopy("There are too many edits", "Up to 250,000 edits", "Reduce the edits");
                case "ERROR_LIMIT" -> new ErrorCopy("Some row error details were omitted", "Up to 5,000 row details with ERROR classification preserved", "Correct the indicated rows and preview again");
                case "TRANSPORT_REQUEST_TOO_LARGE" -> new ErrorCopy("The importer request exceeds the transport limit", "An import request up to 64 MiB", "Reduce the file or split the request");
                case "NUMBER_FORMAT_UNSUPPORTED" -> new ErrorCopy("The numeric format is not supported", "A format up to 1,024 characters, without conditions and with zero, one, or two percent operators", "Use a standard numeric or percentage format");
                case "PERCENTAGE_FORMAT_NOT_ALLOWED" -> new ErrorCopy("A percentage format is not allowed for this field", "A numeric format without percentage for this field", "Change the cell to a numeric format without percentage");
                case "IDENTIFIER_NUMERIC_PRECISION" -> new ErrorCopy("The numeric identifier does not preserve safe precision", "A non-negative integer of up to 15 digits or text", "Format the identifier column as Text and read the workbook again");
                case "EDIT_VALUE_LIMIT", "CELL_EDIT_INVALID" -> new ErrorCopy("The cell edits exceed their limits", "Edits within row, column, and text limits", "Reduce or correct the edits");
                case "CELL_TEXT_LIMIT", "FORMULA_NO_CACHE" -> new ErrorCopy("The workbook cell content is not safe to import", "Cell text within Excel limits and cached formula results", "Shorten the cell or save calculated formula results");
                case "CELL_ERROR_VALUE", "FORMULA_RESULT_ERROR" -> new ErrorCopy("The cell or formula contains an Excel error", "A valid calculated value without Excel errors", "Fix the error, recalculate, and save the workbook");
                case "FIELD_LENGTH_INVALID" -> new ErrorCopy("A field exceeds its maximum length", "The field length allowed by the importer", "Shorten the indicated field");
                case "PRODUCT_TYPE_INVALID" -> new ErrorCopy("The product type is invalid", "1 = Unit (UNIT), 2 = Weight (WEIGHT), 3 = Service (SERVICE)", "Select a valid product type");
                case "NUMBER_SCALE_INVALID" -> new ErrorCopy("The number has too many decimal places", "Prices, quantities and stock: up to 3 decimals; percentages: up to 2", "Correct the indicated cell and apply again");
                case "ZERO_PRICE_INVALID", "NUMBER_INVALID", "NUMBER_FORMAT_AMBIGUOUS", "NUMBER_PRECISION_INVALID", "DISCOUNT_PROHIBITED_PRICE_MODE" -> new ErrorCopy("The numeric or pricing value is invalid", "A value satisfying the selected price mode", "Correct the numeric or discount setting");
                case "GLOBAL_VALUE_UNKNOWN", "VALUE_SOURCE_UNKNOWN", "VALUE_SOURCE_INVALID", "UPDATE_FIELD_UNKNOWN" -> new ErrorCopy("The import configuration value is invalid", "A supported field and value source", "Correct the import configuration");
                case "TAX_REQUIRED", "FAMILY_REQUIRED", "FAMILY_UNKNOWN", "FAMILY_AMBIGUOUS", "SUBFAMILY_UNKNOWN", "SUBFAMILY_AMBIGUOUS", "SUBFAMILY_FAMILY_MISMATCH" -> new ErrorCopy("The product classification reference is invalid", "A unique active reference from this store", "Select a valid store reference");
                case "NAME_REQUIRED", "PRODUCT_AMBIGUOUS", "DUPLICATE_CONFLICT" -> new ErrorCopy("The product identity is invalid", "A unique code, barcode, or name", "Correct the product identity");
                case "STOCK_RANGE_INVALID", "QUANTITY_REQUIRED" -> new ErrorCopy("The product values are incomplete or inconsistent", "Values satisfying the product rules", "Complete and correct the indicated values");
                case "FILE_CHANGED", "HASH_REQUIRED", "TOKEN_LIMIT", "TOKEN_UNEXPECTED", "TOKEN_INVALID", "CONCURRENCY_TOKEN_REQUIRED", "VERSION_REQUIRED" -> new ErrorCopy("The preview concurrency data is not current", "The current preview hash and tokens", "Generate the preview again");
                case "MISSING_REVIEW_REQUIRED", "CONFIRMATION_REQUIRED", "APPLY_CONTEXT_UNSUPPORTED", "APPLY_CONTEXT_INVALID", "APPLY_PROVENANCE_REQUIRED", "APPLY_REQUIRED_VALUE", "APPLY_OFFER_REQUIRED", "APPLY_TRANSACTION_FAILED", "SUMMARY_PREVIEW_INVALID" -> new ErrorCopy("The import cannot be applied in this state", "A valid confirmed import", "Review the import and try again");
                case "PRODUCT_LOCAL_RESOLUTION_FAILED" -> new ErrorCopy("The existing product cannot be resolved locally", "One matching product in the local catalogue", "Refresh the catalogue and preview again");
                case "PERMISSION_DENIED" -> new ErrorCopy("You do not have permission for this operation", "The permission required by the context", "Request the required permission");
                default -> new ErrorCopy("Could not validate import code " + code, "A valid value for this code", "Correct the value and generate the preview again");
            };
        }
        return switch (code) {
            case "DATE_INVALID" -> new ErrorCopy("日期无效", "有效的 Excel 原生日期、DD-MM-AA、DD-MM-AAAA 或 ISO", "使用真实日历日期更正日期");
            case "DATE_RANGE_INVALID" -> new ErrorCopy("结束日期早于开始日期", "结束日期不早于开始日期", "更正促销日期范围");
            case "INVALID_BOOLEAN" -> new ErrorCopy("布尔值无效", "0 或 1", "输入 0 或 1");
            case "INVALID_PRICE_MODE" -> new ErrorCopy("价格模式无效", "1、2、3 或 4", "输入有效价格模式");
            case "IDENTIFIER_REQUIRED" -> new ErrorCopy("缺少商品标识", "编码、条码或名称", "补全该行标识");
            case "IDENTIFIER_DUPLICATE" -> new ErrorCopy("标识重复", "唯一标识", "更正重复行");
            case "TAX_UNKNOWN", "TAX_AMBIGUOUS" -> new ErrorCopy("税率未知或不明确", "此门店的唯一启用税率", "选择有效税率");
            case "OFFER_REQUIRED" -> new ErrorCopy("促销信息不完整", "促销开始日期及价格或折扣", "补全促销字段");
            case "VERSION_STALE" -> new ErrorCopy("商品在预览后已发生变化", "最新预览", "重新生成预览");
            case "FILE_EMPTY" -> new ErrorCopy("工作簿为空", "包含数据的 XLS/XLSX 工作簿", "选择包含数据的工作簿");
            case "FILE_TOO_LARGE" -> new ErrorCopy("工作簿超过 10 MB", "最大 10 MB 的工作簿", "减小工作簿大小");
            case "FILE_EXTENSION_INVALID", "FILE_SIGNATURE_INVALID" -> new ErrorCopy("工作簿格式不受支持", "真实的 XLS 或 XLSX 工作簿", "使用原始 XLS/XLSX 扩展名");
            case "FILE_READ_FAILED", "PREVIEW_READ_FAILED", "WORKBOOK_ENCRYPTED", "WORKBOOK_CORRUPT", "WORKBOOK_UNREADABLE", "WORKBOOK_MACRO_UNSUPPORTED" -> new ErrorCopy("无法安全打开工作簿", "完整且不含宏的工作簿", "打开并保存兼容工作簿后重试");
            case "MAPPING_REQUIRED", "IDENTITY_MAPPING_REQUIRED", "MAPPING_FIELD_UNKNOWN", "COLUMN_INVALID", "COLUMN_NOT_FOUND", "COLUMN_LIMIT", "SHEET_MISSING" -> new ErrorCopy("导入映射无效", "有效的 A-IV 列和标识映射", "更正映射后重新预览");
            case "CONTRACT_LIMIT" -> new ErrorCopy("配置包含过多键", "符合契约限制的映射", "删除未使用的配置键");
            case "CONTEXT_REQUIRED", "CONTEXT_INVALID", "STORE_CONTEXT_MISMATCH", "COMPANY_CONTEXT_MISMATCH" -> new ErrorCopy("操作上下文无效", "STOCK、WAREHOUSE_INPUT 或 WAREHOUSE_OUTPUT", "选择当前上下文");
            case "START_ROW_INVALID", "NO_ROWS_DETECTED", "ROW_LIMIT", "ROW_INVALID", "GRID_ROW_LIMIT", "GRID_CELL_LIMIT", "CELL_LIMIT", "TEXT_LIMIT", "FORMULA_LIMIT", "WORKBOOK_LIMIT" -> new ErrorCopy("检测到的行或工作簿超出限制", "限制范围内的行和单元格", "更正工作簿或拆分导入");
            case "EDIT_LIMIT" -> new ErrorCopy("编辑过多", "最多 250,000 次编辑", "减少编辑");
            case "ERROR_LIMIT" -> new ErrorCopy("部分行错误详情已省略", "最多 5,000 条行详情且保留 ERROR 分类", "更正指出的行后重新预览");
            case "TRANSPORT_REQUEST_TOO_LARGE" -> new ErrorCopy("导入请求超过传输限制", "不超过 64 MiB 的导入请求", "减小文件或拆分请求");
            case "NUMBER_FORMAT_UNSUPPORTED" -> new ErrorCopy("数字格式不受支持", "最多 1,024 个字符、无条件且包含零个、一个或两个百分号运算符的格式", "使用标准数字或百分比格式");
            case "PERCENTAGE_FORMAT_NOT_ALLOWED" -> new ErrorCopy("此属性不允许使用百分比格式", "此属性使用不带百分号的数字格式", "将单元格改为不带百分号的数字格式");
            case "IDENTIFIER_NUMERIC_PRECISION" -> new ErrorCopy("数字标识无法保留安全精度", "最多 15 位的非负整数或文本", "将标识列设置为文本后重新读取工作簿");
            case "EDIT_VALUE_LIMIT", "CELL_EDIT_INVALID" -> new ErrorCopy("单元格编辑超过限制", "行、列和文本均在限制内的编辑", "减少或更正编辑");
            case "CELL_TEXT_LIMIT", "FORMULA_NO_CACHE" -> new ErrorCopy("工作簿单元格内容不安全", "符合 Excel 限制的文本和已缓存公式结果", "缩短单元格或保存计算结果");
            case "CELL_ERROR_VALUE", "FORMULA_RESULT_ERROR" -> new ErrorCopy("单元格或公式包含 Excel 错误", "不含 Excel 错误的有效计算值", "修正错误、重新计算并保存工作簿");
            case "FIELD_LENGTH_INVALID" -> new ErrorCopy("字段超过最大长度", "导入器允许的字段长度", "缩短指定字段");
            case "PRODUCT_TYPE_INVALID" -> new ErrorCopy("商品类型无效", "1 = 计件 (UNIT)，2 = 称重 (WEIGHT)，3 = 服务 (SERVICE)", "选择有效商品类型");
            case "NUMBER_SCALE_INVALID" -> new ErrorCopy("小数位数超出限制", "价格、数量和库存最多3位小数；百分比最多2位", "更正所示单元格后重新应用");
            case "ZERO_PRICE_INVALID", "NUMBER_INVALID", "NUMBER_FORMAT_AMBIGUOUS", "NUMBER_PRECISION_INVALID", "DISCOUNT_PROHIBITED_PRICE_MODE" -> new ErrorCopy("数字或价格值无效", "符合所选价格模式的值", "更正数字或折扣设置");
            case "GLOBAL_VALUE_UNKNOWN", "VALUE_SOURCE_UNKNOWN", "VALUE_SOURCE_INVALID", "UPDATE_FIELD_UNKNOWN" -> new ErrorCopy("导入配置值无效", "受支持的字段和值来源", "更正导入配置");
            case "TAX_REQUIRED", "FAMILY_REQUIRED", "FAMILY_UNKNOWN", "FAMILY_AMBIGUOUS", "SUBFAMILY_UNKNOWN", "SUBFAMILY_AMBIGUOUS", "SUBFAMILY_FAMILY_MISMATCH" -> new ErrorCopy("商品分类引用无效", "此门店唯一启用的引用", "选择有效的门店引用");
            case "NAME_REQUIRED", "PRODUCT_AMBIGUOUS", "DUPLICATE_CONFLICT" -> new ErrorCopy("商品标识无效", "唯一编码、条码或名称", "更正商品标识");
            case "STOCK_RANGE_INVALID", "QUANTITY_REQUIRED" -> new ErrorCopy("商品值不完整或不一致", "符合商品规则的值", "填写并更正指定值");
            case "FILE_CHANGED", "HASH_REQUIRED", "TOKEN_LIMIT", "TOKEN_UNEXPECTED", "TOKEN_INVALID", "CONCURRENCY_TOKEN_REQUIRED", "VERSION_REQUIRED" -> new ErrorCopy("预览并发数据不是最新", "当前预览哈希和令牌", "重新生成预览");
            case "MISSING_REVIEW_REQUIRED", "CONFIRMATION_REQUIRED", "APPLY_CONTEXT_UNSUPPORTED", "APPLY_CONTEXT_INVALID", "APPLY_PROVENANCE_REQUIRED", "APPLY_REQUIRED_VALUE", "APPLY_OFFER_REQUIRED", "APPLY_TRANSACTION_FAILED", "SUMMARY_PREVIEW_INVALID" -> new ErrorCopy("当前状态无法应用导入", "有效且已确认的导入", "检查导入后重试");
            case "PRODUCT_LOCAL_RESOLUTION_FAILED" -> new ErrorCopy("无法在本地解析现有商品", "本地目录中的唯一匹配商品", "刷新目录后重新预览");
            case "PERMISSION_DENIED" -> new ErrorCopy("没有执行此操作的权限", "上下文所需权限", "申请所需权限");
            case "VIEW_INVALID" -> new ErrorCopy("导出视图无效", "导入器标签页", "选择标签页后重新导出");
            default -> new ErrorCopy("无法验证导入代码 " + code, "此代码的有效值", "更正数值后重新生成预览");
        };
    }

    private static String safe(Object value) { return value == null ? "-" : String.valueOf(value); }

    private static String columnLetter(Integer column) {
        if (column == null || column < 1) return "-";
        int value = column;
        StringBuilder result = new StringBuilder();
        while (value > 0) { int remainder = (value - 1) % 26; result.append((char) ('A' + remainder)); value = (value - 1) / 26; }
        return result.reverse().toString();
    }

    record Labels(String row, String status, String excelData, String databaseData, String beforeAfter,
            String errorDetail, String column, String attribute, String received, String reason, String accepted,
            String fix, String code, String statusExisting, String statusMissing, String statusError, String statusPurchaseChanged,
            String sheetName, String locale) {
        static Labels forLocale(String requested) {
            return switch ((requested == null ? "es" : requested)) {
                case "en" -> new Labels("Row", "Status", "New values (Excel)", "Current values (database)", "Before → After", "Error details", "Column", "Attribute", "Received value", "Reason", "Accepted values", "Recommended correction", "Code", "Existing", "Missing", "Error", "Purchase price changed", "Import summary", "en");
                case "zh" -> new Labels("行", "状态", "新值（Excel）", "当前值（数据库）", "更改前 → 更改后", "错误详情", "列", "属性", "收到的值", "原因", "可接受值", "建议修正", "代码", "已存在", "不存在", "错误", "采购价已变化", "导入摘要", "zh");
                default -> new Labels("Fila", "Estado", "Valores nuevos (Excel)", "Valores actuales (BD)", "Antes → Después", "Detalle del error", "Columna", "Atributo", "Valor recibido", "Motivo", "Valores aceptados", "Corrección recomendada", "Código", "Existente", "No existente", "Error", "Precio de compra cambiado", "Resumen importación", "es");
            };
        }
        String field(String key) {
            return switch (key) {
                case "code" -> switchLabel("Código", "Code", "编码");
                case "barcode" -> switchLabel("Código de barras", "Barcode", "条码");
                case "barcode2" -> switchLabel("Código de barras 2", "Barcode 2", "条码 2");
                case "supplierReference" -> switchLabel("Referencia proveedor", "Supplier reference", "供应商参考");
                case "name" -> switchLabel("Nombre", "Name", "名称");
                case "description" -> switchLabel("Descripción", "Description", "描述");
                case "comments" -> switchLabel("Comentarios", "Comments", "备注");
                case "quantity" -> switchLabel("Cantidad", "Quantity", "数量");
                case "familyId" -> switchLabel("Familia / Subfamilia", "Family / Subfamily", "类别 / 子类别");
                case "familyBusinessCode" -> switchLabel("Código Familia / Subfamilia", "Family / Subfamily code", "类别 / 子类别编码");
                case "subfamilyId" -> switchLabel("Subfamilia", "Subfamily", "子类别");
                case "purchasePrice" -> switchLabel("Precio de compra", "Purchase price", "采购价");
                case "salePrice" -> switchLabel("Precio de venta", "Sale price", "售价");
                case "memberPrice" -> switchLabel("Precio de miembro", "Member price", "会员价");
                case "wholesalePrice" -> switchLabel("Precio mayorista", "Wholesale price", "批发价");
                case "offerPrice" -> switchLabel("Precio de oferta", "Offer price", "促销价");
                case "priceUseMode" -> switchLabel("Usar precio", "Use price", "使用价格");
                case "discountType", "prohibitedDiscount" -> switchLabel("Prohibido descuento", "Discount prohibited", "禁止折扣");
                case "taxId" -> switchLabel("Impuestos", "Tax", "税");
                case "taxesIncluded" -> switchLabel("Impuestos incluidos", "Taxes included", "含税");
                case "productType" -> switchLabel("Tipo de producto", "Product type", "商品类型");
                case "purchaseDiscountPercent" -> switchLabel("Descuento de compra", "Purchase discount", "采购折扣");
                case "offerDiscountPercent" -> switchLabel("Descuento de oferta %", "Offer discount %", "促销折扣%");
                case "offerActive" -> switchLabel("Oferta activa", "Offer active", "促销启用");
                case "offerFrom" -> switchLabel("Oferta desde", "Offer from", "促销开始");
                case "offerUntil" -> switchLabel("Oferta hasta", "Offer until", "促销结束");
                case "packageQuantity" -> switchLabel("Cantidad por paquete", "Package quantity", "每包数量");
                case "stockMin" -> switchLabel("Stock mínimo", "Minimum stock", "最低库存");
                case "stockMax" -> switchLabel("Stock máximo", "Maximum stock", "最高库存");
                default -> switchLabel("Campo de importación", "Import field", "导入字段");
            };
        }
        String column(String key) {
            if ("rowNumber".equals(key)) return row();
            if ("status".equals(key)) return status();
            if ("errors".equals(key)) return errorDetail();
            if (isReferenceColumn(key)) return field(key.substring(6));
            if (key.startsWith("excel.")) return field(key.substring(6)) + " · " + excelData();
            if (key.startsWith("current.")) return field(key.substring(8)) + " · " + databaseData();
            return key;
        }
        String rawSheetName() { return switch (locale) { case "en" -> "Raw import"; case "zh" -> "原始导入"; default -> "Importación RAW"; }; }
        private String switchLabel(String es, String en, String zh) {
            return row.equals("Fila") ? es : row.equals("Row") ? en : zh;
        }
        String statusValue(String value, boolean purchaseChanged) { return switch (value) { case "EXISTING" -> purchaseChanged ? statusPurchaseChanged : statusExisting; case "MISSING" -> statusMissing; case "ERROR" -> statusError; default -> value; }; }
    }

    private record ErrorCopy(String reason, String accepted, String fix) { }

}
