package com.tpverp.backend.excel;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductIdentifier;
import com.tpverp.backend.catalog.ProductIdentifierRepository;
import com.tpverp.backend.catalog.CatalogText;
import com.tpverp.backend.catalog.StoreTax;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.catalog.Family;
import com.tpverp.backend.catalog.FamilyRepository;
import com.tpverp.backend.catalog.Subfamily;
import com.tpverp.backend.catalog.SubfamilyRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Server-authoritative, read-only classification for an Excel import. */
@Service
public class ProductExcelImportPreviewService {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Set<String> MAPPING_FIELDS = Set.of(
            "familyId", "subfamilyId", "taxId", "productType", "priceUseMode", "discountType",
            "code", "barcode", "barcode2", "name", "description", "comments", "purchasePrice",
            "purchaseDiscountPercent", "taxesIncluded", "salePrice", "memberPrice", "wholesalePrice",
            "offerPrice", "offerDiscountPercent", "offerActive", "offerFrom", "offerUntil",
            "packageQuantity", "stockMin", "stockMax", "quantity", "supplierReference", "prohibitedDiscount");
    private static final Set<String> GLOBAL_FIELDS = Set.of("offerActive", "productType", "priceUseMode", "discountType", "prohibitedDiscount", "taxId", "taxesIncluded");
    private static final Set<String> DOCUMENT_PRICE_FIELDS = Set.of(
            "purchasePrice", "salePrice", "memberPrice", "wholesalePrice", "offerPrice");
    private static final Set<String> NUMERIC_IMPORT_FIELDS = Set.of("purchasePrice", "purchaseDiscountPercent", "salePrice",
            "memberPrice", "wholesalePrice", "offerPrice", "offerDiscountPercent", "quantity", "packageQuantity", "stockMin", "stockMax",
            "taxId", "priceUseMode", "discountType", "prohibitedDiscount", "taxesIncluded", "offerActive", "productType");
    private static final Set<String> UPDATABLE_FIELDS = Set.of(
            "barcode2", "name", "description", "comments", "familyId", "subfamilyId", "taxId", "productType",
            "priceUseMode", "discountType", "purchasePrice", "purchaseDiscountPercent", "taxesIncluded", "salePrice",
            "memberPrice", "wholesalePrice", "offerPrice", "offerDiscountPercent", "offerActive", "offerFrom", "offerUntil",
            "packageQuantity", "stockMin", "stockMax", "prohibitedDiscount");
    public static final int MAX_EDITS = 250_000;
    public static final int MAX_EDIT_VALUE_CHARACTERS = 32_767;
    public static final long MAX_EDIT_CHARACTERS = 5_000_000L;
    private static final int MAX_ERROR_DETAILS = 5_000;
    private static final String CONTRACT_LIMIT = "CONTRACT_LIMIT";
    private static final int MAX_CONTRACT_KEY_CHARACTERS = 128;
    private static final long MAX_CONTRACT_CHARACTERS = 100_000L;
    private static final BigDecimal MAX_JAVASCRIPT_SAFE_INTEGER = new BigDecimal("9007199254740991");

    private final ProductExcelImportReadService reader;
    private final CurrentOrganization organization;
    private final ProductIdentifierRepository identifiers;
    private final com.tpverp.backend.catalog.ProductRepository products;
    private final StoreTaxRepository taxes;
    private final AuditService audit;
    private final FamilyRepository families;
    private final SubfamilyRepository subfamilies;

