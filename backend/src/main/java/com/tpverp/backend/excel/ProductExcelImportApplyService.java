package com.tpverp.backend.excel;

import com.tpverp.backend.catalog.CatalogService;
import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.PriceUseMode;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.ProductType;
import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.inventory.WarehouseExcelImportMetadata;
import com.tpverp.backend.inventory.WarehouseExcelImportProvenanceService;
import com.tpverp.backend.security.application.PermissionChecks;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Authoritative, all-or-nothing application of an already previewed import. */
@Service
public class ProductExcelImportApplyService {

    private static final Set<String> UPDATABLE_FIELDS = Set.of(
            "barcode2", "name", "description", "comments", "familyId", "subfamilyId", "taxId", "productType",
            "priceUseMode", "discountType", "purchasePrice", "purchaseDiscountPercent", "taxesIncluded", "salePrice",
            "memberPrice", "wholesalePrice", "offerPrice", "offerDiscountPercent", "offerActive", "offerFrom", "offerUntil",
            "packageQuantity", "stockMin", "stockMax");
    private static final int MAX_CONCURRENCY_TOKENS = 5_000;
    private static final java.util.regex.Pattern SHA256 = java.util.regex.Pattern.compile("[0-9a-fA-F]{64}");

    private final ProductExcelImportPreviewService previewService;
    private final ProductExcelImportApplyWriter writer;
    private final AuditService audit;
    private final CurrentOrganization organization;
    private final WarehouseExcelImportProvenanceService provenance;
    private final ProductExcelImportApplyCommitter committer;

    public ProductExcelImportApplyService(ProductExcelImportPreviewService previewService,
            ProductExcelImportApplyWriter writer, AuditService audit) {
        this(previewService, writer, audit, null, null, new ProductExcelImportApplyCommitter(writer));
    }

    public ProductExcelImportApplyService(ProductExcelImportPreviewService previewService,
            ProductExcelImportApplyWriter writer, AuditService audit, CurrentOrganization organization) {
        this(previewService, writer, audit, organization, null, new ProductExcelImportApplyCommitter(writer));
    }

