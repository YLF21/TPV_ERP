package com.tpverp.backend.verifactu;

import java.util.List;

/** Read-only selections, shared by the fiscal lists and their exports. */
public record FiscalListChoices(List<FiscalSubmissionStatus> statuses,
        List<FiscalDocumentType> documentTypes, List<FiscalRecordOperation> operations, List<FiscalMode> fiscalModes) {
    public static final FiscalListChoices EMPTY = new FiscalListChoices(null, null, null, null);

    public FiscalListChoices {
        statuses = copy(statuses);
        documentTypes = copy(documentTypes);
        operations = copy(operations);
        fiscalModes = copy(fiscalModes);
    }

    public boolean hasValues() {
        return !statuses.isEmpty() || !documentTypes.isEmpty() || !operations.isEmpty() || !fiscalModes.isEmpty();
    }

    FiscalListChoices withDefaults(FiscalSubmissionStatus status, FiscalDocumentType type, FiscalRecordOperation operation) {
        return new FiscalListChoices(orSingle(statuses, status), orSingle(documentTypes, type),
                orSingle(operations, operation), fiscalModes);
    }

    FiscalListChoices withMode(FiscalMode mode) {
        return new FiscalListChoices(statuses, documentTypes, operations, orSingle(fiscalModes, mode));
    }

    void appendRecordPredicates(StringBuilder sql) {
        if (!operations.isEmpty()) sql.append(" and record.operacion in (:selectedOperations)\n");
        if (!documentTypes.isEmpty()) sql.append(" and record.tipo_documento_fiscal in (:selectedDocumentTypes)\n");
        if (!fiscalModes.isEmpty()) sql.append(" and record.modo_fiscal in (:selectedFiscalModes)\n");
    }

    void bindRecordParameters(org.springframework.jdbc.core.namedparam.MapSqlParameterSource parameters) {
        if (!operations.isEmpty()) parameters.addValue("selectedOperations", operations.stream().map(Enum::name).toList());
        if (!documentTypes.isEmpty()) parameters.addValue("selectedDocumentTypes", documentTypes.stream().map(Enum::name).toList());
        if (!fiscalModes.isEmpty()) parameters.addValue("selectedFiscalModes", fiscalModes.stream().map(Enum::name).toList());
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <T> List<T> orSingle(List<T> values, T fallback) {
        return values.isEmpty() && fallback != null ? List.of(fallback) : values;
    }
}