    public ProductExcelImportPreviewService(
            ProductExcelImportReadService reader,
            CurrentOrganization organization,
            ProductIdentifierRepository identifiers,
            com.tpverp.backend.catalog.ProductRepository products,
            StoreTaxRepository taxes) {
        this(reader, organization, identifiers, products, taxes, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductExcelImportPreviewService(
            ProductExcelImportReadService reader,
            CurrentOrganization organization,
            ProductIdentifierRepository identifiers,
            com.tpverp.backend.catalog.ProductRepository products,
            StoreTaxRepository taxes,
            AuditService audit,
            FamilyRepository families,
            SubfamilyRepository subfamilies) {
        this.reader = reader;
        this.organization = organization;
        this.identifiers = identifiers;
        this.products = products;
        this.taxes = taxes;
        this.audit = audit;
        this.families = families;
        this.subfamilies = subfamilies;
    }

    @Transactional(noRollbackFor = ProductExcelImportReadService.ProductExcelImportException.class)
    public PreviewResult preview(MultipartFile file, PreviewRequest request) {
        ProductExcelImportReadService.ReadResult read;
        try {
            read = reader.read(file);
        } catch (RuntimeException exception) {
            recordAudit(AuditResult.FALLO, Map.of("code", exception instanceof ProductExcelImportReadService.ProductExcelImportException productError
                    ? productError.code() : "PREVIEW_READ_FAILED"));
            throw exception;
        }
        PreviewRequest effective = request == null ? new PreviewRequest(Map.of(), List.of(), null, null, null, null, null, null, null) : request;
        effective = withoutStockQuantity(effective);
        List<ImportError> contractErrors = validateContract(effective);
        if (!contractErrors.isEmpty()) return blocked(read, contractErrors);
        if (!blank(effective.expectedSha256()) && !effective.expectedSha256().equalsIgnoreCase(read.sha256())) {
            return blocked(read, List.of(error("FILE_CHANGED", null, null, null, effective.expectedSha256(),
                    "El contenido del fichero ha cambiado desde la lectura", "SHA-256 del fichero leído",
                    "Vuelve a cargar el fichero y genera otra vista previa")));
        }
        var store = organization.currentStore();
        if (effective.options() != null && effective.options().storeId() != null && !effective.options().storeId().equals(store.getId())) {
            return blocked(read, List.of(error("STORE_CONTEXT_MISMATCH", null, null, "storeId", effective.options().storeId().toString(),
                    "La tienda de las opciones no coincide con la sesión", "tienda actual", "Usa la tienda activa")));
        }
        if (effective.options() != null && effective.options().companyId() != null && !effective.options().companyId().equals(store.getEmpresa().getId())) {
            return blocked(read, List.of(error("COMPANY_CONTEXT_MISMATCH", null, null, "companyId", effective.options().companyId().toString(),
                    "La empresa de las opciones no coincide con la sesión", "empresa actual", "Usa la empresa activa")));
        }
        List<StoreTax> storeTaxes = taxes.findByStoreIdOrderByPorcentaje(store.getId());
        List<StoreTax> activeTaxes = storeTaxes.stream().filter(StoreTax::isActive).toList();
        List<Family> storeFamilies = families == null ? List.of() : families.findByStoreIdOrderByFamilyCodeAscIdAsc(store.getId());
        Map<String, UUID> familyReferences = familyReferences(storeFamilies);
        UUID defaultFamilyId = storeFamilies.stream().filter(Family::isDefaultFamily)
                .map(Family::getId).findFirst().orElse(null);
        if (effective.storeId() != null && !effective.storeId().equals(store.getId())) {
            return blocked(read, List.of(error("STORE_CONTEXT_MISMATCH", null, null, null, null,
                    "La tienda de la vista previa no coincide con la sesion", "tienda actual",
                    "Vuelve a generar la vista previa en la tienda activa")));
        }
        if (effective.companyId() != null && !effective.companyId().equals(store.getEmpresa().getId())) {
            return blocked(read, List.of(error("COMPANY_CONTEXT_MISMATCH", null, null, null, null,
                    "La empresa de la vista previa no coincide con la sesion", "empresa actual",
                    "Vuelve a generar la vista previa en la empresa activa")));
        }
        PreviewOptions normalizedOptions = effective.options();
        if (normalizedOptions != null) {
            String normalizedContext = canonicalContext(normalizedOptions.context());
            normalizedOptions = new PreviewOptions(normalizedOptions.globalValues(), normalizedOptions.valueSources(),
                    normalizedOptions.showOnlyImported(), normalizedContext, store.getId(), store.getEmpresa().getId(),
                    normalizedOptions.skipZeroPriceUpdate(), normalizedOptions.requireQuantity(),
                    normalizedOptions.documentPriceSource());
        }
        requireContextPermission(normalizedOptions);
        Map<String, String> effectiveMapping = new LinkedHashMap<>(effective.mapping() == null ? Map.of() : effective.mapping());
        if (!effectiveMapping.containsKey("quantity") && !blank(effective.quantityColumn())) effectiveMapping.put("quantity", effective.quantityColumn());
        effective = new PreviewRequest(effectiveMapping, effective.edits(), normalizedOptions, effective.storeId(), effective.companyId(),
                effective.expectedSha256(), effective.startRow(), effective.quantityColumn(), effective.updateFields(), effective.resolvedProducts());
        List<ImportError> mappingErrors = validateMapping(effective.mapping(), read.columns());
        if (!mappingErrors.isEmpty()) return blocked(read, mappingErrors);
        int startRow = effective.startRow() == null ? 2 : effective.startRow();
        if (startRow < 2 || startRow > read.rows().size() + 1) {
            return blocked(read, List.of(error("START_ROW_INVALID", startRow, null, null, String.valueOf(startRow),
                    "La fila inicial debe estar dentro del contenido y ser posterior a la cabecera", "2.." + (read.rows().size() + 1),
                    "Corrige Los productos empiezan en la fila")));
        }
        List<ImportError> editErrors = validateEdits(effective.edits(), read.rows(), read.formulas());
        if (!editErrors.isEmpty()) return blocked(read, editErrors);
        List<List<ProductExcelImportReadService.CellView>> rows = editableRows(read, effective.edits());

        Map<String, String> mapping = effective.mapping() == null ? Map.of() : effective.mapping();
        List<RowInput> detected = new ArrayList<>();
        for (int index = startRow - 1; index < rows.size(); index++) {
            Map<String, String> data = values(rows.get(index), mapping);
            if (blank(data.get("code")) && blank(data.get("barcode")) && blank(data.get("name"))) continue;
            detected.add(new RowInput(index + 1, data, null, mappedCells(rows.get(index), mapping)));
        }
        if (detected.size() > ProductExcelImportReadService.MAX_ROWS) {
            return blocked(read, List.of(error("ROW_LIMIT", null, null, null, null,
                    "El libro supera 5.000 filas detectadas", "hasta 5.000 filas con codigo, barras o nombre",
                    "Divide el fichero en lotes de 5.000 filas o menos")));
        }
        if (detected.isEmpty()) {
            return blocked(read, List.of(error("NO_ROWS_DETECTED", null, null, null, null,
                    "No se han detectado filas con Código, Código de barras o Nombre desde la fila indicada",
                    "Al menos una fila con code, barcode o name", "Mapea Código, Código de barras o Nombre y revisa Los productos empiezan en la fila")));
        }

        Map<String, Set<UUID>> identityIds = identityIds(store.getId(), detected);
        Set<UUID> allIds = identityIds.values().stream().flatMap(Collection::stream).collect(Collectors.toSet());
        allIds.addAll(effective.resolvedProducts().values());
        Map<UUID, Product> productById = products.findAllByStoreIdAndIdIn(store.getId(), allIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (var binding : effective.resolvedProducts().entrySet()) {
            if (!productById.containsKey(binding.getValue()) || detected.stream().noneMatch(row -> row.rowNumber() == binding.getKey())) {
                return blocked(read, List.of(error("ROW_INVALID", binding.getKey(), null, "code", null,
                        "La asociación manual no pertenece a esta importación o tienda", "Producto de la tienda y fila existente",
                        "Revisa el alta manual y vuelve a aplicar")));
            }
        }
        List<PreviewRow> classified = new ArrayList<>();
        ErrorBudget errorBudget = new ErrorBudget();
        LogicalIdentityOwners logicalOwners = logicalIdentityOwners(detected, identityIds);
        Set<String> requestedSubfamilyReferences = detected.stream().map(RowInput::data)
                .flatMap(data -> java.util.stream.Stream.of(data.get("subfamilyId"),
                        !mapping.containsKey("subfamilyId") && normalizeReference(data.get("familyId")).matches("[0-9]{6}")
                                ? data.get("familyId") : null))
                .map(ProductExcelImportPreviewService::normalizeReference).filter(value -> !value.isBlank()).collect(Collectors.toSet());
        Set<UUID> requestedSubfamilyIds = requestedSubfamilyReferences.stream().map(ProductExcelImportPreviewService::parseUuid)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        productById.values().stream().map(Product::getSubfamilyId).filter(Objects::nonNull).forEach(requestedSubfamilyIds::add);
        Set<String> requestedSubfamilyCodes = requestedSubfamilyReferences.stream()
                .filter(reference -> reference.matches("[0-9]{6}")).collect(Collectors.toSet());
        List<Subfamily> referencedSubfamilies = new ArrayList<>();
        if (subfamilies != null && !requestedSubfamilyIds.isEmpty()) {
            referencedSubfamilies.addAll(subfamilies.findByStoreIdAndIdIn(store.getId(), requestedSubfamilyIds));
        }
        if (subfamilies != null && !requestedSubfamilyCodes.isEmpty()) {
            referencedSubfamilies.addAll(subfamilies.findByStoreIdAndSubfamilyCodeIn(store.getId(), requestedSubfamilyCodes));
        }
        Map<String, SubfamilyRef> subfamilyReferences = subfamilies == null || requestedSubfamilyReferences.isEmpty() ? Map.of()
                : referencedSubfamilies.stream()
                        .flatMap(subfamily -> java.util.stream.Stream.of(
                                new SubfamilyRef(normalizeReference(subfamily.getId().toString()), subfamily),
                                new SubfamilyRef(normalizeReference(subfamily.getSubfamilyCode()), subfamily)))
                        .filter(reference -> !reference.reference().isBlank())
                        .collect(Collectors.toMap(SubfamilyRef::reference, Function.identity(),
                                (left, right) -> new SubfamilyRef(left.reference(), null)));
        for (RowInput input : detected) {
            RowInput resolvedInput = new RowInput(input.rowNumber(), input.data(),
                    productById.get(effective.resolvedProducts().get(input.rowNumber())), input.mappedCells());
            classified.add(classify(resolvedInput, mapping, identityIds, productById, store.getId(), effective.options(), effective.updateFields(), activeTaxes,
                    familyReferences, subfamilyReferences, defaultFamilyId, logicalOwners, errorBudget));
        }
        List<PreviewRow> originalRows = List.copyOf(classified);
        boolean stockContext = "STOCK".equals(normalizedOptions.context());
        classified = mergeDuplicates(classified, mapping, errorBudget, stockContext);
        UUID companyId = store.getEmpresa().getId();
        classified = classified.stream()
                .map(row -> withConcurrencyToken(row, read.sha256(), store.getId(), companyId))
                .toList();
        List<ImportError> errors = responseErrors(errorBudget);
        boolean onlyImported = effective.options() != null && Boolean.TRUE.equals(effective.options().showOnlyImported());
        List<PreviewRow> output = onlyImported ? classified.stream().map(PreviewRow::withoutDatabase).toList() : classified;
        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("fileName", read.fileName());
        auditDetails.put("sha256", read.sha256());
        auditDetails.put("context", effective.options() == null ? "" : String.valueOf(effective.options().context()));
        auditDetails.put("detectedRows", detected.size());
        auditDetails.put("existingRows", classified.stream().filter(row -> "EXISTING".equals(row.classification())).count());
        auditDetails.put("missingRows", classified.stream().filter(row -> "MISSING".equals(row.classification())).count());
        auditDetails.put("errorRows", classified.stream().filter(row -> "ERROR".equals(row.classification())).count());
        auditDetails.put("errors", errorBudget.accepted() + errorBudget.omitted());
        auditDetails.put("created", 0);
        auditDetails.put("updated", classified.stream().filter(row -> "EXISTING".equals(row.classification()) && row.masterDataChanged()).count());
        auditDetails.put("unchanged", classified.stream().filter(row -> "EXISTING".equals(row.classification()) && !row.masterDataChanged()).count());
        Map<String, Long> changedFields = new LinkedHashMap<>();
        classified.stream().flatMap(row -> row.changes().keySet().stream()).forEach(field ->
                changedFields.merge(field, 1L, Long::sum));
        auditDetails.put("changedFields", changedFields);
        recordAudit(classified.stream().noneMatch(row -> "ERROR".equals(row.classification())) && errors.isEmpty()
                ? AuditResult.EXITO : AuditResult.FALLO, auditDetails);
        List<FormulaView> formulaMetadata = read.formulas().stream()
                .map(formula -> new FormulaView(formula.cell(), formula.formula(), formula.calculatedValue()))
                .toList();
        long existingRows = output.stream().filter(row -> "EXISTING".equals(row.classification())).count();
        long missingRows = output.stream().filter(row -> "MISSING".equals(row.classification())).count();
        String previewFingerprint = previewFingerprint(read.fileName(), read.sha256(), read.sheetName(), output,
                detected.size(), existingRows, missingRows, errors, formulaMetadata);
        Map<Integer, PreviewRow> groupByRow = new HashMap<>();
        for (PreviewRow group : output) for (Integer number : group.rowNumbers()) groupByRow.put(number, group);
        Map<Integer, RowInput> inputsByRow = detected.stream().collect(Collectors.toMap(RowInput::rowNumber, Function.identity()));
        PreviewOptions reviewOptions = effective.options();
        List<PreviewRow> sourceRows = originalRows.stream().map(original -> {
            PreviewRow group = groupByRow.getOrDefault(original.rowNumber(), original);
            PreviewRow source = new PreviewRow(original.rowNumber(), List.of(original.rowNumber()), group.classification(),
                    reviewExcelData(inputsByRow.get(original.rowNumber()), reviewOptions, storeTaxes),
                    reviewDatabaseData(group.databaseData(), storeFamilies, referencedSubfamilies, storeTaxes),
                    group.version(), original.changes(), group.errors(),
                    original.purchasePriceChanged(), group.masterDataChanged(), group.concurrencyToken());
            return onlyImported ? source.withoutDatabase() : source;
        }).toList();
        previewFingerprint = previewFingerprint(read.fileName(), read.sha256(), read.sheetName(), sourceRows,
                detected.size(), existingRows, missingRows, errors, formulaMetadata);
        return new PreviewResult(read.fileName(), read.sha256(), read.sheetName(), output,
                detected.size(), existingRows, missingRows, errors, formulaMetadata, previewFingerprint, sourceRows);
    }

    /** Source rows are a review projection, never the normalized commands used for writes. */
    private static Map<String, Object> reviewExcelData(RowInput input, PreviewOptions options, List<StoreTax> taxes) {
        Map<String, Object> values = new LinkedHashMap<>(input.data());
        for (String field : List.of("offerActive", "productType", "priceUseMode", "discountType", "taxId", "taxesIncluded")) {
            String value = "discountType".equals(field)
                    ? effective(input.data(), options, "prohibitedDiscount", "discountType") : effective(input.data(), options, field);
            if (value != null) values.put(field, value);
        }
        Object child = values.get("subfamilyId");
        if (child != null && !String.valueOf(child).isBlank()) values.put("familyId", child);
        UUID tax = parseUuid(String.valueOf(values.get("taxId")));
        if (tax != null) taxes.stream().filter(item -> item.getId().equals(tax)).findFirst()
                .ifPresent(item -> values.put("taxId", item.getPercentage().stripTrailingZeros().toPlainString() + "%"));
        return values;
    }

    private static Map<String, Object> reviewDatabaseData(Map<String, Object> database, List<Family> families,
            List<Subfamily> subfamilies, List<StoreTax> taxes) {
        if (database == null) return null;
        Map<String, Object> values = new LinkedHashMap<>(database);
        UUID parent = parseUuid(String.valueOf(database.get("familyId")));
        UUID child = parseUuid(String.valueOf(database.get("subfamilyId")));
        families.stream().filter(item -> item.getId().equals(parent)).findFirst().ifPresent(item -> values.put("familyId", item.getFamilyCode()));
        subfamilies.stream().filter(item -> item.getId().equals(child)).findFirst().ifPresent(item -> values.put("familyId", item.getSubfamilyCode()));
        UUID tax = parseUuid(String.valueOf(database.get("taxId")));
        taxes.stream().filter(item -> item.getId().equals(tax)).findFirst()
                .ifPresent(item -> values.put("taxId", item.getPercentage().stripTrailingZeros().toPlainString() + "%"));
        values.computeIfPresent("priceUseMode", (key, value) -> switch (String.valueOf(value)) {
            case "NORMAL" -> "1"; case "MEMBER_PRICE" -> "2"; case "OFFER_PRICE" -> "3"; case "OFFER_DISCOUNT" -> "4"; default -> value;
        });
        return values;
    }

    /** Reads the original grid and applies only validated session cell edits; no mapping, catalogue or DB resolution. */
    public ProductExcelImportReadService.ReadResult readRaw(MultipartFile file, PreviewRequest request) {
        ProductExcelImportReadService.ReadResult read = reader.read(file);
        if (request != null && !blank(request.expectedSha256()) && !request.expectedSha256().equalsIgnoreCase(read.sha256())) {
            throw new ProductExcelImportReadService.ProductExcelImportException("FILE_CHANGED",
                    "El contenido del fichero ha cambiado desde la lectura", null, null, "sha256", request.expectedSha256(), null);
        }
        List<ImportError> errors = validateEdits(request == null ? List.of() : request.edits(), read.rows(), read.formulas());
        if (!errors.isEmpty()) {
            ImportError error = errors.getFirst();
            throw new ProductExcelImportReadService.ProductExcelImportException(error.code(), error.reason(), error.row(), error.column(),
                    error.attribute(), error.receivedValue(), null);
        }
        List<List<ProductExcelImportReadService.CellView>> edited = editableRows(read, request == null ? List.of() : request.edits());
        return new ProductExcelImportReadService.ReadResult(read.fileName(), read.sha256(), read.sheetName(), edited,
                read.formulas(), read.nonEmptyRows(), read.columns(), read.nonEmptyCells());
    }

    private static PreviewRequest withoutStockQuantity(PreviewRequest request) {
        if (request.options() == null || !"STOCK".equals(canonicalContext(request.options().context()))) return request;
        Map<String, String> mapping = new LinkedHashMap<>(request.mapping() == null ? Map.of() : request.mapping());
        mapping.remove("quantity");
        Map<String, Boolean> updates = new LinkedHashMap<>(request.updateFields() == null ? Map.of() : request.updateFields());
        updates.remove("quantity");
        var options = request.options();
        return new PreviewRequest(mapping, request.edits(), new PreviewOptions(options.globalValues(), options.valueSources(),
                options.showOnlyImported(), options.context(), options.storeId(), options.companyId(), options.skipZeroPriceUpdate(),
                false, options.documentPriceSource()), request.storeId(), request.companyId(), request.expectedSha256(),
                request.startRow(), null, updates, request.resolvedProducts());
    }

    private static void requireContextPermission(PreviewOptions options) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || options == null || options.context() == null) return;
        boolean allowed = switch (options.context()) {
            case "STOCK" -> com.tpverp.backend.security.application.PermissionChecks.hasProductManagement(authentication);
            case "WAREHOUSE_INPUT", "WAREHOUSE_OUTPUT" ->
                    com.tpverp.backend.security.application.PermissionChecks.hasWarehouseManagement(authentication);
            default -> true;
        };
        if (!allowed) throw new AccessDeniedException("Permiso insuficiente para previsualizar la importacion");
    }

    private PreviewRow classify(RowInput input, Map<String, String> mapping, Map<String, Set<UUID>> identityIds,
            Map<UUID, Product> productById, UUID storeId, PreviewOptions options, Map<String, Boolean> updateFields,
            List<StoreTax> activeTaxes, Map<String, UUID> familyReferences, Map<String, SubfamilyRef> subfamilyReferences,
            UUID defaultFamilyId, LogicalIdentityOwners logicalOwners, ErrorBudget errorBudget) {
        Map<String, String> data = canonicalizeIdentityFields(input.data());
        BoundedRowErrors errors = new BoundedRowErrors(errorBudget);
        Product product = input.product() != null ? input.product()
                : resolveProduct(data, identityIds, productById, input.rowNumber(), errors);
        validateCellProvenance(input, mapping, product, options, updateFields, errors);
        validateBarcode2(data, input.rowNumber(), product, updateFields, logicalOwners, errors);
        validateValues(data, mapping, input.mappedCells(), input.rowNumber(), storeId, product, options,
                updateFields, errors, activeTaxes, familyReferences, subfamilyReferences, defaultFamilyId);
        if (product == null && blank(data.get("code")) && blank(data.get("barcode"))) {
            errors.add(error("IDENTIFIER_REQUIRED", input.rowNumber(), firstColumn(mapping, "name"), "name",
                    data.get("name"), "La fila tiene nombre pero no codigo ni codigo de barras",
                    "Código o código de barras", "Asigna un identificador al producto"));
        }
        String classification = errors.hadError() ? "ERROR" : product == null ? "MISSING" : "EXISTING";
        Map<String, Object> database = product == null ? null : productSnapshot(product);
        Map<String, Object> changes = product == null ? Map.of() : changes(product, data, updateFields, options);
        Map<String, Object> excel = new LinkedHashMap<>();
        excel.putAll(data);
        return new PreviewRow(input.rowNumber(), List.of(input.rowNumber()), classification, excel, database,
                product == null ? null : product.getVersion(), changes, errors, purchasePriceDiffers(product, data, input.mappedCells()));
    }

    private static boolean purchasePriceDiffers(Product product, Map<String, String> data,
            Map<String, ProductExcelImportReadService.CellView> cells) {
        if (product == null || blank(data.get("purchasePrice"))) return false;
        try {
            BigDecimal value = parseFormattedNumber(data.get("purchasePrice"));
            return product.getPurchasePrice() == null || value.compareTo(product.getPurchasePrice()) != 0;
        } catch (NumberFormatException ignored) { return false; }
    }

    private Product resolveProduct(Map<String, String> data, Map<String, Set<UUID>> identityIds,
            Map<UUID, Product> productById, int row, List<ImportError> errors) {
        Set<UUID> candidates = new LinkedHashSet<>();
        for (String field : List.of("code", "barcode")) {
            String value = normalized(data.get(field));
            if (!value.isBlank()) candidates.addAll(identityIds.getOrDefault(value, Set.of()));
        }
        if (candidates.size() > 1) {
            errors.add(error("PRODUCT_AMBIGUOUS", row, null, null, null,
                    "Los identificadores de la fila pertenecen a productos distintos", "un unico producto",
                    "Corrige Código o Código de barras"));
            return null;
        }
        return candidates.stream().findFirst().map(productById::get).orElse(null);
    }

    private void validateValues(Map<String, String> data, Map<String, String> mapping,
            Map<String, ProductExcelImportReadService.CellView> mappedCells, int row, UUID storeId,
            Product product, PreviewOptions options, Map<String, Boolean> updateFields, List<ImportError> errors, List<StoreTax> activeTaxes,
            Map<String, UUID> familyReferences, Map<String, SubfamilyRef> subfamilyReferences, UUID defaultFamilyId) {
        String priceUse = effective(data, options, "priceUseMode");
        String prohibited = effective(data, options, "prohibitedDiscount", "discountType");
        String taxesIncluded = effective(data, options, "taxesIncluded");
        String normalizedPriceUse = normalizePriceUse(priceUse);
        validateTextLengths(data, mapping, row, product, options, updateFields, errors);
        boolean priceUseApplies = product == null || applies(updateFields, "priceUseMode");
        boolean prohibitedApplies = product == null || (applies(updateFields, "discountType") || applies(updateFields, "prohibitedDiscount"));
        if (priceUseApplies && !blank(priceUse) && normalizedPriceUse == null) {
            errors.add(error("INVALID_PRICE_MODE", row, firstColumn(mapping, "priceUseMode"), "priceUseMode", priceUse,
                    "El modo de precio no es valido", "1 NORMAL, 2 MEMBER_PRICE, 3 OFFER_PRICE, 4 OFFER_DISCOUNT",
                    "Usa uno de los modos aceptados"));
        } else if (!blank(priceUse)) data.put("priceUseMode", normalizedPriceUse);
        if (!blank(prohibited) && Set.of("0", "1").contains(prohibited.trim())) {
            data.put("discountType", prohibited.trim());
            data.put("prohibitedDiscount", prohibited.trim());
        }
        if (!blank(taxesIncluded) && Set.of("0", "1").contains(taxesIncluded.trim())) data.put("taxesIncluded", taxesIncluded.trim());
        if (prohibitedApplies && !blank(prohibited) && !Set.of("0", "1").contains(prohibited.trim())) {
            errors.add(error("INVALID_BOOLEAN", row, firstColumn(mapping, "discountType"), "discountType", prohibited,
                    "El valor booleano no es valido", "0 o 1", "Usa 0 para No o 1 para Si"));
        }
        if ((product == null || applies(updateFields, "taxesIncluded")) && !blank(taxesIncluded) && !Set.of("0", "1").contains(taxesIncluded.trim())) {
            errors.add(error("INVALID_BOOLEAN", row, firstColumn(mapping, "taxesIncluded"), "taxesIncluded", taxesIncluded,
                    "El valor booleano no es valido", "0 o 1", "Usa 0 para No o 1 para Si"));
        }
        String offerActive = effective(data, options, "offerActive");
        if ((product == null || applies(updateFields, "offerActive")) && !blank(offerActive) && !Set.of("0", "1").contains(offerActive.trim())) {
            errors.add(error("INVALID_BOOLEAN", row, firstColumn(mapping, "offerActive"), "offerActive", offerActive,
                    "Oferta activa debe ser un booleano válido", "0 o 1", "Usa 0 para No o 1 para Si"));
        }
        if (!blank(offerActive) && Set.of("0", "1").contains(offerActive.trim())) data.put("offerActive", offerActive.trim());
        String productType = normalizeProductType(effective(data, options, "productType"));
        if ((product == null || applies(updateFields, "productType")) && !blank(productType) && !Set.of("UNIT", "SERVICE", "WEIGHT").contains(productType.trim().toUpperCase(Locale.ROOT))) {
            errors.add(error("PRODUCT_TYPE_INVALID", row, firstColumn(mapping, "productType"), "productType", productType,
                    "El tipo de producto no es válido", "1 = Unidad (UNIT), 2 = Peso (WEIGHT), 3 = Servicio (SERVICE)", "Corrige el tipo de producto"));
        } else if (!blank(productType)) data.put("productType", productType.trim().toUpperCase(Locale.ROOT));
        String resultingPriceUse = normalizePriceUse(priceUse);
        if (resultingPriceUse == null && product != null && product.getPriceUseMode() != null) {
            resultingPriceUse = product.getPriceUseMode().name();
        }
        String resultingProhibited = prohibited;
        if (blank(resultingProhibited) && product != null && product.getDiscountType() == com.tpverp.backend.catalog.DiscountType.NONE) {
            resultingProhibited = "1";
        }
        if (!priceUseApplies && product != null && product.getPriceUseMode() != null) resultingPriceUse = product.getPriceUseMode().name();
        if (!prohibitedApplies && product != null) resultingProhibited = product.getDiscountType() == com.tpverp.backend.catalog.DiscountType.NONE ? "1" : "0";
        if ("1".equals(resultingProhibited) && resultingPriceUse != null && !"NORMAL".equals(resultingPriceUse)) {
            errors.add(error("DISCOUNT_PROHIBITED_PRICE_MODE", row, firstColumn(mapping, "priceUseMode"), "priceUseMode",
                    resultingPriceUse, "No se permite un precio especial con descuento prohibido", "NORMAL",
                    "Selecciona Precio normal o cambia Prohibido descuento a 0"));
        }
        for (String field : List.of("purchasePrice", "purchaseDiscountPercent", "salePrice", "memberPrice",
                "wholesalePrice", "offerPrice", "offerDiscountPercent", "packageQuantity", "stockMin", "stockMax", "quantity")) {
            String value = data.get(field);
            boolean warehouseLineValue = requiresWarehouseLineValidation(options, field);
            if (!blank(value) && (field.equals("quantity") || warehouseLineValue
                    || product == null || applies(updateFields, field))) {
                ProductExcelImportReadService.CellView sourceCell = mappedCells.get(field);
                boolean numericSource = sourceCell != null && sourceCell.numeric();
                BigDecimal number = parseDecimal(value, field, row, firstColumn(mapping, field), errors,
                        numericSource);
                if (number != null) {
                    String canonical = number.toPlainString();
                    data.put(field, canonical);
                    boolean persistsMasterField = product == null || applies(updateFields, field);
                    if (Set.of("memberPrice", "wholesalePrice", "offerPrice").contains(field)
                            && number.signum() == 0
                            && persistsMasterField
                            && !Boolean.TRUE.equals(options != null && options.skipZeroPriceUpdate())) {
                        errors.add(error("ZERO_PRICE_INVALID", row, firstColumn(mapping, field), field, value,
                                "Los precios opcionales no pueden ser cero", "Vacío o precio mayor o igual que 0,01",
                                "Usa un precio válido o activa No actualizar cuando precio 0"));
                    }
                }
            }
        }
        if ("1".equals(resultingProhibited)) {
            Map<String, BigDecimal> specialPrices = new LinkedHashMap<>();
            BigDecimal memberPrice = resultingOptionalPrice(product, data, updateFields, options,
                    "memberPrice", product == null ? null : product.getMemberPrice());
            BigDecimal offerPrice = resultingOptionalPrice(product, data, updateFields, options,
                    "offerPrice", product == null ? null : product.getOfferPrice());
            BigDecimal offerDiscount = resultingOptionalPrice(product, data, updateFields, null,
                    "offerDiscountPercent", product == null ? null : product.getOfferDiscountPercent());
            if (memberPrice != null) specialPrices.put("memberPrice", memberPrice);
            if (offerPrice != null) specialPrices.put("offerPrice", offerPrice);
            if (offerDiscount != null) specialPrices.put("offerDiscountPercent", offerDiscount);
            if (!specialPrices.isEmpty()) {
                errors.add(error("DISCOUNT_PROHIBITED_PRICE_MODE", row, firstColumn(mapping, "discountType"),
                        "discountType", String.join(", ", specialPrices.keySet()),
                        "Un producto con descuento prohibido no puede conservar precios de miembro u oferta",
                        "NORMAL sin precio de miembro, precio de oferta ni descuento de oferta",
                        "Elimina los precios especiales o cambia Prohibido descuento a 0"));
            }
        }
        LocalDate from = parseDate(data.get("offerFrom"));
        LocalDate until = parseDate(data.get("offerUntil"));
        boolean fromApplies = product == null || applies(updateFields, "offerFrom");
        boolean untilApplies = product == null || applies(updateFields, "offerUntil");
        if (fromApplies && !blank(data.get("offerFrom")) && from == null) dateError(data, mapping, row, "offerFrom", errors);
        if (untilApplies && !blank(data.get("offerUntil")) && until == null) dateError(data, mapping, row, "offerUntil", errors);
        if (from != null) data.put("offerFrom", from.toString());
        if (until != null) data.put("offerUntil", until.toString());
        LocalDate resultingFrom = product != null && (!fromApplies || blank(data.get("offerFrom")))
                ? product.getOfferFrom() : from;
        LocalDate resultingUntil = product != null && (!untilApplies || blank(data.get("offerUntil")))
                ? product.getOfferUntil() : until;
        if (resultingFrom != null && resultingUntil != null && resultingUntil.isBefore(resultingFrom)) {
            errors.add(error("DATE_RANGE_INVALID", row, firstColumn(mapping, "offerUntil"), "offerUntil", data.get("offerUntil"),
                    "Oferta hasta no puede ser anterior a Oferta desde", "Oferta hasta >= Oferta desde",
                    "Corrige el rango de fechas"));
        }
        if ("OFFER_PRICE".equals(resultingPriceUse) || "OFFER_DISCOUNT".equals(resultingPriceUse)) {
            LocalDate offerStart = product != null && (!applies(updateFields, "offerFrom") || blank(data.get("offerFrom")))
                    ? product.getOfferFrom() : from;
            BigDecimal offerAmount = resultingOptionalPrice(product, data, updateFields, options,
                    "offerPrice", product == null ? null : product.getOfferPrice());
            BigDecimal offerPercent = product != null && (!applies(updateFields, "offerDiscountPercent") || blank(data.get("offerDiscountPercent")))
                    ? product.getOfferDiscountPercent() : decimal(data.get("offerDiscountPercent"));
            if (offerStart == null || ("OFFER_PRICE".equals(resultingPriceUse) && offerAmount == null)
                    || ("OFFER_DISCOUNT".equals(resultingPriceUse) && offerPercent == null)) {
                errors.add(error("OFFER_REQUIRED", row, firstColumn(mapping, "priceUseMode"), "priceUseMode", resultingPriceUse,
                        "El modo de oferta necesita fecha y valor de oferta en el estado resultante",
                        "Oferta desde y Precio de oferta o Descuento oferta %", "Completa los campos de oferta o selecciona Precio normal"));
            }
            if ("OFFER_DISCOUNT".equals(resultingPriceUse) && offerPercent != null
                    && offerPercent.compareTo(BigDecimal.valueOf(100)) == 0) {
                errors.add(error("ZERO_PRICE_INVALID", row, firstColumn(mapping, "offerDiscountPercent"),
                        "offerDiscountPercent", data.get("offerDiscountPercent"),
                        "El descuento de oferta del 100% produce un precio de oferta cero no permitido",
                        "Descuento de oferta menor que 100%", "Reduce el descuento o selecciona otro modo de precio"));
            }
        }
        boolean resultingOfferActive = product == null
                ? "1".equals(data.get("offerActive"))
                : (applies(updateFields, "offerActive") && !blank(data.get("offerActive"))
                        ? "1".equals(data.get("offerActive")) : product.isOfferActive());
        resultingOfferActive |= "OFFER_PRICE".equals(resultingPriceUse) || "OFFER_DISCOUNT".equals(resultingPriceUse);
        if (resultingOfferActive) {
            BigDecimal resultingOfferPrice = product == null || applies(updateFields, "offerPrice")
                    ? (blank(data.get("offerPrice")) && product != null ? product.getOfferPrice() : decimal(data.get("offerPrice")))
                    : product.getOfferPrice();
            BigDecimal resultingOfferDiscount = product == null || applies(updateFields, "offerDiscountPercent")
                    ? (blank(data.get("offerDiscountPercent")) && product != null
                            ? product.getOfferDiscountPercent() : decimal(data.get("offerDiscountPercent")))
                    : product.getOfferDiscountPercent();
            LocalDate resultingOfferFrom = product == null || applies(updateFields, "offerFrom")
                    ? (blank(data.get("offerFrom")) && product != null ? product.getOfferFrom() : from)
                    : product.getOfferFrom();
            boolean hasOfferValue = "OFFER_DISCOUNT".equals(resultingPriceUse)
                    ? resultingOfferDiscount != null : resultingOfferPrice != null;
            if (!hasOfferValue || resultingOfferFrom == null) {
                errors.add(error("OFFER_REQUIRED", row, firstColumn(mapping, "offerActive"), "offerActive", "1",
                        "Una oferta activa necesita precio de oferta y fecha inicial",
                        "Precio de oferta y Oferta desde", "Completa los datos de oferta o desactiva la oferta"));
            }
        }
        boolean taxApplies = product == null || applies(updateFields, "taxId");
        String taxValue = effective(data, options, "taxId");
        if (!blank(taxValue)) {
            List<ImportError> taxErrors = taxApplies ? errors : new ArrayList<>();
            String taxId = resolveTax(storeId, taxValue, row, firstColumn(mapping, "taxId"), taxErrors, activeTaxes);
            if (taxId != null) data.put("taxId", taxId);
        }
        else if (product == null) {
            StoreTax defaultTax = activeTaxes.stream().filter(StoreTax::isDefaultTax).findFirst().orElse(null);
            if (defaultTax != null) data.put("taxId", defaultTax.getId().toString());
            else errors.add(error("TAX_REQUIRED", row, firstColumn(mapping, "taxId"), "taxId", null,
                    "El impuesto es obligatorio para crear un producto y no hay predeterminado activo",
                    "UUID o porcentaje activo de la tienda", "Selecciona un impuesto válido o configura el predeterminado"));
        }
        boolean familyApplies = product == null || applies(updateFields, "familyId");
        String familyValue = effective(data, options, "familyId");
        // The combined control uses the same operational codes as the product form.
        // Explicit legacy two-column mappings retain their independent semantics.
        String businessCode = normalizeReference(familyValue);
        boolean combinedFamily = !mapping.containsKey("subfamilyId") && businessCode.matches("[0-9]{3}(?:[0-9]{3})?");
        if (familyApplies && combinedFamily) {
            UUID parentId = resolveReference(businessCode.substring(0, 3), familyReferences);
            SubfamilyRef child = businessCode.length() == 6 ? subfamilyReferences.get(businessCode) : null;
            if (parentId == null || (businessCode.length() == 6 && (child == null || child.subfamily() == null
                    || !parentId.equals(child.subfamily().getFamilyId())))) {
                errors.add(error("FAMILY_UNKNOWN", row, firstColumn(mapping, "familyId"), "familyId", familyValue,
                        "El código no identifica una familia o subfamilia válida de la tienda",
                        "3 dígitos: familia sin subfamilia; 6 dígitos: familia y subfamilia",
                        "Comprueba el código completo, incluidos los ceros iniciales"));
            } else {
                data.put("familyId", parentId.toString());
                data.put("subfamilyId", child == null ? "" : child.subfamily().getId().toString());
                data.put("familyBusinessCode", businessCode);
            }
        } else if (familyApplies && blank(familyValue) && product == null && defaultFamilyId != null) {
            data.put("familyId", defaultFamilyId.toString());
        } else if (familyApplies && !blank(familyValue)) {
            UUID familyId = resolveReference(familyValue, familyReferences);
            if (familyId == null) {
                String code = familyReferences.containsKey(normalizeReference(familyValue)) ? "FAMILY_AMBIGUOUS" : "FAMILY_UNKNOWN";
                errors.add(error(code, row, firstColumn(mapping, "familyId"), "familyId", familyValue,
                        "La familia no pertenece a la tienda activa o su código es ambiguo", "UUID o código único de familia de la tienda", "Selecciona una familia válida"));
            } else data.put("familyId", familyId.toString());
        } else if (product == null) {
            errors.add(error("FAMILY_REQUIRED", row, firstColumn(mapping, "familyId"), "familyId", null,
                    "La familia es obligatoria para crear un producto y no hay predeterminada", "UUID de familia", "Configura una familia predeterminada"));
        }
        boolean subfamilyApplies = product == null || applies(updateFields, "subfamilyId");
        String subfamilyValue = effective(data, options, "subfamilyId");
        if (!combinedFamily && subfamilyApplies && !blank(subfamilyValue)) {
            SubfamilyRef subfamilyReference = subfamilyReferences.get(normalizeReference(subfamilyValue));
            UUID subfamilyId = subfamilyReference == null || subfamilyReference.subfamily() == null
                    ? parseUuid(subfamilyValue) : subfamilyReference.subfamily().getId();
            if (subfamilyReference != null && subfamilyReference.subfamily() == null) {
                errors.add(error("SUBFAMILY_AMBIGUOUS", row, firstColumn(mapping, "subfamilyId"), "subfamilyId", subfamilyValue,
                        "El código de subfamilia pertenece a varias familias", "Un único código operativo de subfamilia", "Corrige el código de subfamilia"));
            } else if (subfamilyId == null || (subfamilies != null && subfamilyReference == null)) {
                errors.add(error("SUBFAMILY_UNKNOWN", row, firstColumn(mapping, "subfamilyId"), "subfamilyId", subfamilyValue,
                        "La subfamilia no pertenece a la tienda activa", "UUID de una subfamilia de la tienda", "Selecciona una subfamilia válida"));
            } else {
                UUID resultingFamily = familyApplies ? parseUuid(data.get("familyId")) : null;
                if (resultingFamily == null && product != null) resultingFamily = product.getFamilyId();
                UUID subfamilyFamily = subfamilyReference == null ? null : subfamilyReference.subfamily().getFamilyId();
                if (resultingFamily != null && subfamilyFamily != null && !resultingFamily.equals(subfamilyFamily)) {
                    errors.add(error("SUBFAMILY_FAMILY_MISMATCH", row, firstColumn(mapping, "subfamilyId"), "subfamilyId", subfamilyValue,
                            "La subfamilia no pertenece a la familia resultante", "Subfamilia de la familia seleccionada", "Corrige Familia o Subfamilia"));
                } else data.put("subfamilyId", subfamilyId.toString());
            }
        }
        if (product == null && blank(data.get("name"))) {
            errors.add(error("NAME_REQUIRED", row, firstColumn(mapping, "name"), "name", null,
                    "El nombre es obligatorio para crear un producto", "Texto no vacio", "Completa Nombre para el alta"));
        }
        if (options != null && Boolean.TRUE.equals(options.requireQuantity()) && blank(data.get("quantity"))) {
            errors.add(error("QUANTITY_REQUIRED", row, firstColumn(mapping, "quantity"), "quantity", null,
                    "Este consumidor exige una cantidad por línea", "Cantidad decimal mayor que 0", "Mapea y completa Cantidad"));
        }
        BigDecimal min = product != null && (!applies(updateFields, "stockMin") || blank(data.get("stockMin")))
                ? product.getStockMin() : decimal(data.get("stockMin"));
        BigDecimal max = product != null && (!applies(updateFields, "stockMax") || blank(data.get("stockMax")))
                ? product.getStockMax() : decimal(data.get("stockMax"));
        if (min != null && max != null && max.compareTo(min) < 0) {
            errors.add(error("STOCK_RANGE_INVALID", row, firstColumn(mapping, "stockMax"), "stockMax", data.get("stockMax"),
                    "El stock máximo no puede ser menor que el stock mínimo", "stockMax >= stockMin",
                    "Corrige los límites de stock"));
        }
    }

    private static void validateTextLengths(Map<String, String> data, Map<String, String> mapping,
            int row, Product product, PreviewOptions options, Map<String, Boolean> updateFields, List<ImportError> errors) {
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (!validatesField(product, options, updateFields, entry.getKey())) continue;
            if (blank(entry.getValue())) continue;
            String lengthValue = isIdentityField(entry.getKey())
                    ? CatalogText.canonicalIdentity(entry.getValue()) : entry.getValue();
            int limit = "name".equals(entry.getKey()) ? 255
                    : Set.of("code", "barcode", "barcode2", "supplierReference").contains(entry.getKey()) ? 128 : 32_767;
            if (lengthValue != null && lengthValue.length() > limit) {
                errors.add(error("FIELD_LENGTH_INVALID", row, firstColumn(mapping, entry.getKey()), entry.getKey(),
                        entry.getValue().substring(0, Math.min(entry.getValue().length(), 256)),
                        "El valor supera la longitud máxima del campo",
                        "name <= 255; identificadores/referencia <= 128", "Acorta el valor antes de importar"));
            }
        }
    }