    public ProductExcelImportApplyService(ProductExcelImportPreviewService previewService,
            ProductExcelImportApplyWriter writer, AuditService audit, CurrentOrganization organization,
            WarehouseExcelImportProvenanceService provenance) {
        this(previewService, writer, audit, organization, provenance, new ProductExcelImportApplyCommitter(writer));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductExcelImportApplyService(ProductExcelImportPreviewService previewService,
            ProductExcelImportApplyWriter writer, AuditService audit, CurrentOrganization organization,
            WarehouseExcelImportProvenanceService provenance, ProductExcelImportApplyCommitter committer) {
        this.previewService = previewService;
        this.writer = writer;
        this.audit = audit;
        this.organization = organization;
        this.provenance = provenance;
        this.committer = committer;
    }

    public ApplyResult apply(MultipartFile file, ApplyRequest request) {
        if (request == null || request.preview() == null
                || request.preview().expectedSha256() == null
                || request.preview().expectedSha256().isBlank()) {
            return fail(request, file, List.of(error("HASH_REQUIRED", null,
                    "El hash esperado es obligatorio para aplicar", "SHA-256", "Genera una vista previa nueva")));
        }
        if (file == null || file.isEmpty()) {
            return fail(request, file, List.of(error("FILE_EMPTY", null, "El fichero esta vacio", "XLS o XLSX", "Selecciona un fichero valido")));
        }
        if (request.operation() != null) return applyOperation(file, request);
        List<ApplyError> tokenPayloadErrors = validateTokenPayload(request.expectedConcurrencyTokens());
        if (!tokenPayloadErrors.isEmpty()) {
            return fail(request, file, tokenPayloadErrors);
        }
        ProductExcelImportPreviewService.PreviewRequest canonicalPreview = canonicalizeContext(request.preview());
        String applyContext = canonicalPreview.options() == null ? null : canonicalPreview.options().context();
        if ("STOCK".equals(applyContext) || "WAREHOUSE_OUTPUT".equals(applyContext)) {
            return fail(request, file, List.of(error("APPLY_CONTEXT_UNSUPPORTED", null,
                    "Este contexto debe continuar por su flujo atomico especifico y no puede escribir el maestro aqui",
                    "WAREHOUSE_INPUT", "Usa el flujo de edicion masiva de Stock o el documento de salida")));
        }
        ProductExcelImportPreviewService.PreviewRequest authoritative = forceDatabaseSnapshot(canonicalPreview);
        ProductExcelImportPreviewService.PreviewResult preview = previewService.preview(file, authoritative);
        if (!preview.errors().isEmpty() || preview.rows().stream().anyMatch(row -> "ERROR".equals(row.classification()))) {
            List<ApplyError> previewErrors = new ArrayList<>(applyErrors(preview.errors()));
            for (ProductExcelImportPreviewService.PreviewRow row : preview.rows()) previewErrors.addAll(applyErrors(row.errors()));
            previewErrors = new ArrayList<>(new LinkedHashSet<>(previewErrors));
            auditBestEffort(preview, "FALLO", previewErrors.size(), authoritative);
            return new ApplyResult(preview.fileName(), preview.sha256(), List.of(), previewErrors, 0);
        }
        requireContextPermission(authoritative);
        List<ApplyError> tokenContractErrors = validateConcurrencyTokens(request.expectedConcurrencyTokens(), preview);
        if (!tokenContractErrors.isEmpty()) {
            auditBestEffort(preview, "FALLO", tokenContractErrors.size(), authoritative);
            return new ApplyResult(preview.fileName(), preview.sha256(), List.of(), tokenContractErrors, 0);
        }
        List<ApplyError> errors = new ArrayList<>();
        List<WriteItem> writes = new ArrayList<>();
        for (ProductExcelImportPreviewService.PreviewRow row : preview.rows()) {
            if (!"ERROR".equals(row.classification())) {
                String expectedToken = request.expectedConcurrencyTokens() == null
                        ? null : request.expectedConcurrencyTokens().get(row.rowNumber());
                if (expectedToken == null || expectedToken.isBlank()) {
                    errors.add(error("CONCURRENCY_TOKEN_REQUIRED", row.rowNumber(),
                            "Cada fila necesita el token de concurrencia de la vista previa",
                            "expectedConcurrencyTokens[rowNumber]", "Vuelve a previsualizar y conserva el token recibido"));
                    continue;
                }
                if (row.concurrencyToken() == null || !expectedToken.equals(row.concurrencyToken())) {
                    errors.add(error("VERSION_STALE", row.rowNumber(),
                            "La fila ya no coincide con la vista previa; puede haber cambiado el producto o el fichero",
                            "Token de concurrencia vigente", "Vuelve a generar la vista previa"));
                    continue;
                }
            }
            if ("MISSING".equals(row.classification())) {
                if (!Boolean.TRUE.equals(request.autoAddMissing())) {
                    errors.add(error("MISSING_REVIEW_REQUIRED", row.rowNumber(),
                            "El producto no existe y las altas automaticas estan desactivadas",
                            "autoAddMissing=true o revision manual", "Activa altas automaticas tras revisar la vista previa"));
                } else {
                    ProductRequestResult product = productRequest(row, null, authoritative);
                    if (product.error() != null) errors.add(product.error());
                    else writes.add(new WriteItem(row, null, product.request()));
                }
            } else if ("EXISTING".equals(row.classification())) {
                UUID productId = uuid(row.databaseData().get("id"));
                Long expected = row.version();
                if (productId == null || expected == null) {
                    errors.add(error("VERSION_REQUIRED", row.rowNumber(),
                            "La vista previa no contiene la version interna del producto existente",
                            "version de la vista previa", "Vuelve a previsualizar y conserva el token recibido"));
                    continue;
                }
                ProductRequestResult product = productRequest(row, row.databaseData(), authoritative);
                if (product.error() != null) errors.add(product.error());
                else if (row.changes().isEmpty()) writes.add(new WriteItem(row, new ExpectedProduct(productId, expected), null));
                else writes.add(new WriteItem(row, new ExpectedProduct(productId, expected), product.request()));
            }
        }
        if (!errors.isEmpty()) {
            auditBestEffort(preview, "FALLO", errors.size(), authoritative);
            return new ApplyResult(preview.fileName(), preview.sha256(), List.of(), errors, 0);
        }
        boolean masterMutation = writes.stream().anyMatch(item -> item.request() != null);
        if (masterMutation && "WAREHOUSE_INPUT".equals(applyContext)
                && !Boolean.TRUE.equals(request.confirmMasterChanges())) {
            List<ApplyError> confirmationErrors = List.of(error("CONFIRMATION_REQUIRED", null,
                    "Debes confirmar explicitamente los cambios del maestro antes de aplicar",
                    "confirmMasterChanges=true", "Revisa la vista previa y confirma Aplicar y actualizar productos"));
            auditBestEffort(preview, "FALLO", confirmationErrors.size(), authoritative);
            return new ApplyResult(preview.fileName(), preview.sha256(), List.of(), confirmationErrors, 0);
        }
        requireMasterMutationPermission(authoritative, request.autoAddMissing(), preview);
        if (Boolean.TRUE.equals(request.updateSupplier())) {
            if (!"WAREHOUSE_INPUT".equals(applyContext) || request.supplierId() == null
                    || request.warehouseId() == null || request.documentDate() == null) {
                List<ApplyError> invalidProvenance = List.of(error("APPLY_PROVENANCE_REQUIRED", null,
                        "La actualizacion de proveedor necesita el borrador Warehouse completo",
                        "warehouseId, documentDate y supplierId", "Vuelve a previsualizar desde la entrada de almacen"));
                auditBestEffort(preview, "FALLO", invalidProvenance.size(), authoritative);
                return new ApplyResult(preview.fileName(), preview.sha256(), List.of(), invalidProvenance, 0);
            }
        }
        ProductExcelImportApplyCommitter.CommitResult committed;
        try {
            committed = committer.commit(preview, writes, new ApplyRequest(authoritative,
                            request.expectedConcurrencyTokens(), request.autoAddMissing(),
                            request.confirmMasterChanges(), request.warehouseId(), request.documentDate(),
                            request.supplierId(), request.updateSupplier()));
        } catch (ProductExcelImportApplyCommitter.WarehouseImportContextException exception) {
            auditBestEffort(preview, "FALLO", 1, authoritative);
            return new ApplyResult(preview.fileName(), preview.sha256(), List.of(),
                    List.of(error("APPLY_CONTEXT_INVALID", null,
                            "El almacen o proveedor del borrador no pertenece al contexto activo",
                            "Almacen activo de la tienda y proveedor activo de la empresa",
                            "Revisa el documento y genera una nueva vista previa")), 0);
        } catch (RuntimeException exception) {
            String code = exception instanceof ProductExcelImportApplyWriter.StaleVersionException
                    || exception instanceof ProductExcelImportApplyWriter.ConcurrentImportConflictException
                    ? "VERSION_STALE" : "APPLY_TRANSACTION_FAILED";
            auditBestEffort(preview, "FALLO", 1, authoritative);
            return new ApplyResult(preview.fileName(), preview.sha256(), List.of(),
                    List.of(error(code, null,
                            "No se pudo aplicar el lote; la transaccion fue revertida", "Lote completo valido",
                            "Corrige el error y genera una nueva vista previa")), 0);
        }
        List<ProductExcelImportApplyWriter.AppliedProduct> applied = committed.applied();
        Map<UUID, ProductExcelImportApplyWriter.AppliedProduct> byId = new LinkedHashMap<>();
        Map<Integer, ProductExcelImportApplyWriter.AppliedProduct> byRow = new LinkedHashMap<>();
        for (ProductExcelImportApplyWriter.AppliedProduct item : applied) {
            if (item.sourceId() != null) byId.put(item.sourceId(), item);
            byRow.put(item.rowNumber(), item);
        }
        List<AppliedRow> rows = preview.rows().stream().map(row -> {
            UUID sourceId = uuid(row.databaseData() == null ? null : row.databaseData().get("id"));
            ProductExcelImportApplyWriter.AppliedProduct item = sourceId == null ? null : byId.get(sourceId);
            if (item == null && "MISSING".equals(row.classification())) {
                item = byRow.get(row.rowNumber());
            }
            return new AppliedRow(row.rowNumber(), row.rowNumbers(), row.classification(),
                    item == null ? null : item.productId(), row.errors());
        }).toList();
        ApplyResult result = new ApplyResult(preview.fileName(), preview.sha256(), rows, List.of(),
                (int) applied.stream().filter(ProductExcelImportApplyWriter.AppliedProduct::mutated).count(),
                committed.warehouseMetadata(), committed.warehouseProvenanceToken());
        // The writer transaction has already completed. Audit is best-effort and must
        // never turn a committed import into a false APPLY_TRANSACTION_FAILED response.
        auditBestEffort(preview, "EXITO", 0, authoritative);
        return result;
    }

    private ApplyResult applyOperation(MultipartFile file, ApplyRequest request) {
        var original = forceDatabaseSnapshot(canonicalizeContext(request.preview()));
        requireContextPermission(original);
        boolean prepare = request.operation() == Operation.PREPARE_DESTINATION;
        if (!prepare && !PermissionChecks.hasProductManagement(SecurityContextHolder.getContext().getAuthentication())) {
            deny("GESTION_PRODUCTO es necesario para crear o actualizar productos");
        }
        var current = previewService.preview(file, original);
        if (request.expectedPreviewFingerprint() == null || !SHA256.matcher(request.expectedPreviewFingerprint()).matches()
                || !request.expectedPreviewFingerprint().equals(current.previewFingerprint())) {
            return operationFailure(current, original, request.operation(), List.of(error("VERSION_STALE", null,
                    "La revisión ya no coincide con los datos actuales", "Vista previa vigente", "Vuelve a aplicar el mapeo")));
        }
        List<ApplyError> errors = applyErrors(current.errors().stream().filter(error -> error.row() == null).toList());
        if (!errors.isEmpty()) return operationFailure(current, original, request.operation(), errors);
        if (!prepare && !Boolean.TRUE.equals(request.confirmMasterChanges())) {
            return operationFailure(current, original, request.operation(), List.of(error("CONFIRMATION_REQUIRED", null,
                    "Confirma la operación sobre las fichas", "Confirmación explícita", "Revisa el número de productos afectados")));
        }
        if (request.operation() == Operation.UPDATE_PURCHASE_PRICE
                && (original.updateFields() == null || !Boolean.TRUE.equals(original.updateFields().get("purchasePrice")))) {
            return operationFailure(current, original, request.operation(), List.of(error("CONFIRMATION_REQUIRED", null,
                    "Precio de compra no está marcado para actualizar", "Atributo marcado", "Marca Precio de compra y vuelve a aplicar")));
        }
        Set<Integer> targetNumbers = current.rows().stream().filter(row -> switch (request.operation()) {
            case CREATE_MISSING -> "MISSING".equals(row.existence());
            case UPDATE_PURCHASE_PRICE -> "EXISTING".equals(row.existence()) && row.purchasePriceChanged();
            case UPDATE_SELECTED_FIELDS, PREPARE_DESTINATION -> "EXISTING".equals(row.existence());
        }).flatMap(row -> row.rowNumbers().stream()).collect(Collectors.toSet());
        var options = original.options();
        Map<String, Boolean> fields = prepare && !"STOCK".equals(options.context()) ? Map.of()
                : request.operation() == Operation.UPDATE_PURCHASE_PRICE ? Map.of("purchasePrice", true)
                : original.updateFields() == null ? Map.of() : original.updateFields();
        // Master actions validate product attributes, never document quantities or tariffs.
        var scopedOptions = new ProductExcelImportPreviewService.PreviewOptions(options.globalValues(), options.valueSources(),
                false, prepare ? options.context() : "STOCK", options.storeId(), options.companyId(), options.skipZeroPriceUpdate(),
                prepare && Boolean.TRUE.equals(options.requireQuantity()), prepare ? options.documentPriceSource() : null);
        var scopedRequest = new ProductExcelImportPreviewService.PreviewRequest(original.mapping(), original.edits(), scopedOptions,
                original.storeId(), original.companyId(), original.expectedSha256(), original.startRow(), original.quantityColumn(),
                fields, original.resolvedProducts());
        var checked = previewService.preview(file, scopedRequest);
        var targets = checked.rows().stream().filter(row -> row.rowNumbers().stream().anyMatch(targetNumbers::contains)).toList();
        errors = new ArrayList<>(applyErrors(checked.errors().stream()
                .filter(error -> error.row() == null || targetNumbers.contains(error.row())).toList()));
        for (var row : targets) errors.addAll(applyErrors(row.errors()));
        if (!errors.isEmpty()) return operationFailure(current, original, request.operation(), errors);
        // A product cannot change between the review read and the operation-specific validation.
        Map<Integer, ProductExcelImportPreviewService.PreviewRow> reviewedByNumber = new LinkedHashMap<>();
        for (var row : current.rows()) for (var number : row.rowNumbers()) reviewedByNumber.put(number, row);
        List<WriteItem> writes = new ArrayList<>();
        for (var row : targets) {
            var reviewed = reviewedByNumber.get(row.rowNumber());
            UUID id = row.databaseData() == null ? null : uuid(row.databaseData().get("id"));
            UUID reviewedId = reviewed.databaseData() == null ? null : uuid(reviewed.databaseData().get("id"));
            if (!Objects.equals(id, reviewedId) || !Objects.equals(row.version(), reviewed.version())) {
                errors.add(error("VERSION_STALE", row.rowNumber(), "El producto ha cambiado", "Versión vigente", "Vuelve a aplicar"));
                continue;
            }
            if (prepare) {
                if (id != null) writes.add(new WriteItem(row, new ExpectedProduct(id, row.version()), null));
            } else if (id == null || !row.changes().isEmpty()) {
                var product = productRequest(row, row.databaseData(), scopedRequest);
                if (product.error() != null) errors.add(product.error());
                else writes.add(new WriteItem(row, id == null ? null : new ExpectedProduct(id, row.version()), product.request()));
            }
        }
        if (!errors.isEmpty()) return operationFailure(current, original, request.operation(), errors);
        var targetedPreview = new ProductExcelImportPreviewService.PreviewResult(current.fileName(), current.sha256(),
                current.sheetName(), targets, targets.size(), targets.stream().filter(row -> row.databaseData() != null).count(),
                targets.stream().filter(row -> row.databaseData() == null).count(), List.of(), current.formulas(), current.previewFingerprint());
        final ProductExcelImportApplyCommitter.CommitResult committed;
        try {
            committed = prepare ? committer.commit(targetedPreview, writes, request)
                    : new ProductExcelImportApplyCommitter.CommitResult(writer.write(writes), null, null);
        } catch (RuntimeException exception) {
            String code = exception instanceof ProductExcelImportApplyWriter.StaleVersionException
                    || exception instanceof ProductExcelImportApplyWriter.ConcurrentImportConflictException
                    ? "VERSION_STALE" : exception instanceof ProductExcelImportApplyCommitter.WarehouseImportContextException
                    ? "APPLY_CONTEXT_INVALID" : "APPLY_TRANSACTION_FAILED";
            return operationFailure(current, original, request.operation(), List.of(error(code, null,
                    "No se completó la operación; no se guardó el lote", "Lote válido y vigente", "Vuelve a aplicar y revisar")));
        }
        Map<Integer, ProductExcelImportApplyWriter.AppliedProduct> appliedByNumber = committed.applied().stream()
                .collect(Collectors.toMap(ProductExcelImportApplyWriter.AppliedProduct::rowNumber, item -> item));
        var appliedRows = targets.stream().map(row -> {
            var applied = appliedByNumber.get(row.rowNumber());
            return new AppliedRow(row.rowNumber(), row.rowNumbers(), row.classification(), applied == null
                    ? row.databaseData() == null ? null : uuid(row.databaseData().get("id")) : applied.productId(), List.of());
        }).toList();
        auditOperation(targetedPreview, original, request.operation(), List.of(), writes, committed.applied());
        return new ApplyResult(current.fileName(), current.sha256(), appliedRows, List.of(),
                (int) committed.applied().stream().filter(ProductExcelImportApplyWriter.AppliedProduct::mutated).count(),
                committed.warehouseMetadata(), committed.warehouseProvenanceToken());
    }

    private ApplyResult operationFailure(ProductExcelImportPreviewService.PreviewResult preview,
            ProductExcelImportPreviewService.PreviewRequest request, Operation operation, List<ApplyError> errors) {
        auditOperation(preview, request, operation, errors, List.of(), List.of());
        return new ApplyResult(preview.fileName(), preview.sha256(), List.of(), errors, 0);
    }

    static WarehouseExcelImportMetadata warehouseMetadata(ProductExcelImportPreviewService.PreviewResult preview,
            List<ProductExcelImportApplyWriter.AppliedProduct> applied, ApplyRequest request) {
        Map<Integer, ProductExcelImportApplyWriter.AppliedProduct> byRow = applied.stream()
                .collect(Collectors.toMap(ProductExcelImportApplyWriter.AppliedProduct::rowNumber, item -> item,
                        (left, right) -> left, LinkedHashMap::new));
        List<WarehouseExcelImportMetadata.Line> lines = preview.rows().stream()
                .filter(row -> row.errors().isEmpty())
                .map(row -> {
                    var item = byRow.get(row.rowNumber());
                    if (item == null || item.productId() == null) return null;
                    String reference = textValue(row.excelData().get("supplierReference"));
                    if (reference == null || reference.isBlank()) reference = firstText(row.excelData(), "code", "barcode");
                    BigDecimal gross = decimalValue(row.excelData().get("purchasePrice"));
                    if (gross == null && row.databaseData() != null) gross = decimalValue(row.databaseData().get("purchasePrice"));
                    BigDecimal discount = decimalValue(row.excelData().get("purchaseDiscountPercent"));
                    return new WarehouseExcelImportMetadata.Line(item.productId(), row.rowNumbers(), reference, gross, discount);
                })
                .filter(Objects::nonNull)
                .toList();
        List<WarehouseExcelImportMetadata.Formula> formulas = preview.formulas().stream()
                .map(formula -> new WarehouseExcelImportMetadata.Formula(formula.cell(), formula.formula(), formula.calculatedValue()))
                .toList();
        return new WarehouseExcelImportMetadata(preview.fileName(), formulas, preview.sha256(), preview.sheetName(),
                Boolean.TRUE.equals(request.updateSupplier()), authoritativeSkipZero(request), lines);
    }

    private static boolean authoritativeSkipZero(ApplyRequest request) {
        return request.preview() != null && request.preview().options() != null
                && request.preview().options().skipZeroPriceUpdate();
    }

    private static String firstText(Map<String, Object> data, String... fields) {
        for (String field : fields) {
            String value = textValue(data.get(field));
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String textValue(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private static BigDecimal decimalValue(Object value) {
        String text = textValue(value);
        if (text == null) return null;
        try { return new BigDecimal(text.replace(',', '.')); }
        catch (NumberFormatException exception) { return null; }
    }

    private ProductExcelImportPreviewService.PreviewRequest forceDatabaseSnapshot(
            ProductExcelImportPreviewService.PreviewRequest request) {
        var options = request.options();
        var forced = options == null ? null : new ProductExcelImportPreviewService.PreviewOptions(
                options.globalValues(), options.valueSources(), false, options.context(), options.storeId(),
                options.companyId(), options.skipZeroPriceUpdate(), options.requireQuantity(),
                options.documentPriceSource());
        return new ProductExcelImportPreviewService.PreviewRequest(request.mapping(), request.edits(), forced,
                request.storeId(), request.companyId(), request.expectedSha256(), request.startRow(),
                request.quantityColumn(), request.updateFields(), request.resolvedProducts());
    }

    private static ProductExcelImportPreviewService.PreviewRequest canonicalizeContext(
            ProductExcelImportPreviewService.PreviewRequest request) {
        var options = request.options();
        if (options == null) return request;
        var canonical = new ProductExcelImportPreviewService.PreviewOptions(
                options.globalValues(), options.valueSources(), options.showOnlyImported(),
                ProductExcelImportPreviewService.canonicalContext(options.context()),
                options.storeId(), options.companyId(), options.skipZeroPriceUpdate(), options.requireQuantity(),
                options.documentPriceSource());
        return new ProductExcelImportPreviewService.PreviewRequest(request.mapping(), request.edits(), canonical,
                request.storeId(), request.companyId(), request.expectedSha256(), request.startRow(),
                request.quantityColumn(), request.updateFields(), request.resolvedProducts());
    }

    private void requireContextPermission(ProductExcelImportPreviewService.PreviewRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String context = request.options() == null ? null : request.options().context();
        boolean warehouse = "WAREHOUSE_INPUT".equals(context) || "WAREHOUSE_OUTPUT".equals(context);
        boolean allowed = "STOCK".equals(context) ? PermissionChecks.hasProductManagement(authentication)
                : warehouse && PermissionChecks.hasWarehouseManagement(authentication);
        if (!allowed) deny("Permiso insuficiente para aplicar la importacion");
    }

    private void requireMasterMutationPermission(ProductExcelImportPreviewService.PreviewRequest request,
            Boolean autoAddMissing, ProductExcelImportPreviewService.PreviewResult preview) {
        boolean mutation = Boolean.TRUE.equals(autoAddMissing)
                && preview.rows().stream().anyMatch(row -> "MISSING".equals(row.classification()));
        mutation = mutation || preview.rows().stream().anyMatch(row -> "EXISTING".equals(row.classification())
                && !row.changes().isEmpty());
        boolean warehouse = request.options() != null && request.options().context().startsWith("WAREHOUSE");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (mutation && warehouse && !PermissionChecks.hasProductManagement(authentication)) {
            deny("GESTION_PRODUCTO es necesario para modificar el maestro");
        }
    }

    private static List<ApplyError> validateConcurrencyTokens(Map<Integer, String> submitted,
            ProductExcelImportPreviewService.PreviewResult preview) {
        Map<Integer, String> tokens = submitted == null ? Map.of() : submitted;
        if (tokens.size() > MAX_CONCURRENCY_TOKENS) {
            return List.of(error("TOKEN_LIMIT", null,
                    "El contrato supera el número máximo de tokens de concurrencia", "Hasta 5.000 tokens",
                    "Reduce las filas aplicables o genera una vista previa por lotes"));
        }
        Set<Integer> applicable = preview.rows().stream()
                .filter(row -> row.errors().isEmpty() && ("EXISTING".equals(row.classification())
                        || "MISSING".equals(row.classification())))
                .map(ProductExcelImportPreviewService.PreviewRow::rowNumber)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        List<ApplyError> errors = new ArrayList<>();
        for (Integer key : tokens.keySet()) {
            if (key == null || !applicable.contains(key)) {
                errors.add(error("TOKEN_UNEXPECTED", key,
                        "El token no corresponde a una fila aplicable de esta vista previa",
                        "Una clave rowNumber de una fila EXISTING o MISSING", "Vuelve a generar la vista previa"));
            }
        }
        for (Integer key : applicable) {
            String token = tokens.get(key);
            if (token == null || token.isBlank()) {
                errors.add(error("CONCURRENCY_TOKEN_REQUIRED", key,
                        "Cada fila aplicable necesita su token de concurrencia", "SHA-256 de 64 caracteres",
                        "Vuelve a previsualizar y conserva el token recibido"));
            } else if (!SHA256.matcher(token).matches()) {
                errors.add(error("TOKEN_INVALID", key,
                        "El token de concurrencia no tiene formato SHA-256", "64 caracteres hexadecimales",
                        "Vuelve a generar la vista previa"));
            }
        }
        return errors;
    }

    /** Reject abusive token payloads before the authoritative re-preview. */
    private static List<ApplyError> validateTokenPayload(Map<Integer, String> submitted) {
        if (submitted == null || submitted.isEmpty()) return List.of();
        if (submitted.size() > MAX_CONCURRENCY_TOKENS) {
            return List.of(error("TOKEN_LIMIT", null,
                    "El contrato supera el número máximo de tokens de concurrencia", "Hasta 5.000 tokens",
                    "Reduce las filas aplicables o genera una vista previa por lotes"));
        }
        long characters = 0L;
        List<ApplyError> errors = new ArrayList<>();
        int invalid = 0;
        for (Map.Entry<Integer, String> entry : submitted.entrySet()) {
            String token = entry.getValue();
            characters += token == null ? 0 : token.length();
            if (token == null || token.isBlank()) {
                if (invalid++ < 100) errors.add(error("CONCURRENCY_TOKEN_REQUIRED", entry.getKey(),
                        "Cada fila aplicable necesita su token de concurrencia", "SHA-256 de 64 caracteres",
                        "Vuelve a previsualizar y conserva el token recibido"));
            } else if (!SHA256.matcher(token).matches()) {
                if (invalid++ < 100) errors.add(error("TOKEN_INVALID", entry.getKey(),
                        "El token de concurrencia no tiene formato SHA-256", "64 caracteres hexadecimales",
                        "Vuelve a generar la vista previa"));
            }
        }
        if (invalid > 100) errors.add(error("TOKEN_LIMIT", null,
                "Hay demasiados tokens inválidos para devolver detalle individual", "Hasta 100 errores detallados",
                "Corrige los tokens y vuelve a generar la vista previa"));
        if (characters > (long) MAX_CONCURRENCY_TOKENS * 64L) {
            errors.add(error("TOKEN_LIMIT", null,
                    "El contrato supera el presupuesto de texto de tokens", "Hasta 320.000 caracteres",
                    "Reduce los tokens enviados"));
        }
        return errors;
    }

    private void deny(String message) {
        throw new org.springframework.security.access.AccessDeniedException(message);
    }

    private void auditOperation(ProductExcelImportPreviewService.PreviewResult preview,
            ProductExcelImportPreviewService.PreviewRequest request, Operation operation, List<ApplyError> errors,
            List<WriteItem> writes, List<ProductExcelImportApplyWriter.AppliedProduct> applied) {
        Set<Integer> mutatedRows = applied.stream().filter(ProductExcelImportApplyWriter.AppliedProduct::mutated)
                .map(ProductExcelImportApplyWriter.AppliedProduct::rowNumber).collect(Collectors.toSet());
        var mutatedWrites = writes.stream().filter(item -> mutatedRows.contains(item.row().rowNumber())).toList();
        Map<String, Long> changedFields = new LinkedHashMap<>();
        mutatedWrites.stream().flatMap(item -> item.row().changes().keySet().stream())
                .forEach(field -> changedFields.merge(field, 1L, Long::sum));
        auditBestEffort(preview, errors.isEmpty() ? "EXITO" : "FALLO", errors.size(), request, Map.of(
                "operation", operation.name(),
                "createdCount", mutatedWrites.stream().filter(item -> item.existing() == null).count(),
                "updatedCount", mutatedWrites.stream().filter(item -> item.existing() != null).count(),
                "unchangedCount", applied.stream().filter(item -> !item.mutated()).count(),
                "changedFields", changedFields));
    }

    private void audit(ProductExcelImportPreviewService.PreviewResult preview, String result, int errors,
            ProductExcelImportPreviewService.PreviewRequest request, Map<String, Object> operationDetails) {
        if (audit != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("fileName", preview.fileName());
            details.put("sha256", preview.sha256());
            details.put("rows", preview.detectedRows());
            details.put("errors", errors);
            details.put("context", auditContext(request));
            details.put("createdCount", preview.rows().stream().filter(row -> "MISSING".equals(row.classification())).count());
            details.put("updatedCount", preview.rows().stream().filter(row -> "EXISTING".equals(row.classification()) && row.masterDataChanged()).count());
            details.put("unchangedCount", preview.rows().stream().filter(row -> "EXISTING".equals(row.classification()) && !row.masterDataChanged()).count());
            Map<String, Long> changedFields = new LinkedHashMap<>();
            preview.rows().stream().flatMap(row -> row.changes().keySet().stream()).forEach(field ->
                    changedFields.merge(field, 1L, Long::sum));
            details.put("changedFields", changedFields);
            details.putAll(operationDetails);
            if (organization != null) {
                try {
                    details.put("storeId", organization.currentStore().getId().toString());
                    details.put("companyId", organization.currentCompany().getId().toString());
                } catch (RuntimeException ignored) {
                    // Keep audit best effort when a direct caller has no session.
                }
            }
            audit.record("PRODUCT_EXCEL_IMPORT_APPLY", "EXITO".equals(result) ? AuditResult.EXITO : AuditResult.FALLO,
                    details);
        }
    }

    private void auditBestEffort(ProductExcelImportPreviewService.PreviewResult preview, String result, int errors,
            ProductExcelImportPreviewService.PreviewRequest request) {
        auditBestEffort(preview, result, errors, request, Map.of());
    }

    private void auditBestEffort(ProductExcelImportPreviewService.PreviewResult preview, String result, int errors,
            ProductExcelImportPreviewService.PreviewRequest request, Map<String, Object> operationDetails) {
        try {
            audit(preview, result, errors, request, operationDetails);
        } catch (RuntimeException ignored) {
            // Auditing must not alter the result of an already committed write.
        }
    }

    private ProductRequestResult productRequest(ProductExcelImportPreviewService.PreviewRow row,
            Map<String, Object> database, ProductExcelImportPreviewService.PreviewRequest request) {
        Map<String, Object> data = new LinkedHashMap<>(database == null ? Map.of() : database);
        data.putAll(row.excelData());
        Set<String> fields = request.updateFields() == null ? UPDATABLE_FIELDS : request.updateFields().entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue())).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        if (database != null) {
            boolean combinedFamily = ProductExcelImportPreviewService.combinedFamilyUpdate(row.excelData(), request.updateFields());
            for (String field : List.of("name", "description", "comments", "familyId", "subfamilyId", "taxId", "productType",
                    "purchasePrice", "purchaseDiscountPercent", "taxesIncluded", "salePrice", "memberPrice", "wholesalePrice",
                    "offerPrice", "offerDiscountPercent", "offerActive", "offerFrom", "offerUntil", "packageQuantity", "stockMin", "stockMax")) {
                if (!(combinedFamily && "subfamilyId".equals(field))
                        && (!fields.contains(field) || string(data.get(field), "").isBlank()) && database.containsKey(field)) data.put(field, database.get(field));
            }
            if (!fields.contains("priceUseMode") || string(data.get("priceUseMode"), "").isBlank()) data.put("priceUseMode", database.get("priceUseMode"));
            if (!fields.contains("discountType") || string(data.get("discountType"), "").isBlank()) data.put("discountType", database.get("discountType"));
            if (!fields.contains("discountType") && !fields.contains("prohibitedDiscount")) {
                data.put("prohibitedDiscount", data.get("discountType"));
            }
            // Primary identities are lookup keys, not updateFields; never replace them accidentally.
            data.put("code", database.get("code"));
            data.put("barcode", database.get("barcode"));
            if (!fields.contains("barcode2") || string(data.get("barcode2"), "").isBlank()) data.put("barcode2", database.get("barcode2"));
            if (Boolean.TRUE.equals(request.options() != null && request.options().skipZeroPriceUpdate())) {
                for (String field : List.of("purchasePrice", "salePrice", "memberPrice", "wholesalePrice", "offerPrice")) {
                    if (isZero(data.get(field))) data.put(field, database.get(field));
                }
            }
        } else {
            // Catalog defaults for a new product are zero prices and taxes included.
            if (decimal(data.get("purchasePrice")) == null) data.put("purchasePrice", "0");
            if (decimal(data.get("salePrice")) == null) data.put("salePrice", "0");
            if (string(data.get("taxesIncluded"), "").isBlank()) data.put("taxesIncluded", "1");
            if (Boolean.TRUE.equals(request.options() != null && request.options().skipZeroPriceUpdate())) {
                for (String field : List.of("memberPrice", "wholesalePrice", "offerPrice")) {
                    if (isZero(data.get(field))) data.put(field, null);
                }
            }
        }
        if (database != null
                && string(row.excelData().get("discountType"), "").isBlank()
                && string(row.excelData().get("prohibitedDiscount"), "").isBlank()) {
            data.put("prohibitedDiscount", database.containsKey("prohibitedDiscount")
                    ? database.get("prohibitedDiscount") : database.get("discountType"));
        }
        if (!Boolean.TRUE.equals(request.options() != null && request.options().skipZeroPriceUpdate())) {
            for (String field : List.of("memberPrice", "wholesalePrice", "offerPrice")) {
                if (isZero(data.get(field))) {
                    return new ProductRequestResult(null, error("ZERO_PRICE_INVALID", row.rowNumber(),
                            "Los precios opcionales no pueden ser cero", "Precio vacío o mayor que 0",
                            "Corrige el precio o activa skipZeroPriceUpdate"));
                }
            }
        }
        UUID familyId = uuid(data.get("familyId"));
        UUID taxId = uuid(data.get("taxId"));
        ProductType productType = enumValue(ProductType.class, data.get("productType"), ProductType.UNIT);
        PriceUseMode priceUse = enumValue(PriceUseMode.class, data.get("priceUseMode"), PriceUseMode.NORMAL);
        String prohibited = string(data.get("discountType"), string(data.get("prohibitedDiscount"), "0"));
        DiscountType discount = "1".equals(prohibited) || "NONE".equalsIgnoreCase(prohibited)
                ? DiscountType.NONE : DiscountType.NORMAL;
        BigDecimal purchase = decimal(data.get("purchasePrice"));
        BigDecimal sale = decimal(data.get("salePrice"));
        String name = string(data.get("name"), "");
        if (familyId == null || taxId == null || purchase == null || sale == null || name.isBlank()) {
            return new ProductRequestResult(null, error("APPLY_REQUIRED_VALUE", row.rowNumber(),
                    "Faltan valores obligatorios para persistir el producto", "familia, impuesto, nombre, precio de compra y venta",
                    "Completa los valores y vuelve a previsualizar"));
        }
        BigDecimal offerPrice = decimal(data.get("offerPrice"));
        BigDecimal offerDiscount = decimal(data.get("offerDiscountPercent"));
        BigDecimal memberPrice = decimal(data.get("memberPrice"));
        if (discount == DiscountType.NONE && (priceUse != PriceUseMode.NORMAL
                || memberPrice != null || offerPrice != null || offerDiscount != null)) {
            return new ProductRequestResult(null, error("DISCOUNT_PROHIBITED_PRICE_MODE", row.rowNumber(),
                    "Un producto con descuento prohibido no puede usar ni conservar precios de miembro u oferta",
                    "NORMAL sin precio de miembro, precio de oferta ni descuento de oferta",
                    "Elimina los precios especiales o cambia Prohibido descuento a 0"));
        }
        LocalDate offerFrom = date(data.get("offerFrom"));
        if ((priceUse == PriceUseMode.OFFER_PRICE || priceUse == PriceUseMode.OFFER_DISCOUNT) && offerFrom == null) {
            return new ProductRequestResult(null, error("APPLY_OFFER_REQUIRED", row.rowNumber(),
                    "Un modo de oferta necesita fecha de inicio", "Oferta desde", "Completa Oferta desde"));
        }
        if (priceUse == PriceUseMode.OFFER_PRICE && offerPrice == null) {
            return new ProductRequestResult(null, error("APPLY_OFFER_REQUIRED", row.rowNumber(),
                    "El precio de oferta es obligatorio para este modo", "Precio de oferta", "Completa Precio de oferta"));
        }
        if (priceUse == PriceUseMode.OFFER_DISCOUNT && offerDiscount == null) {
            return new ProductRequestResult(null, error("APPLY_OFFER_REQUIRED", row.rowNumber(),
                    "El descuento de oferta es obligatorio para este modo", "Descuento oferta %", "Completa Descuento oferta %"));
        }
        CatalogService.ProductRequest product = new CatalogService.ProductRequest(familyId, uuid(data.get("subfamilyId")), taxId,
                productType, discount, priceUse, name, string(data.get("description"), null), string(data.get("comments"), null),
                purchase, booleanValue(data.get("taxesIncluded")), string(data.get("code"), null), string(data.get("barcode"), null),
                string(data.get("barcode2"), null), sale, memberPrice, decimal(data.get("wholesalePrice")),
                offerPrice, offerDiscount, decimal(data.get("purchaseDiscountPercent")),
                booleanValue(data.get("offerActive")), offerFrom, date(data.get("offerUntil")),
                decimal(data.get("stockMin")), decimal(data.get("stockMax")), decimal(data.get("packageQuantity")), null, null);
        return new ProductRequestResult(product, null);
    }

    private static boolean booleanValue(Object value) { return "1".equals(string(value, "0")) || Boolean.TRUE.equals(value); }
    private static String string(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private static BigDecimal decimal(Object value) { try { return value == null || string(value, "").isBlank() ? null : new BigDecimal(string(value, "").replace(',', '.')); } catch (NumberFormatException exception) { return null; } }
    private static boolean isZero(Object value) { BigDecimal number = decimal(value); return number != null && number.signum() == 0; }
    private static LocalDate date(Object value) { try { return value == null || string(value, "").isBlank() ? null : LocalDate.parse(string(value, "")); } catch (RuntimeException exception) { return null; } }
    private static UUID uuid(Object value) { try { return value == null ? null : UUID.fromString(String.valueOf(value)); } catch (IllegalArgumentException exception) { return null; } }
    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, E fallback) { try { return value == null ? fallback : Enum.valueOf(type, String.valueOf(value).toUpperCase()); } catch (IllegalArgumentException exception) { return fallback; } }
    private static ApplyError error(String code, Integer row, String reason, String accepted, String fix) {
        return new ApplyError(code, row, null, null, null, reason, accepted, fix);
    }
    private static List<ApplyError> applyErrors(Collection<ProductExcelImportPreviewService.ImportError> source) {
        return source.stream().map(error -> new ApplyError(error.code(), error.row(), error.column(), error.attribute(),
                error.receivedValue(), error.reason(), error.acceptedValues(), error.recommendedFix())).toList();
    }
    private ApplyResult fail(ApplyRequest request, MultipartFile file, List<ApplyError> errors) {
        String fileName = safeFileName(file == null ? null : file.getOriginalFilename());
        String suppliedHash = request == null || request.preview() == null ? null : request.preview().expectedSha256();
        String expectedHash = suppliedHash != null && suppliedHash.matches("[0-9a-fA-F]{64}") ? suppliedHash : "";
        String suppliedContext = request == null || request.preview() == null || request.preview().options() == null
                ? null : request.preview().options().context();
        String context = ProductExcelImportPreviewService.canonicalContext(suppliedContext);
        if (context == null || !Set.of("STOCK", "WAREHOUSE_INPUT", "WAREHOUSE_OUTPUT").contains(context)) context = "";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fileName", fileName);
        details.put("sha256", expectedHash);
        details.put("context", context);
        details.put("errors", errors.size());
        details.put("errorCodes", errors.stream().map(ApplyError::code).distinct().toList());
        if (organization != null) {
            try {
                if (organization.currentStore() != null && organization.currentStore().getId() != null) {
                    details.put("storeId", organization.currentStore().getId().toString());
                }
                if (organization.currentCompany() != null && organization.currentCompany().getId() != null) {
                    details.put("companyId", organization.currentCompany().getId().toString());
                }
            } catch (RuntimeException ignored) {
                // Tenant context is optional for direct service callers; never trust client IDs for audit.
            }
        }
        try {
            if (audit != null) audit.record("PRODUCT_EXCEL_IMPORT_APPLY", AuditResult.FALLO, details);
        } catch (RuntimeException ignored) {
            // Returning a structured validation error must not depend on audit availability.
        }
        return new ApplyResult(fileName, expectedHash.isBlank() ? null : expectedHash, List.of(), errors, 0);
    }

    private static String safeFileName(String value) {
        String name = value == null ? "import.xlsx" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return name.isBlank() ? "import.xlsx" : name.substring(0, Math.min(255, name.length()));
    }

    private static String auditContext(ProductExcelImportPreviewService.PreviewRequest request) {
        String raw = request == null || request.options() == null ? null : request.options().context();
        String canonical = ProductExcelImportPreviewService.canonicalContext(raw);
        return canonical != null && Set.of("STOCK", "WAREHOUSE_INPUT", "WAREHOUSE_OUTPUT").contains(canonical)
                ? canonical : "";
    }

    public record ApplyRequest(ProductExcelImportPreviewService.PreviewRequest preview,
            Map<Integer, String> expectedConcurrencyTokens, Boolean autoAddMissing, Boolean confirmMasterChanges,
            UUID warehouseId, LocalDate documentDate, UUID supplierId, Boolean updateSupplier,
            Operation operation, String expectedPreviewFingerprint) {
        public ApplyRequest(ProductExcelImportPreviewService.PreviewRequest preview,
                Map<Integer, String> expectedConcurrencyTokens, Boolean autoAddMissing, Boolean confirmMasterChanges,
                UUID warehouseId, LocalDate documentDate, UUID supplierId, Boolean updateSupplier) {
            this(preview, expectedConcurrencyTokens, autoAddMissing, confirmMasterChanges, warehouseId, documentDate,
                    supplierId, updateSupplier, null, null);
        }
        public ApplyRequest(ProductExcelImportPreviewService.PreviewRequest preview,
                Map<Integer, String> expectedConcurrencyTokens, Boolean autoAddMissing, Boolean confirmMasterChanges) {
            this(preview, expectedConcurrencyTokens, autoAddMissing, confirmMasterChanges, null, null, null, false);
        }
    }
    public enum Operation { CREATE_MISSING, UPDATE_PURCHASE_PRICE, UPDATE_SELECTED_FIELDS, PREPARE_DESTINATION }
    public record AppliedRow(int rowNumber, List<Integer> rowNumbers, String classification, UUID productId,
            List<ProductExcelImportPreviewService.ImportError> errors) { }
    public record ApplyResult(String fileName, String sha256, List<AppliedRow> rows, List<ApplyError> errors,
            int appliedCount, WarehouseExcelImportMetadata warehouseMetadata, String warehouseProvenanceToken) {
        public ApplyResult(String fileName, String sha256, List<AppliedRow> rows, List<ApplyError> errors,
                int appliedCount) {
            this(fileName, sha256, rows, errors, appliedCount, null, null);
        }
    }
    public record ApplyError(String code, Integer row, Integer column, String attribute, String receivedValue,
            String reason, String acceptedValues, String recommendedFix) { }
    static record ExpectedProduct(UUID productId, long version) { }
    static record WriteItem(ProductExcelImportPreviewService.PreviewRow row, ExpectedProduct existing,
            CatalogService.ProductRequest request) { }
    private record ProductRequestResult(CatalogService.ProductRequest request, ApplyError error) { }
}