    private String resolveTax(UUID storeId, String value, int row, Integer column, List<ImportError> errors, List<StoreTax> activeTaxes) {
        try {
            UUID id = UUID.fromString(value.trim());
            StoreTax tax = activeTaxes.stream().filter(candidate -> candidate.getId().equals(id)).findFirst().orElse(null);
            if (tax == null || !storeId.equals(tax.getStoreId())) throw new IllegalArgumentException();
            return tax.getId().toString();
        } catch (IllegalArgumentException ignored) {
            // A percentage is also accepted, but must resolve to one active tax in this store.
        }
        BigDecimal percentage;
        try { percentage = parseFormattedNumber(value); }
        catch (NumberFormatException exception) {
            errors.add(error("TAX_UNKNOWN", row, column, "taxId", value, "El impuesto no es un UUID ni porcentaje valido",
                    "UUID o porcentaje activo", "Selecciona un impuesto de la tienda"));
            return null;
        }
        List<StoreTax> matches = activeTaxes.stream().filter(tax -> tax.getPercentage().compareTo(percentage) == 0).toList();
        if (matches.size() != 1) errors.add(error(matches.isEmpty() ? "TAX_UNKNOWN" : "TAX_AMBIGUOUS", row, column, "taxId", value,
                matches.isEmpty() ? "No existe un impuesto activo con ese porcentaje" : "Hay varios impuestos activos con ese porcentaje",
                "Un unico porcentaje activo", "Revisa la configuración de impuestos de la tienda"));
        return matches.size() == 1 ? matches.get(0).getId().toString() : null;
    }

    private Map<String, Set<UUID>> identityIds(UUID storeId, List<RowInput> rows) {
        Set<String> values = rows.stream().flatMap(row -> java.util.stream.Stream.of(
                        row.data().get("code"), row.data().get("barcode"), row.data().get("barcode2")))
                .filter(Objects::nonNull).map(ProductExcelImportPreviewService::normalized).filter(value -> !value.isBlank()).collect(Collectors.toSet());
        if (values.isEmpty()) return Map.of();
        return identifiers.findAllByStoreIdAndValorLowerIn(storeId, values).stream()
                .collect(Collectors.groupingBy(identifier -> normalized(identifier.getValue()), LinkedHashMap::new,
                        Collectors.mapping(ProductIdentifier::getProductId, Collectors.toCollection(LinkedHashSet::new))));
    }

    private static void validateBarcode2(Map<String, String> data, int row, Product product,
            Map<String, Boolean> updateFields, LogicalIdentityOwners logicalOwners, List<ImportError> errors) {
        String value = normalized(data.get("barcode2"));
        if (value.isBlank()) return;
        if (product != null && !applies(updateFields, "barcode2")) return;
        // CatalogService deliberately permits code==barcode as the one
        // primary-identifier exception. A secondary barcode has no such
        // exception: it must never equal either primary identifier, including
        // when the row resolves to an existing product.
        if (value.equals(normalized(data.get("code")))
                || value.equals(normalized(data.get("barcode")))) {
            errors.add(error("IDENTIFIER_DUPLICATE", row, null, "barcode2", data.get("barcode2"),
                    "El codigo de barras secundario no puede coincidir con el codigo o codigo de barras principal",
                    "Un codigo de barras secundario distinto de code y barcode",
                    "Corrige el codigo de barras secundario antes de importar"));
            return;
        }
        String owner = logicalOwners.rowOwners().get(row);
        Set<String> owners = new LinkedHashSet<>();
        owners.addAll(logicalOwners.barcode2Owners().getOrDefault(value, Set.of()));
        owners.addAll(logicalOwners.primaryOwners().getOrDefault(value, Set.of()));
        owners.remove(owner);
        if (product != null && value.equals(normalized(product.getBarcode2()))) {
            // A persisted secondary barcode may be repeated by rows that
            // resolve to that same product. Primary identifiers never get
            // this exception: barcode2 must remain globally unique.
            owners.remove("product:" + product.getId());
        }
        if (!owners.isEmpty()) {
            errors.add(error("IDENTIFIER_DUPLICATE", row, null, "barcode2", data.get("barcode2"),
                    "El codigo de barras secundario ya identifica otro producto o aparece repetido",
                    "Un unico codigo de barras secundario por producto",
                    "Corrige el codigo de barras secundario antes de importar"));
        }
    }

    /**
     * Resolves the same logical owner rules used by duplicate merging before
     * validating barcode2. Repeating a secondary barcode inside one product
     * group is harmless; sharing it with another product or primary identity
     * is a uniqueness violation.
     */
    private static LogicalIdentityOwners logicalIdentityOwners(List<RowInput> rows,
            Map<String, Set<UUID>> identityIds) {
        int[] parent = new int[rows.size()];
        java.util.Arrays.setAll(parent, index -> index);
        Map<String, Integer> keyOwners = new HashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            for (String key : primaryIdentityKeys(rows.get(index), identityIds)) {
                Integer owner = keyOwners.putIfAbsent(key, index);
                if (owner != null) union(parent, owner, index);
            }
        }
        Map<Integer, String> rowOwners = new HashMap<>();
        Map<String, Set<String>> primaryOwners = new HashMap<>();
        Map<String, Set<String>> barcode2Owners = new HashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            String owner = "group:" + find(parent, index);
            rowOwners.put(rows.get(index).rowNumber(), owner);
            for (String field : List.of("code", "barcode")) {
                String value = normalized(rows.get(index).data().get(field));
                if (!value.isBlank()) primaryOwners.computeIfAbsent(value, ignored -> new LinkedHashSet<>()).add(owner);
            }
            String secondary = normalized(rows.get(index).data().get("barcode2"));
            if (!secondary.isBlank()) barcode2Owners.computeIfAbsent(secondary, ignored -> new LinkedHashSet<>()).add(owner);
        }
        // Existing catalog identifiers participate in the same global namespace.
        for (Map.Entry<String, Set<UUID>> entry : identityIds.entrySet()) {
            String value = entry.getKey();
            for (UUID productId : entry.getValue()) {
                String owner = "product:" + productId;
                primaryOwners.computeIfAbsent(value, ignored -> new LinkedHashSet<>()).add(owner);
                barcode2Owners.computeIfAbsent(value, ignored -> new LinkedHashSet<>()).add(owner);
            }
        }
        return new LogicalIdentityOwners(rowOwners, primaryOwners, barcode2Owners);
    }

    private static Set<String> primaryIdentityKeys(RowInput row, Map<String, Set<UUID>> identityIds) {
        Set<String> keys = new LinkedHashSet<>();
        for (String field : List.of("code", "barcode")) {
            String value = normalized(row.data().get(field));
            if (!value.isBlank()) {
                keys.add("value:" + value);
                for (UUID productId : identityIds.getOrDefault(value, Set.of())) keys.add("product:" + productId);
            }
        }
        return keys;
    }

    private static Map<String, UUID> familyReferences(List<Family> values) {
        Map<String, UUID> result = new LinkedHashMap<>();
        for (Family family : values) {
            addReference(result, family.getId() == null ? null : family.getId().toString(), family.getId());
            addReference(result, family.getFamilyCode(), family.getId());
        }
        return result;
    }

    private static void addReference(Map<String, UUID> references, String reference, UUID id) {
        String key = normalizeReference(reference);
        if (!key.isBlank() && id != null) {
            UUID previous = references.putIfAbsent(key, id);
            if (previous != null && !previous.equals(id)) references.put(key, null);
        }
    }

    private List<PreviewRow> mergeDuplicates(List<PreviewRow> rows, Map<String, String> mapping) {
        return mergeDuplicates(rows, mapping, new ErrorBudget());
    }

    private List<PreviewRow> mergeDuplicates(List<PreviewRow> rows, Map<String, String> mapping, ErrorBudget errorBudget) {
        return mergeDuplicates(rows, mapping, errorBudget, false);
    }

    private List<PreviewRow> mergeDuplicates(List<PreviewRow> rows, Map<String, String> mapping, ErrorBudget errorBudget, boolean stockContext) {
        int[] parent = new int[rows.size()];
        java.util.Arrays.setAll(parent, index -> index);
        Map<String, Integer> owners = new HashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            for (String key : identityKeys(rows.get(index))) {
                Integer owner = owners.putIfAbsent(key, index);
                if (owner != null) union(parent, owner, index);
            }
        }
        Map<Integer, List<PreviewRow>> grouped = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) grouped.computeIfAbsent(find(parent, index), ignored -> new ArrayList<>()).add(rows.get(index));
        List<PreviewRow> result = new ArrayList<>();
        for (List<PreviewRow> group : grouped.values()) {
            PreviewRow first = group.get(0);
            if (group.size() == 1) { result.add(first); continue; }
            boolean same = group.stream().allMatch(row -> equivalentExceptQuantity(first, row))
                    && group.stream().noneMatch(row -> "ERROR".equals(row.classification()))
                    && group.stream().map(PreviewRow::classification).distinct().allMatch(classification ->
                            "EXISTING".equals(classification) || "MISSING".equals(classification));
            if (!same) {
                result.addAll(group.stream().map(row -> withError(row, error("DUPLICATE_CONFLICT", row.rowNumber(), null, null, null,
                        "El mismo producto aparece con datos distintos", "Valores idénticos salvo cantidad",
                        "Unifica las filas o corrige sus valores"), errorBudget)).toList());
                continue;
            }
            BigDecimal quantity = stockContext ? null : group.stream().map(row -> row.excelData().containsKey("quantity")
                            ? decimal(row.excelData().get("quantity")) : BigDecimal.ONE)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> data = new LinkedHashMap<>(first.excelData());
            // Missing quantity defaults to one; retain it when duplicate rows merge.
            if (!stockContext) data.put("quantity", quantity.toPlainString());
            List<Integer> rowNumbers = group.stream().flatMap(row -> row.rowNumbers().stream()).distinct().toList();
            PreviewRow existing = group.stream().filter(row -> "EXISTING".equals(row.classification())).findFirst().orElse(first);
            PreviewRow merged = new PreviewRow(first.rowNumber(), rowNumbers, existing.classification(), data,
                    existing.databaseData(), existing.version(), existing.changes(), existing.errors(),
                    group.stream().anyMatch(PreviewRow::purchasePriceChanged));
            ImportError quantityError = stockContext ? null : mergedQuantityError(quantity, first.rowNumber(), firstColumn(mapping, "quantity"));
            result.add(quantityError == null ? merged : withError(merged, quantityError, errorBudget));
        }
        return result;
    }

    /**
     * Every source quantity is validated before grouping, but adding otherwise
     * compatible rows can cross the NUMERIC(19,3) / @Digits(16,3) boundary.
     * Revalidate the aggregate so the preview blocks the batch before either
     * the Stock draft or a Warehouse document receives an unpersistable value.
     */
    private static ImportError mergedQuantityError(BigDecimal quantity, int row, Integer column) {
        BigDecimal normalized = quantity.stripTrailingZeros();
        if (normalized.scale() > 3) {
            return error("NUMBER_SCALE_INVALID", row, column, "quantity", quantity.toPlainString(),
                    "La cantidad sumada tiene más de tres decimales", "Hasta 3 decimales",
                    "Reduce o redondea las cantidades de las filas repetidas");
        }
        int integerDigits = Math.max(1, normalized.precision() - normalized.scale());
        if (integerDigits > 16) {
            return error("NUMBER_PRECISION_INVALID", row, column, "quantity", quantity.toPlainString(),
                    "La suma de cantidades repetidas supera la precisión máxima", "Hasta 16 enteros y 3 decimales",
                    "Divide la cantidad entre productos o documentos distintos");
        }
        return null;
    }

    private static Set<String> identityKeys(PreviewRow row) {
        Set<String> keys = new LinkedHashSet<>();
        if (row.databaseData() != null && row.databaseData().get("id") != null) keys.add("product:" + row.databaseData().get("id"));
        // Existing rows must remain connected to every imported identity as well.
        // Otherwise an existing code=A row with a newly supplied barcode=X could
        // be split from a second missing row identified by X and create a duplicate.
        Object code = row.excelData().get("code"); Object barcode = row.excelData().get("barcode");
        if (!blank(code == null ? null : code.toString())) keys.add("value:" + normalized(code.toString()));
        if (!blank(barcode == null ? null : barcode.toString())) keys.add("value:" + normalized(barcode.toString()));
        return keys;
    }

    private static int find(int[] parent, int value) { while (parent[value] != value) { parent[value] = parent[parent[value]]; value = parent[value]; } return value; }
    private static void union(int[] parent, int left, int right) { int a = find(parent, left); int b = find(parent, right); if (a != b) parent[b] = a; }

    private static boolean equivalentExceptQuantity(PreviewRow left, PreviewRow right) {
        Map<String, Object> a = new HashMap<>(left.excelData());
        Map<String, Object> b = new HashMap<>(right.excelData());
        a.remove("quantity"); b.remove("quantity");
        // Two rows resolved through different primary identities may describe
        // the same existing product. Primary identity columns are lookup keys,
        // while all other fields (including prices) must still agree.
        String leftProduct = left.databaseData() == null ? null : String.valueOf(left.databaseData().get("id"));
        String rightProduct = right.databaseData() == null ? null : String.valueOf(right.databaseData().get("id"));
        if (leftProduct != null && rightProduct != null && leftProduct.equals(rightProduct)) {
            a.remove("code"); a.remove("barcode");
            b.remove("code"); b.remove("barcode");
        }
        return a.equals(b) && (Objects.equals(left.classification(), right.classification())
                || (Set.of("EXISTING", "MISSING").contains(left.classification())
                        && Set.of("EXISTING", "MISSING").contains(right.classification())));
    }

    private static PreviewRow withError(PreviewRow row, ImportError error, ErrorBudget errorBudget) {
        List<ImportError> errors = new ArrayList<>(row.errors());
        if (errorBudget.reserve()) errors.add(error);
        return new PreviewRow(row.rowNumber(), row.rowNumbers(), "ERROR", row.excelData(), row.databaseData(), row.version(), row.changes(), errors, row.purchasePriceChanged(), null);
    }

    private static PreviewRow withConcurrencyToken(PreviewRow row, String sha256, UUID storeId, UUID companyId) {
        if ("ERROR".equals(row.classification())) {
            return row.concurrencyToken() == null ? row : new PreviewRow(row.rowNumber(), row.rowNumbers(), row.classification(), row.excelData(),
                    row.databaseData(), row.version(), row.changes(), row.errors(), row.purchasePriceChanged(), false, null);
        }
        Map<String, Object> canonicalPayload = new LinkedHashMap<>();
        canonicalPayload.put("expectedSha256", sha256 == null ? "" : sha256);
        canonicalPayload.put("storeId", storeId == null ? "" : storeId.toString());
        canonicalPayload.put("companyId", companyId == null ? "" : companyId.toString());
        canonicalPayload.put("productId", row.databaseData() == null ? "" : row.databaseData().getOrDefault("id", ""));
        canonicalPayload.put("version", row.version() == null ? "" : row.version());
        canonicalPayload.put("rowNumbers", row.rowNumbers() == null ? List.of()
                : row.rowNumbers().stream().sorted().toList());
        canonicalPayload.put("classification", row.classification());
        canonicalPayload.put("excelData", row.excelData());
        canonicalPayload.put("changes", row.changes());
        String canonical = canonicalValue(canonicalPayload);
        String token;
        try {
            token = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
        return new PreviewRow(row.rowNumber(), row.rowNumbers(), row.classification(), row.excelData(), row.databaseData(), row.version(),
                row.changes(), row.errors(), row.purchasePriceChanged(), "EXISTING".equals(row.classification()) && !row.changes().isEmpty(), token);
    }

    private static String canonicalValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> escape(String.valueOf(entry.getKey())) + ":" + canonicalValue(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(ProductExcelImportPreviewService::canonicalValue).collect(Collectors.joining(",", "[", "]"));
        }
        return escape(String.valueOf(value));
    }

    private static String previewFingerprint(String fileName, String sha256, String sheetName,
            List<PreviewRow> rows, long detectedRows, long existingRows, long missingRows,
            List<ImportError> errors, List<FormulaView> formulas) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileName", fileName);
        payload.put("sha256", sha256);
        payload.put("sheetName", sheetName);
        payload.put("detectedRows", detectedRows);
        payload.put("existingRows", existingRows);
        payload.put("missingRows", missingRows);
        payload.put("errors", errors.stream().map(ProductExcelImportPreviewService::errorFingerprintData).toList());
        payload.put("formulas", formulas.stream().map(formula -> Map.of(
                "cell", formula.cell() == null ? "" : formula.cell(),
                "formula", formula.formula() == null ? "" : formula.formula(),
                "calculatedValue", formula.calculatedValue() == null ? "" : formula.calculatedValue())).toList());
        payload.put("rows", rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rowNumber", row.rowNumber());
            item.put("rowNumbers", row.rowNumbers());
            item.put("classification", row.classification());
            item.put("excelData", row.excelData());
            item.put("databaseData", row.databaseData());
            item.put("version", row.version());
            item.put("changes", row.changes());
            item.put("errors", row.errors().stream().map(ProductExcelImportPreviewService::errorFingerprintData).toList());
            item.put("purchasePriceChanged", row.purchasePriceChanged());
            item.put("masterDataChanged", row.masterDataChanged());
            item.put("concurrencyToken", row.concurrencyToken());
            return item;
        }).toList());
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalValue(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static Map<String, Object> errorFingerprintData(ImportError error) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", error.code());
        item.put("row", error.row());
        item.put("column", error.column());
        item.put("attribute", error.attribute());
        item.put("receivedValue", error.receivedValue());
        item.put("reason", error.reason());
        item.put("acceptedValues", error.acceptedValues());
        item.put("recommendedFix", error.recommendedFix());
        return item;
    }

    private static String escape(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Map<String, Object> productSnapshot(Product product) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", product.getId().toString()); result.put("version", product.getVersion());
        result.put("code", product.getCode()); result.put("barcode", product.getBarcode()); result.put("name", product.getName());
        result.put("barcode2", product.getBarcode2());
        result.put("description", product.getDescription()); result.put("comments", product.getComments());
        result.put("familyId", product.getFamilyId()); result.put("subfamilyId", product.getSubfamilyId());
        result.put("productType", product.getProductType() == null ? null : product.getProductType().name()); result.put("purchasePrice", decimalSnapshot(product.getPurchasePrice()));
        result.put("purchaseDiscountPercent", decimalSnapshot(product.getPurchaseDiscountPercent()));
        result.put("salePrice", decimalSnapshot(product.getSalePrice())); result.put("memberPrice", decimalSnapshot(product.getMemberPrice()));
        result.put("wholesalePrice", decimalSnapshot(product.getWholesalePrice())); result.put("offerPrice", decimalSnapshot(product.getOfferPrice()));
        result.put("offerDiscountPercent", decimalSnapshot(product.getOfferDiscountPercent())); result.put("offerActive", product.isOfferActive() ? "1" : "0");
        result.put("offerFrom", product.getOfferFrom()); result.put("offerUntil", product.getOfferUntil());
        result.put("taxId", product.getTaxId()); result.put("taxesIncluded", product.isTaxesIncluded() ? "1" : "0");
        result.put("priceUseMode", product.getPriceUseMode() == null ? null : product.getPriceUseMode().name());
        String prohibitedDiscount = product.getDiscountType() == com.tpverp.backend.catalog.DiscountType.NONE ? "1" : "0";
        result.put("discountType", prohibitedDiscount); result.put("prohibitedDiscount", prohibitedDiscount);
        result.put("packageQuantity", decimalSnapshot(product.getPackageQuantity())); result.put("stockMin", decimalSnapshot(product.getStockMin())); result.put("stockMax", decimalSnapshot(product.getStockMax()));
        return result;
    }

    private static String decimalSnapshot(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static Map<String, Object> changes(Product product, Map<String, String> data, Map<String, Boolean> updateFields, PreviewOptions options) {
        Map<String, Object> before = productSnapshot(product); Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            boolean combinedSubfamily = "subfamilyId".equals(entry.getKey()) && combinedFamilyUpdate(data, updateFields);
            boolean zeroPrice = Boolean.TRUE.equals(options == null ? false : options.skipZeroPriceUpdate())
                    && Set.of("purchasePrice", "salePrice", "memberPrice", "wholesalePrice", "offerPrice").contains(entry.getKey())
                    && decimal(entry.getValue()) != null && decimal(entry.getValue()).signum() == 0;
            if (!zeroPrice && (!blank(entry.getValue()) || combinedSubfamily)
                    && (combinedSubfamily || updateFields == null || Boolean.TRUE.equals(updateFields.get(entry.getKey())))
                    && before.containsKey(entry.getKey())
                    && !(combinedSubfamily && blank(entry.getValue()) && before.get(entry.getKey()) == null)
                    && !valuesEqual(entry.getValue(), before.get(entry.getKey()))) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("before", before.get(entry.getKey()));
                change.put("after", entry.getValue());
                result.put(entry.getKey(), change);
            }
        }
        String resultingMode = applies(updateFields, "priceUseMode") && !blank(data.get("priceUseMode"))
                ? data.get("priceUseMode") : String.valueOf(before.get("priceUseMode"));
        if (("OFFER_PRICE".equals(resultingMode) || "OFFER_DISCOUNT".equals(resultingMode))
                && (result.containsKey("offerActive") || result.containsKey("priceUseMode"))) {
            // Catalog activates offer modes even when the workbook explicitly says 0.
            if (product.isOfferActive()) result.remove("offerActive");
            else result.put("offerActive", Map.of("before", "0", "after", "1"));
        }
        return result;
    }

    static boolean combinedFamilyUpdate(Map<String, ?> data, Map<String, Boolean> updateFields) {
        return applies(updateFields, "familyId") && data.get("familyBusinessCode") instanceof String code
                && code.matches("[0-9]{3}(?:[0-9]{3})?");
    }

    private static boolean valuesEqual(String excel, Object database) {
        if (database == null) return false;
        String db = database.toString();
        try { return new BigDecimal(excel.replace(',', '.')).compareTo(new BigDecimal(db)) == 0; }
        catch (NumberFormatException ignored) { return excel.equals(db); }
    }

    private static List<List<ProductExcelImportReadService.CellView>> editableRows(ProductExcelImportReadService.ReadResult read, List<CellEdit> edits) {
        List<List<ProductExcelImportReadService.CellView>> rows = read.rows().stream()
                .map(row -> new ArrayList<>(row)).collect(Collectors.toCollection(ArrayList::new));
        if (edits != null) for (CellEdit edit : edits) {
            if (edit == null) continue;
            int column = columnIndex(edit.column()); if (edit.row() != null && edit.row() > 0 && edit.row() <= rows.size() && column >= 0) {
                List<ProductExcelImportReadService.CellView> target = rows.get(edit.row() - 1);
                while (target.size() <= column) target.add(new ProductExcelImportReadService.CellView(null, null));
                if (Objects.equals(target.get(column).value(), edit.value())) continue;
                target.set(column, new ProductExcelImportReadService.CellView(edit.value(), null));
            }
        }
        return rows;
    }

    private static Map<String, String> values(List<ProductExcelImportReadService.CellView> row, Map<String, String> mapping) {
        Map<String, String> result = new LinkedHashMap<>();
        if (mapping != null) for (Map.Entry<String, String> entry : mapping.entrySet()) {
            int index = columnIndex(entry.getValue());
            if (index >= 0 && index < row.size()) {
                var cell = row.get(index);
                String value = cell == null ? null
                        : NUMERIC_IMPORT_FIELDS.contains(entry.getKey()) && cell.rawNumeric() != null
                        ? cell.rawNumeric() : cell.value();
                result.put(entry.getKey(), value == null ? "" : value.trim());
            } else result.put(entry.getKey(), "");
        }
        String reference = result.get("supplierReference");
        if (blank(reference)) result.put("supplierReference", blank(result.get("code")) ? result.get("barcode") : result.get("code"));
        return result;
    }

    private static Map<String, ProductExcelImportReadService.CellView> mappedCells(List<ProductExcelImportReadService.CellView> row,
            Map<String, String> mapping) {
        Map<String, ProductExcelImportReadService.CellView> result = new LinkedHashMap<>();
        if (mapping == null) return result;
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            int index = columnIndex(entry.getValue());
            if (index >= 0 && index < row.size()) {
                var cell = row.get(index);
                if (cell != null) result.put(entry.getKey(), cell);
            }
        }
        return result;
    }

    private static void validateCellProvenance(RowInput input, Map<String, String> mapping,
            Product product, PreviewOptions options, Map<String, Boolean> updateFields, List<ImportError> errors) {
        Set<String> percentageFields = Set.of("purchaseDiscountPercent", "offerDiscountPercent", "taxId");
        Set<String> integerSelectors = Set.of("priceUseMode", "discountType", "prohibitedDiscount", "taxesIncluded", "offerActive");
        for (Map.Entry<String, ProductExcelImportReadService.CellView> entry : input.mappedCells().entrySet()) {
            String field = entry.getKey();
            if (!validatesField(product, options, updateFields, field)) continue;
            if (!usesExcelCell(options, field)) continue;
            var cell = entry.getValue();
            if (cell == null) continue;
            if (cell.errorCode() != null) {
                String address = mapping.get(field) + input.rowNumber();
                errors.add(error(cell.errorCode(), input.rowNumber(), firstColumn(mapping, field), field,
                        cell.value(), "La celda " + address + " contiene un error de Excel o una fórmula sin resultado guardado",
                        "Valor válido o fórmula con resultado guardado sin errores",
                        "Corrige manualmente " + address + " en Excel, recalcula, guarda el archivo y vuelve a abrirlo para reintentar"));
                continue;
            }
            if (!cell.numeric() && NUMERIC_IMPORT_FIELDS.contains(field)
                    && !percentageFields.contains(field) && textualPercentage(cell.value())) {
                errors.add(error("PERCENTAGE_FORMAT_NOT_ALLOWED", input.rowNumber(), firstColumn(mapping, field), field,
                        input.data().get(field), "El porcentaje escrito como texto no es aplicable a este atributo",
                        "Porcentaje solo para descuentos o impuesto; formato numérico para el resto",
                        "Quita el signo % o asigna la columna a un atributo porcentual"));
            }
            if (!cell.numeric()) continue;
            if (cell.percentage() && !percentageFields.contains(field)) {
                errors.add(error("PERCENTAGE_FORMAT_NOT_ALLOWED", input.rowNumber(), firstColumn(mapping, field), field,
                        input.data().get(field), "El formato de porcentaje no es aplicable a este atributo",
                        "Porcentaje solo para descuentos o impuesto; formato numérico para el resto",
                        "Cambia el formato de la celda y vuelve a previsualizar"));
            }
            if (integerSelectors.contains(field) && !validRawInteger(cell.rawNumeric())) {
                String code = "priceUseMode".equals(field) ? "INVALID_PRICE_MODE" : "INVALID_BOOLEAN";
                errors.add(error(code, input.rowNumber(), firstColumn(mapping, field), field, input.data().get(field),
                        "El selector debe proceder de un entero numérico exacto",
                        "Un entero sin parte decimal, o una celda de Texto con el valor permitido",
                        "Corrige el valor o formatea la columna como Texto"));
            }
        }
        for (String field : List.of("familyId", "subfamilyId")) {
            if (!validatesField(product, options, updateFields, field)) continue;
            var cell = input.mappedCells().get(field);
            if (cell == null || !cell.numeric() || validRawInteger(cell.rawNumeric())) continue;
            if (cell != null && cell.numeric()) {
                errors.add(error(field.equals("familyId") ? "FAMILY_UNKNOWN" : "SUBFAMILY_UNKNOWN",
                        input.rowNumber(), firstColumn(mapping, field), field, input.data().get(field),
                        "La referencia numérica no conserva un entero no negativo fiable",
                        "Entero no negativo en la celda numérica o referencia formateada como Texto",
                        "Formatea la columna como Texto y vuelve a leer el fichero"));
            }
        }
        var productType = input.mappedCells().get("productType");
        if (validatesField(product, options, updateFields, "productType") && productType != null && productType.numeric()) {
            errors.add(error("PRODUCT_TYPE_INVALID", input.rowNumber(), firstColumn(mapping, "productType"),
                    "productType", input.data().get("productType"),
                    "El tipo de producto no puede proceder de una celda numérica",
                    "UNIT, SERVICE o WEIGHT en una celda de Texto", "Formatea la columna como Texto y vuelve a leer el fichero"));
        }
        for (String field : List.of("offerFrom", "offerUntil")) {
            if (!validatesField(product, options, updateFields, field)) continue;
            var cell = input.mappedCells().get(field);
            if (cell != null && cell.numeric() && cell.rawNumeric() != null) {
                errors.add(error("DATE_INVALID", input.rowNumber(), firstColumn(mapping, field), field,
                        input.data().get(field), "La fecha numérica no es una fecha Excel nativa",
                        "Fecha Excel nativa o fecha de texto DD-MM-AA, DD-MM-AAAA o ISO",
                        "Aplica un formato de fecha a la celda o formatea la columna como Texto"));
            }
        }
        for (String field : List.of("code", "barcode", "barcode2", "supplierReference")) {
            if (!validatesField(product, options, updateFields, field)) continue;
            var cell = input.mappedCells().get(field);
            if (cell == null || !cell.numeric()) continue;
            String raw = cell.rawNumeric();
            String display = cell.value();
            boolean invalid = raw == null || cell.percentage()
                    || !validRawInteger(raw) || rawIntegerDigits(raw) > 15
                    || display == null || !display.matches("[0-9]+");
            if (invalid) {
                errors.add(error("IDENTIFIER_NUMERIC_PRECISION", input.rowNumber(), firstColumn(mapping, field), field,
                        input.data().get(field), "El identificador numérico entero supera la precisión segura de Excel",
                        "Hasta 15 dígitos numéricos o una celda formateada como Texto",
                        "Formatea la columna como Texto y vuelve a leer el fichero"));
            }
        }
    }

    private static boolean textualPercentage(String value) {
        return value != null && value.trim().replace("\u00a0", "").replace(" ", "").endsWith("%");
    }

    private static boolean validRawInteger(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            BigDecimal value = new BigDecimal(raw);
            return value.signum() >= 0 && value.stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static int rawIntegerDigits(String raw) {
        try {
            return new BigDecimal(raw).toBigInteger().abs().toString().length();
        } catch (NumberFormatException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private static List<ImportError> validateMapping(Map<String, String> mapping, int columns) {
        List<ImportError> errors = new ArrayList<>();
        if (mapping == null || mapping.isEmpty()) return List.of(error("MAPPING_REQUIRED", null, null, null, null,
                "El mapeo de columnas es obligatorio", "mapping con letras A..IV", "Configura el mapeo antes de previsualizar"));
        if (mapping.entrySet().stream().noneMatch(entry -> Set.of("code", "barcode", "name").contains(entry.getKey())
                && !blank(entry.getValue()))) {
            errors.add(error("IDENTITY_MAPPING_REQUIRED", null, null, null, null,
                    "Debe mapearse Código, Código de barras o Nombre", "code, barcode o name", "Mapea un atributo de identidad"));
        }
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (!MAPPING_FIELDS.contains(entry.getKey())) {
                errors.add(error("MAPPING_FIELD_UNKNOWN", null, null, truncate(entry.getKey()), truncate(entry.getValue()),
                        "El atributo no pertenece al contrato de importación", MAPPING_FIELDS.toString(), "Elimina o corrige el atributo"));
                continue;
            }
            int index = columnIndex(entry.getValue());
            if (blank(entry.getValue())) continue;
            if (index < 0 || index >= ProductExcelImportReadService.MAX_COLUMNS) errors.add(error("COLUMN_INVALID", null, null,
                    truncate(entry.getKey()), truncate(entry.getValue()), "La columna debe ser una letra Excel entre A e IV", "A..IV", "Corrige la letra"));
            else if (index >= columns) errors.add(error("COLUMN_NOT_FOUND", null, index + 1, truncate(entry.getKey()), truncate(entry.getValue()),
                    "La columna no existe en la cuadrícula leída", "Columna presente en el libro", "Selecciona una columna existente"));
        }
        return errors;
    }

    private static List<ImportError> validateContract(PreviewRequest request) {
        List<ImportError> errors = new ArrayList<>();
        if (request.resolvedProducts().size() > ProductExcelImportReadService.MAX_ROWS
                || request.resolvedProducts().keySet().stream().anyMatch(row -> row < 2)
                || (!request.resolvedProducts().isEmpty() && blank(request.expectedSha256()))) {
            errors.add(error("CONTRACT_LIMIT", null, null, "resolvedProducts", null,
                    "Las asociaciones manuales requieren filas válidas y el hash del fichero", "Hasta 5.000 asociaciones",
                    "Vuelve a leer el fichero y revisar las altas"));
        }
        long contractCharacters = 0L;
        rejectDuplicateDiscountAliases(errors, request.mapping(), "mapping");
        rejectDuplicateDiscountAliases(errors, request.updateFields(), "updateFields");
        if (!blank(request.expectedSha256()) && !request.expectedSha256().matches("[0-9a-fA-F]{64}")) {
            errors.add(error("HASH_REQUIRED", null, null, "expectedSha256", truncate(request.expectedSha256()),
                    "El hash debe ser SHA-256 hexadecimal", "64 caracteres hexadecimales",
                    "Vuelve a leer el fichero y usa su hash SHA-256"));
        }
        if (request.expectedSha256() != null) contractCharacters += request.expectedSha256().length();
        if (request.quantityColumn() != null) {
            String quantityColumn = request.quantityColumn().trim();
            if (quantityColumn.length() > 2 || columnIndex(quantityColumn) < 0) {
                errors.add(error("COLUMN_INVALID", null, null, "quantity", truncate(quantityColumn),
                        "La columna debe ser una letra Excel entre A e IV", "A..IV",
                        "Corrige la columna de cantidad"));
            }
            contractCharacters += quantityColumn.length();
        }
        if (request.mapping() != null && request.mapping().size() > MAPPING_FIELDS.size()) {
            errors.add(error(CONTRACT_LIMIT, null, null, "mapping", String.valueOf(request.mapping().size()),
                    "El mapeo contiene demasiados atributos", "Hasta " + MAPPING_FIELDS.size() + " atributos",
                    "Elimina las claves de mapping que no utilices"));
        } else if (request.mapping() != null) {
            for (Map.Entry<String, String> entry : request.mapping().entrySet()) {
                contractCharacters += validateContractEntry(errors, entry.getKey(), entry.getValue(), "mapping");
            }
        }
        PreviewOptions options = request.options();
        if (options == null || blank(options.context())) {
            errors.add(error("CONTEXT_REQUIRED", null, null, "context", null, "El contexto es obligatorio", "STOCK, WAREHOUSE_INPUT o WAREHOUSE_OUTPUT", "Indica el consumidor de la importación"));
        } else if (!Set.of("STOCK", "WAREHOUSE_INPUT", "WAREHOUSE_OUTPUT").contains(options.context())
                || options.context().length() > 32) {
            errors.add(error("CONTEXT_INVALID", null, null, "context", truncate(options.context()), "El contexto no está soportado", "STOCK, WAREHOUSE_INPUT o WAREHOUSE_OUTPUT", "Selecciona un consumidor válido"));
        }
        if (options != null && options.context() != null) contractCharacters += options.context().length();
        if (options != null && options.documentPriceSource() != null) {
            String source = options.documentPriceSource().trim();
            contractCharacters += source.length();
            if (!DOCUMENT_PRICE_FIELDS.contains(source)) {
                errors.add(error("VALUE_SOURCE_INVALID", null, null, "documentPriceSource", truncate(source),
                        "La tarifa elegida para el documento no está soportada",
                        DOCUMENT_PRICE_FIELDS.toString(), "Selecciona una tarifa válida del documento"));
            } else if (!"WAREHOUSE_INPUT".equals(options.context())) {
                errors.add(error("VALUE_SOURCE_INVALID", null, null, "documentPriceSource", truncate(source),
                        "La tarifa del documento solo se admite en entradas de almacén",
                        "context=WAREHOUSE_INPUT", "Quita la tarifa documental de este contexto"));
            }
        }
        if (options != null && options.storeId() != null && request.storeId() != null && !options.storeId().equals(request.storeId())) {
            errors.add(error("STORE_CONTEXT_MISMATCH", null, null, "storeId", options.storeId().toString(), "Hay dos tiendas distintas en el contrato", "Una única tienda", "Usa el storeId de la petición"));
        }
        if (options != null && options.companyId() != null && request.companyId() != null && !options.companyId().equals(request.companyId())) {
            errors.add(error("COMPANY_CONTEXT_MISMATCH", null, null, "companyId", options.companyId().toString(), "Hay dos empresas distintas en el contrato", "Una única empresa", "Usa el companyId de la petición"));
        }
        if (options != null && options.globalValues() != null) {
            rejectDuplicateDiscountAliases(errors, options.globalValues(), "globalValues");
            if (options.globalValues().size() > GLOBAL_FIELDS.size()) {
                errors.add(error(CONTRACT_LIMIT, null, null, "globalValues", String.valueOf(options.globalValues().size()),
                        "Hay demasiados valores globales", "Hasta " + GLOBAL_FIELDS.size() + " atributos especiales",
                        "Elimina las claves globales que no utilices"));
            } else for (String field : options.globalValues().keySet()) {
                contractCharacters += validateContractEntry(errors, field, options.globalValues().get(field), "globalValues");
                if (!GLOBAL_FIELDS.contains(field)) {
                    errors.add(error("GLOBAL_VALUE_UNKNOWN", null, null, truncate(field), truncate(options.globalValues().get(field)), "El valor global no pertenece a los atributos especiales", GLOBAL_FIELDS.toString(), "Elimina o corrige la clave"));
                }
            }
        }
        if (options != null && options.valueSources() != null) {
            rejectDuplicateDiscountAliases(errors, options.valueSources(), "valueSources");
            if (options.valueSources().size() > GLOBAL_FIELDS.size()) {
                errors.add(error(CONTRACT_LIMIT, null, null, "valueSources", String.valueOf(options.valueSources().size()),
                        "Hay demasiados orígenes de valores", "Hasta " + GLOBAL_FIELDS.size() + " atributos especiales",
                        "Elimina los orígenes que no utilices"));
            } else for (Map.Entry<String, ValueSource> entry : options.valueSources().entrySet()) {
                String source = entry.getValue() == null ? null : entry.getValue().source();
                String sourceValue = entry.getValue() == null ? null : entry.getValue().value();
                contractCharacters += validateContractEntry(errors, entry.getKey(), sourceValue, "valueSources");
                contractCharacters += source == null ? 0 : source.length();
                if (source != null && source.length() > MAX_CONTRACT_KEY_CHARACTERS) {
                    errors.add(error(CONTRACT_LIMIT, null, null, "valueSources", truncate(source),
                            "El origen de configuración es demasiado largo", "Hasta 128 caracteres", "Reduce el origen"));
                }
                if (!GLOBAL_FIELDS.contains(entry.getKey())) {
                    errors.add(error("VALUE_SOURCE_UNKNOWN", null, null, truncate(entry.getKey()), entry.getValue() == null ? null : truncate(entry.getValue().source()), "El origen no está permitido para ese atributo", "offerActive, productType, priceUseMode, discountType, taxId, taxesIncluded", "Corrige el atributo"));
                } else if (entry.getValue() == null || (!"excel".equalsIgnoreCase(entry.getValue().source()) && !"global".equalsIgnoreCase(entry.getValue().source()))) {
                    errors.add(error("VALUE_SOURCE_INVALID", null, null, truncate(entry.getKey()), entry.getValue() == null ? null : truncate(entry.getValue().source()), "El origen debe ser Excel o global", "excel o global", "Selecciona un origen válido"));
                }
            }
        }
        if (request.updateFields() != null) {
            if (request.updateFields().size() > UPDATABLE_FIELDS.size()) {
                errors.add(error(CONTRACT_LIMIT, null, null, "updateFields", String.valueOf(request.updateFields().size()),
                        "Hay demasiados campos de actualización", "Hasta " + UPDATABLE_FIELDS.size() + " campos actualizables",
                        "Elimina las claves de updateFields que no utilices"));
            } else for (String field : request.updateFields().keySet()) {
                contractCharacters += validateContractEntry(errors, field, String.valueOf(request.updateFields().get(field)), "updateFields");
                if (!UPDATABLE_FIELDS.contains(field)) {
                    errors.add(error("UPDATE_FIELD_UNKNOWN", null, null, truncate(field), truncate(String.valueOf(request.updateFields().get(field))), "El atributo no es actualizable como maestro", "Campos de producto actualizables", "Elimina ese campo de updateFields"));
                }
            }
        }
        if (contractCharacters > MAX_CONTRACT_CHARACTERS) {
            errors.add(error(CONTRACT_LIMIT, null, null, "contract", String.valueOf(contractCharacters),
                    "La configuración supera el presupuesto de texto", "Hasta 100.000 caracteres de configuración",
                    "Reduce claves y valores antes de previsualizar"));
        }
        return errors;
    }

    private static void rejectDuplicateDiscountAliases(List<ImportError> errors, Map<String, ?> values,
            String attribute) {
        if (values != null && values.containsKey("discountType") && values.containsKey("prohibitedDiscount")) {
            errors.add(error("VALUE_SOURCE_INVALID", null, null, attribute, "discountType + prohibitedDiscount",
                    "La configuración contiene dos alias para Prohibido descuento",
                    "Usa solo discountType o prohibitedDiscount",
                    "Elimina el alias duplicado y vuelve a previsualizar"));
        }
    }

    private static long validateContractEntry(List<ImportError> errors, String key, String value, String attribute) {
        long characters = (key == null ? 0 : key.length()) + (value == null ? 0 : value.length());
        if (key != null && key.length() > MAX_CONTRACT_KEY_CHARACTERS) {
            errors.add(error(CONTRACT_LIMIT, null, null, attribute, truncate(key),
                    "La clave de configuración es demasiado larga", "Hasta 128 caracteres", "Reduce la clave"));
        }
        if (value != null && value.length() > MAX_EDIT_VALUE_CHARACTERS) {
            errors.add(error("EDIT_VALUE_LIMIT", null, null, attribute, truncate(value),
                    "El valor de configuración supera 32.767 caracteres", "Hasta 32.767 caracteres", "Acorta el valor"));
        }
        return characters;
    }

    public static String canonicalContext(String value) {
        if (blank(value)) return null;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "STOCK" -> "STOCK";
            case "WAREHOUSE_INPUT" -> "WAREHOUSE_INPUT";
            case "WAREHOUSE_OUTPUT" -> "WAREHOUSE_OUTPUT";
            default -> value.trim().toUpperCase(Locale.ROOT);
        };
    }

    private static List<ImportError> validateEdits(List<CellEdit> edits,
            List<List<ProductExcelImportReadService.CellView>> sourceRows,
            List<ProductExcelImportReadService.FormulaView> formulas) {
        if (edits == null) return List.of();
        List<ImportError> errors = new ArrayList<>();
        if (edits.size() > MAX_EDITS) {
            return List.of(error("EDIT_LIMIT", null, null, "edits", String.valueOf(edits.size()),
                    "El contrato supera el número máximo de ediciones", "Hasta 250.000 ediciones",
                    "Reduce las ediciones o divide el fichero en lotes"));
        }
        int detailedErrors = 0;
        Map<String, CellEdit> lastEdits = new LinkedHashMap<>();
        for (CellEdit edit : edits) {
            String rawValue = edit == null ? null : edit.value();
            boolean valueTooLong = rawValue != null && rawValue.length() > MAX_EDIT_VALUE_CHARACTERS;
            int editColumn = edit == null ? -1 : columnIndex(edit.column());
            boolean invalidCoordinate = edit == null || edit.row() == null || edit.row() < 1
                    || edit.row() > sourceRows.size() || editColumn < 0
                    || editColumn >= ProductExcelImportReadService.MAX_COLUMNS;
            if (invalidCoordinate) {
                if (detailedErrors++ < 100) {
                    errors.add(error("CELL_EDIT_INVALID", edit == null ? null : edit.row(),
                            edit == null || edit.column() == null ? null : editColumn + 1,
                            "cell", truncate(rawValue),
                            "La edición no apunta a una celda válida", "Fila existente y columna A..IV", "Corrige la edición"));
                }
                if (valueTooLong && detailedErrors++ < 100) {
                    errors.add(error("EDIT_VALUE_LIMIT", edit == null ? null : edit.row(),
                            edit == null || edit.column() == null ? null : editColumn + 1,
                            "cell", truncate(rawValue), "El valor de la edición supera 32.767 caracteres",
                            "Hasta 32.767 caracteres", "Acorta el valor de la celda"));
                }
                continue;
            }
            String value = rawValue == null ? "" : rawValue;
            if (valueTooLong) {
                if (detailedErrors++ < 100) {
                    errors.add(error("EDIT_VALUE_LIMIT", edit.row(), editColumn + 1,
                            "cell", truncate(value), "El valor de la edición supera 32.767 caracteres",
                            "Hasta 32.767 caracteres", "Acorta el valor de la celda"));
                }
                continue;
            }
            // Last-write semantics match editableRows; duplicate coordinates
            // must not inflate the resulting workbook budget.
            lastEdits.put(edit.row() + ":" + editColumn, edit);
        }
        if (detailedErrors > 100) {
            errors.add(error("EDIT_LIMIT", null, null, "edits", String.valueOf(detailedErrors),
                    "Hay demasiadas ediciones inválidas para devolver detalle individual", "Hasta 100 errores detallados",
                    "Corrige las ediciones y vuelve a intentarlo"));
        }
        long projectedCells = sourceRows.stream().mapToLong(List::size).sum();
        long projectedNonEmpty = sourceRows.stream().flatMap(List::stream)
                .filter(cell -> cell != null && cell.value() != null && !cell.value().isBlank()).count();
        long projectedText = sourceRows.stream().flatMap(List::stream)
                .mapToLong(ProductExcelImportPreviewService::cellTextLength).sum();
        for (CellEdit edit : lastEdits.values()) {
            int rowIndex = edit.row() - 1;
            int column = columnIndex(edit.column());
            List<ProductExcelImportReadService.CellView> row = sourceRows.get(rowIndex);
            ProductExcelImportReadService.CellView previousCell = column < row.size() ? row.get(column) : null;
            String next = edit.value() == null ? "" : edit.value();
            projectedText -= cellTextLength(previousCell);
            projectedText += next.length();
            projectedNonEmpty += (next.isBlank() ? 0 : 1)
                    - (previousCell == null || previousCell.value() == null || previousCell.value().isBlank() ? 0 : 1);
        }
        Map<Integer, Integer> rowWidths = new HashMap<>();
        for (CellEdit edit : lastEdits.values()) {
            int column = columnIndex(edit.column());
            rowWidths.merge(edit.row() - 1, column + 1, Math::max);
        }
        for (Map.Entry<Integer, Integer> entry : rowWidths.entrySet()) {
            projectedCells += Math.max(0, entry.getValue() - sourceRows.get(entry.getKey()).size());
        }
        if (projectedCells > ProductExcelImportReadService.MAX_MATERIALIZED_CELLS) {
            errors.add(error("EDIT_LIMIT", null, null, "edits", String.valueOf(projectedCells),
                    "Las ediciones expandirían la cuadrícula por encima del límite materializable",
                    "Hasta " + ProductExcelImportReadService.MAX_MATERIALIZED_CELLS + " celdas",
                    "Reduce el alcance de filas o columnas editadas"));
        }
        if (projectedNonEmpty > ProductExcelImportReadService.MAX_NON_EMPTY_CELLS
                || projectedText > ProductExcelImportReadService.MAX_TEXT_CHARACTERS) {
            errors.add(error("EDIT_LIMIT", null, null, "edits", String.valueOf(Math.max(projectedNonEmpty, projectedText)),
                    "Las ediciones superarían el límite de texto o celdas no vacías resultante",
                    "Hasta 250.000 celdas no vacías y 5.000.000 caracteres",
                    "Reduce las ediciones o divide el fichero en lotes"));
        }
        return errors;
    }

    private static long cellTextLength(ProductExcelImportReadService.CellView cell) {
        if (cell == null) return 0L;
        return length(cell.value()) + length(cell.formula()) + length(cell.rawNumeric());
    }

    private static long length(String value) {
        return value == null ? 0L : value.length();
    }

    private static String truncate(String value) {
        return value == null ? null : value.substring(0, Math.min(256, value.length()));
    }

    private static List<ImportError> responseErrors(ErrorBudget budget) {
        if (budget.omitted() == 0) return List.of();
        return List.of(error("ERROR_LIMIT", null, null, "errors", String.valueOf(budget.omitted()),
                "Se han omitido detalles de error por encima del límite de respuesta",
                "Hasta 5.000 detalles de error de fila", "Corrige las filas indicadas y vuelve a previsualizar"));
    }

    /** Shared per-response budget. Omitted errors are counted without retaining
     * their coordinates or values, keeping the response and heap bounded. */
    private static final class ErrorBudget {
        private int accepted;
        private int omitted;

        private boolean reserve() {
            if (accepted < MAX_ERROR_DETAILS) {
                accepted++;
                return true;
            }
            omitted++;
            return false;
        }

        private int accepted() { return accepted; }
        private int omitted() { return omitted; }
    }

    /** A row error list which keeps classification independent of the detail cap. */
    private static final class BoundedRowErrors extends ArrayList<ImportError> {
        private final ErrorBudget budget;
        private boolean hadError;

        private BoundedRowErrors(ErrorBudget budget) { this.budget = budget; }

        @Override
        public boolean add(ImportError error) {
            if (error == null) return super.add(null);
            hadError = true;
            return budget.reserve() && super.add(error);
        }

        @Override
        public boolean addAll(Collection<? extends ImportError> errors) {
            boolean changed = false;
            for (ImportError error : errors) changed |= add(error);
            return changed;
        }

        private boolean hadError() { return hadError; }
    }

    private static Integer firstColumn(Map<String, String> mapping, String field) {
        if (mapping == null || !mapping.containsKey(field)) return null;
        int index = columnIndex(mapping.get(field)); return index < 0 ? null : index + 1;
    }
    private static int columnIndex(String value) {
        if (value == null || value.trim().length() > 2 || !value.trim().matches("[A-Za-z]+")) return -1;
        int result = 0;
        for (char c : value.trim().toUpperCase(Locale.ROOT).toCharArray()) result = result * 26 + c - 'A' + 1;
        int index = result - 1;
        return index < 0 || index >= ProductExcelImportReadService.MAX_COLUMNS ? -1 : index;
    }
    private static boolean usesExcelCell(PreviewOptions options, String field) {
        if (options == null || !GLOBAL_FIELDS.contains(field)) return true;
        List<String> keys = Set.of("discountType", "prohibitedDiscount").contains(field)
                ? List.of("prohibitedDiscount", "discountType") : List.of(field);
        if (options.valueSources() != null) for (String key : keys) {
            ValueSource source = options.valueSources().get(key);
            if (source != null && "global".equalsIgnoreCase(source.source())) return false;
            if (source != null && "excel".equalsIgnoreCase(source.source())) return true;
        }
        return options.globalValues() == null || keys.stream().noneMatch(options.globalValues()::containsKey);
    }

    private static String effective(Map<String, String> data, PreviewOptions options, String... keys) {
        if (options != null && options.valueSources() != null) {
            for (String key : keys) {
                ValueSource source = options.valueSources().get(key);
                if (source != null) {
                    if ("global".equalsIgnoreCase(source.source())) return source.value() == null ? "" : source.value();
                    if ("excel".equalsIgnoreCase(source.source())) return data.getOrDefault(key, "");
                }
            }
        }
        if (options != null && options.globalValues() != null) {
            for (String key : keys) if (options.globalValues().containsKey(key)) return options.globalValues().get(key);
        }
        for (String key : keys) if (!blank(data.get(key))) return data.get(key);
        return "";
    }
    static String normalizeProductType(String value) {
        if (value == null) return null;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "1" -> "UNIT";
            case "2" -> "WEIGHT";
            case "3" -> "SERVICE";
            default -> value.trim().toUpperCase(Locale.ROOT);
        };
    }
    private static String normalizePriceUse(String value) { if (value == null) return null; return switch (value.trim().toUpperCase(Locale.ROOT)) { case "1", "NORMAL" -> "NORMAL"; case "2", "MEMBER_PRICE", "MEMBER" -> "MEMBER_PRICE"; case "3", "OFFER_PRICE", "OFFER" -> "OFFER_PRICE"; case "4", "OFFER_DISCOUNT" -> "OFFER_DISCOUNT"; default -> null; }; }
    private static UUID parseUuid(String value) { try { return blank(value) ? null : UUID.fromString(value.trim()); } catch (IllegalArgumentException exception) { return null; } }
    private static UUID resolveReference(String value, Map<String, UUID> references) {
        // UUIDs and operational codes are both accepted only when present in the
        // already tenant/store-scoped reference map.
        return references.get(normalizeReference(value));
    }
    private static String normalizeReference(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static boolean applies(Map<String, Boolean> updateFields, String field) {
        return updateFields == null || Boolean.TRUE.equals(updateFields.get(field));
    }
    private static boolean requiresWarehouseLineValidation(PreviewOptions options, String field) {
        if (options == null || !"WAREHOUSE_INPUT".equals(options.context())) return false;
        String documentPriceSource = blank(options.documentPriceSource())
                ? "purchasePrice" : options.documentPriceSource().trim();
        return "purchasePrice".equals(field) || "purchaseDiscountPercent".equals(field) || "supplierReference".equals(field)
                || documentPriceSource.equals(field);
    }
    private static boolean validatesField(Product product, PreviewOptions options, Map<String, Boolean> updateFields, String field) {
        return product == null || Set.of("code", "barcode", "quantity").contains(field)
                || applies(updateFields, field) || requiresWarehouseLineValidation(options, field);
    }
    private static BigDecimal resultingOptionalPrice(Product product, Map<String, String> data,
            Map<String, Boolean> updateFields, PreviewOptions options, String field, BigDecimal persisted) {
        if (product != null && (!applies(updateFields, field) || blank(data.get(field)))) return persisted;
        BigDecimal imported = decimal(data.get(field));
        boolean ignoredZero = options != null && Boolean.TRUE.equals(options.skipZeroPriceUpdate())
                && Set.of("memberPrice", "wholesalePrice", "offerPrice").contains(field)
                && imported != null && imported.signum() == 0;
        return ignoredZero ? persisted : imported;
    }
    private static BigDecimal parseDecimal(String value, String field, int row, Integer column,
            List<ImportError> errors) {
        return parseDecimal(value, field, row, column, errors, false);
    }

    private static BigDecimal parseDecimal(String value, String field, int row, Integer column,
            List<ImportError> errors, boolean numericSource) {
        try {
            boolean price = DOCUMENT_PRICE_FIELDS.contains(field);
            boolean scale3Field = price || Set.of("quantity", "packageQuantity", "stockMin", "stockMax").contains(field);
            if (!numericSource && !price && hasAmbiguousThousands(value)) {
                errors.add(error("NUMBER_FORMAT_AMBIGUOUS", row, column, field, value,
                        "Un separador y tres dígitos finales puede ser decimal o millar",
                        "1234 o 1.234,00 / 1,234.00", "Indica los decimales con dos dígitos o usa ambos separadores"));
                return null;
            }
            BigDecimal decimal = parseFormattedNumber(value, numericSource || scale3Field).stripTrailingZeros();
            if (decimal.signum() < 0) throw new NumberFormatException();
            if (field.toLowerCase(Locale.ROOT).contains("percent")
                    && decimal.compareTo(BigDecimal.valueOf(100)) > 0) throw new NumberFormatException();
            if ((field.equals("quantity") || field.equals("packageQuantity")) && decimal.signum() <= 0) {
                throw new NumberFormatException();
            }
            boolean percent = field.toLowerCase(Locale.ROOT).contains("percent");
            int scale = percent ? 2 : 3;
            if (decimal.scale() > scale) {
                errors.add(error("NUMBER_SCALE_INVALID", row, column, field, value,
                        "El número tiene más decimales de los permitidos", "Precios/cantidades/stock: 3; porcentajes: 2",
                        "Redondea el valor al número de decimales permitido"));
                return null;
            }
            int maxPrecision = percent ? 5 : price ? 20 : 19;
            int integerDigits = Math.max(1, decimal.precision() - decimal.scale());
            if (integerDigits > maxPrecision - scale) {
                errors.add(error("NUMBER_PRECISION_INVALID", row, column, field, value,
                        "El número supera la precisión máxima del campo", "Hasta " + (maxPrecision - scale) + " dígitos enteros y " + scale + " decimales",
                        "Acorta el valor numérico"));
                return null;
            }
            BigDecimal scaledInteger = decimal.movePointRight(scale);
            boolean exactJavascriptRoundTrip = Double.isFinite(decimal.doubleValue())
                    && BigDecimal.valueOf(decimal.doubleValue()).compareTo(decimal) == 0;
            if (scaledInteger.stripTrailingZeros().scale() > 0
                    || scaledInteger.abs().compareTo(MAX_JAVASCRIPT_SAFE_INTEGER) > 0
                    || !exactJavascriptRoundTrip) {
                errors.add(error("NUMBER_PRECISION_INVALID", row, column, field, value,
                        "El número perdería precisión al pasar por la interfaz",
                        "Decimal representable exactamente y entero escalado hasta 9.007.199.254.740.991",
                        "Reduce el importe o la cantidad, o divídelo entre varias líneas"));
                return null;
            }
            return decimal;
        } catch (NumberFormatException exception) {
            errors.add(error("NUMBER_INVALID", row, column, field, value,
                    "El número no es válido, negativo o está fuera de rango",
                    "Decimal no negativo; porcentaje 0..100; cantidad mayor que 0", "Corrige el valor numérico"));
            return null;
        }
    }

    /** Parses the numeric representations emitted by ES/EN Excel formatters. */
    private static BigDecimal parseFormattedNumber(String raw) {
        return parseFormattedNumber(raw, true);
    }

    private static BigDecimal parseFormattedNumber(String raw, boolean singleSeparatorIsDecimal) {
        if (raw == null || raw.isBlank()) throw new NumberFormatException();
        String text = raw.trim().replace("\u00a0", "").replace(" ", "")
                .replace("€", "").replace("$", "").replace("£", "").replace("¥", "");
        if (text.endsWith("%")) text = text.substring(0, text.length() - 1);
        if (!text.matches("[+-]?[0-9.,]+")) throw new NumberFormatException();
        int comma = text.lastIndexOf(',');
        int dot = text.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            char decimalSeparator = comma > dot ? ',' : '.';
            char groupingSeparator = decimalSeparator == ',' ? '.' : ',';
            validateGrouping(text.substring(0, Math.max(comma, dot)), groupingSeparator);
            text = text.replace(String.valueOf(groupingSeparator), "");
            text = text.replace(decimalSeparator, '.');
        } else if (comma >= 0) {
            text = normalizeSingleSeparator(text, ',', singleSeparatorIsDecimal);
        } else if (dot >= 0) {
            text = normalizeSingleSeparator(text, '.', singleSeparatorIsDecimal);
        }
        return new BigDecimal(text);
    }

    private static String normalizeSingleSeparator(String text, char separator, boolean singleSeparatorIsDecimal) {
        int last = text.lastIndexOf(separator);
        int decimals = text.length() - last - 1;
        String digitsBefore = text.substring(0, last).replace(String.valueOf(separator), "");
        if (last != text.indexOf(separator)) {
            validateGrouping(text, separator);
            return text.replace(String.valueOf(separator), "");
        }
        // A three-digit final group is the conventional thousands form when
        // no other separator identifies the decimal part.
        if (!singleSeparatorIsDecimal && decimals == 3 && digitsBefore.length() > 0) {
            validateGrouping(text.substring(0, last), separator);
            return text.replace(String.valueOf(separator), "");
        }
        return text.replace(separator, '.');
    }

    private static void validateGrouping(String integerPart, char separator) {
        String[] groups = integerPart.split(java.util.regex.Pattern.quote(String.valueOf(separator)), -1);
        if (groups.length <= 1) return;
        if (groups[0].length() < 1 || groups[0].length() > 3) throw new NumberFormatException();
        for (int index = 1; index < groups.length; index++) {
            if (groups[index].length() != 3) throw new NumberFormatException();
        }
    }

    private static boolean hasAmbiguousThousands(String raw) {
        if (raw == null) return false;
        String text = raw.trim().replace("\u00a0", "").replace(" ", "")
                .replace("€", "").replace("$", "").replace("£", "").replace("¥", "");
        if (text.endsWith("%")) text = text.substring(0, text.length() - 1);
        int comma = text.lastIndexOf(',');
        int dot = text.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) return false;
        int separator = Math.max(comma, dot);
        return separator > 0 && comma != dot && text.indexOf(comma >= 0 ? ',' : '.') == separator
                && text.length() - separator - 1 == 3;
    }
    private static BigDecimal decimal(Object value) { try { return value == null || blank(value.toString()) ? null : new BigDecimal(value.toString().replace(',', '.')); } catch (NumberFormatException exception) { return null; } }
    private static LocalDate parseDate(String value) { if (blank(value)) return null; String text = value.trim(); try { if (text.matches("\\d{2}-\\d{2}-\\d{2}")) { String[] p = text.split("-"); return LocalDate.of(2000 + Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0])); } if (text.matches("\\d{2}-\\d{2}-\\d{4}")) { String[] p = text.split("-"); return LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0])); } return LocalDate.parse(text, ISO_DATE); } catch (DateTimeException | NumberFormatException exception) { return null; } }
    private static void dateError(Map<String, String> data, Map<String, String> mapping, int row, String field, List<ImportError> errors) { errors.add(error("DATE_INVALID", row, firstColumn(mapping, field), field, data.get(field), "La fecha no existe en el calendario", "DD-MM-AA, DD-MM-AAAA, ISO o fecha Excel", "Corrige la fecha")); }
    private static Map<String, String> canonicalizeIdentityFields(Map<String, String> input) {
        Map<String, String> data = new LinkedHashMap<>(input);
        for (String field : List.of("code", "barcode", "barcode2", "supplierReference")) {
            if (data.containsKey(field)) data.put(field, CatalogText.canonicalIdentity(data.get(field)));
        }
        return data;
    }
    private static boolean isIdentityField(String field) {
        return Set.of("name", "code", "barcode", "barcode2", "supplierReference").contains(field);
    }
    private static String normalized(String value) {
        String canonical = CatalogText.canonicalIdentity(value);
        return canonical == null ? "" : canonical.toLowerCase(Locale.ROOT);
    }
    private static boolean blank(String value) { return CatalogText.isBlank(value); }
    private PreviewResult blocked(ProductExcelImportReadService.ReadResult read, List<ImportError> errors) {
        recordAudit(AuditResult.FALLO, Map.of("fileName", read.fileName(), "sha256", read.sha256(), "errors", errors.size()));
        String fingerprint = previewFingerprint(read.fileName(), read.sha256(), read.sheetName(),
                List.of(), 0, 0, 0, errors, List.of());
        return new PreviewResult(read.fileName(), read.sha256(), read.sheetName(), List.of(), 0, 0, 0, errors, List.of(), fingerprint);
    }
    private void recordAudit(AuditResult result, Map<String, Object> details) {
        if (audit == null) return;
        Map<String, Object> safeDetails = new LinkedHashMap<>(details);
        try {
            safeDetails.put("storeId", organization.currentStore().getId().toString());
            safeDetails.put("companyId", organization.currentCompany().getId().toString());
        } catch (RuntimeException ignored) {
            // Direct service tests and rejected requests may lack an organization session.
        }
        try {
            audit.record("PRODUCT_EXCEL_IMPORT_PREVIEW", result, safeDetails);
        } catch (RuntimeException ignored) {
            // Audit must not alter the authoritative preview result.
        }
    }
    private static ImportError error(String code, Integer row, Integer column, String attribute, String receivedValue, String reason, String accepted, String fix) {
        return new ImportError(code, row, column, truncate(attribute), truncate(receivedValue), reason, accepted, fix);
    }
    private record RowInput(int rowNumber, Map<String, String> data, Product product,
            Map<String, ProductExcelImportReadService.CellView> mappedCells) { }
    private record SubfamilyRef(String reference, Subfamily subfamily) { }
    private record LogicalIdentityOwners(Map<Integer, String> rowOwners,
            Map<String, Set<String>> primaryOwners, Map<String, Set<String>> barcode2Owners) { }

    public record CellEdit(Integer row, String column, String value) { }
    public record ValueSource(String source, String value) { }
    public record PreviewOptions(Map<String, String> globalValues, Map<String, ValueSource> valueSources,
            Boolean showOnlyImported, String context, UUID storeId, UUID companyId,
            Boolean skipZeroPriceUpdate, Boolean requireQuantity, String documentPriceSource) {
        public PreviewOptions(Map<String, String> globalValues, Map<String, ValueSource> valueSources,
                Boolean showOnlyImported, String context, UUID storeId, UUID companyId,
                Boolean skipZeroPriceUpdate, Boolean requireQuantity) {
            this(globalValues, valueSources, showOnlyImported, context, storeId, companyId,
                    skipZeroPriceUpdate, requireQuantity, null);
        }
        public PreviewOptions() { this(Map.of(), Map.of(), false, null, null, null, false, false, null); }
    }
    public record PreviewRequest(Map<String, String> mapping, List<CellEdit> edits, PreviewOptions options, UUID storeId, UUID companyId, String expectedSha256, Integer startRow, String quantityColumn, Map<String, Boolean> updateFields, Map<Integer, UUID> resolvedProducts) {
        public PreviewRequest(Map<String, String> mapping, List<CellEdit> edits, PreviewOptions options, UUID storeId,
                UUID companyId, String expectedSha256, Integer startRow, String quantityColumn, Map<String, Boolean> updateFields) {
            this(mapping, edits, options, storeId, companyId, expectedSha256, startRow, quantityColumn, updateFields, Map.of());
        }
        public PreviewRequest { resolvedProducts = resolvedProducts == null ? Map.of() : Map.copyOf(resolvedProducts); }
    }
    public record ImportError(String code, Integer row, Integer column, String attribute, String receivedValue, String reason, String acceptedValues, String recommendedFix) { }
    public record PreviewRow(int rowNumber, List<Integer> rowNumbers, String classification, Map<String, Object> excelData, Map<String, Object> databaseData, Long version, Map<String, Object> changes, List<ImportError> errors, boolean purchasePriceChanged, boolean masterDataChanged, String concurrencyToken) {
        @com.fasterxml.jackson.annotation.JsonProperty("errorTexts")
        public Map<String, String> errorTexts() {
            return ProductExcelImportPresentation.errorTexts(errors);
        }
        public PreviewRow(int rowNumber, List<Integer> rowNumbers, String classification, Map<String, Object> excelData, Map<String, Object> databaseData, Long version, Map<String, Object> changes, List<ImportError> errors) {
            this(rowNumber, rowNumbers, classification, excelData, databaseData, version, changes, errors, changes != null && changes.containsKey("purchasePrice"),
                    "EXISTING".equals(classification) && changes != null && !changes.isEmpty(), null);
        }
        public PreviewRow(int rowNumber, List<Integer> rowNumbers, String classification, Map<String, Object> excelData, Map<String, Object> databaseData, Long version, Map<String, Object> changes, List<ImportError> errors, boolean purchasePriceChanged) {
            this(rowNumber, rowNumbers, classification, excelData, databaseData, version, changes, errors, purchasePriceChanged,
                    "EXISTING".equals(classification) && changes != null && !changes.isEmpty(), null);
        }
        public PreviewRow(int rowNumber, List<Integer> rowNumbers, String classification, Map<String, Object> excelData, Map<String, Object> databaseData, Long version, Map<String, Object> changes, List<ImportError> errors, boolean purchasePriceChanged, String concurrencyToken) {
            this(rowNumber, rowNumbers, classification, excelData, databaseData, version, changes, errors, purchasePriceChanged,
                    "EXISTING".equals(classification) && changes != null && !changes.isEmpty(), concurrencyToken);
        }
        PreviewRow withoutDatabase() { return new PreviewRow(rowNumber, rowNumbers, classification, excelData, null, null, Map.of(), errors, purchasePriceChanged, masterDataChanged, concurrencyToken); }
        @com.fasterxml.jackson.annotation.JsonProperty("existence")
        public String existence() {
            if ("EXISTING".equals(classification) || databaseData != null && databaseData.get("id") != null) return "EXISTING";
            if (errors.stream().anyMatch(error -> "PRODUCT_AMBIGUOUS".equals(error.code()))) return "AMBIGUOUS";
            if (blank(String.valueOf(excelData.getOrDefault("code", ""))) && blank(String.valueOf(excelData.getOrDefault("barcode", "")))) return "UNRESOLVED";
            return "MISSING";
        }
    }
    public record FormulaView(String cell, String formula, String calculatedValue) { }

    public record PreviewResult(String fileName, String sha256, String sheetName, List<PreviewRow> rows,
            long detectedRows, long existingRows, long missingRows, List<ImportError> errors,
            List<FormulaView> formulas, String previewFingerprint, List<PreviewRow> sourceRows) {
        @com.fasterxml.jackson.annotation.JsonProperty("errorTexts")
        public Map<String, String> errorTexts() {
            return ProductExcelImportPresentation.errorTexts(errors);
        }
        public PreviewResult(String fileName, String sha256, String sheetName, List<PreviewRow> rows,
                long detectedRows, long existingRows, long missingRows, List<ImportError> errors,
                List<FormulaView> formulas, String previewFingerprint) {
            this(fileName, sha256, sheetName, rows, detectedRows, existingRows, missingRows, errors, formulas, previewFingerprint, rows);
        }
        public PreviewResult(String fileName, String sha256, String sheetName, List<PreviewRow> rows,
                long detectedRows, long existingRows, long missingRows, List<ImportError> errors) {
            this(fileName, sha256, sheetName, rows, detectedRows, existingRows, missingRows, errors, List.of(), null);
        }
        public PreviewResult(String fileName, String sha256, String sheetName, List<PreviewRow> rows,
                long detectedRows, long existingRows, long missingRows, List<ImportError> errors,
                List<FormulaView> formulas) {
            this(fileName, sha256, sheetName, rows, detectedRows, existingRows, missingRows, errors, formulas, null);
        }
        public PreviewResult {
            formulas = formulas == null ? List.of() : List.copyOf(formulas);
        }
    }
}
