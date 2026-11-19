package com.tpverp.saas.document;

/** Shared predicates for queries over the current commercial document projection (alias d). */
public final class CommercialDocumentFacts {
    private CommercialDocumentFacts() {}

    // A conversion replaces its origin even when the invoice is outside a detail filter.
    // COMPENSA links independent new-sale and return documents, so both retain their sign.
    public static final String COUNTS_AS_SALE = """
            (d.document_status <> 'ANULADO' and not (
              d.document_type in ('TICKET','ALBARAN_VENTA') and exists (
                select 1 from saas_commercial_document_relation r
                join saas_commercial_document invoice on invoice.company_id=r.company_id
                     and invoice.store_id=r.store_id and invoice.source_document_id=r.source_document_id
                where r.company_id=d.company_id and r.store_id=d.store_id
                  and r.origin_document_id=d.source_document_id and r.relation_type='FACTURA_DE'
                  and invoice.document_type='FACTURA_VENTA' and invoice.document_status <> 'ANULADO'
              )))
            """;

    public static final String UNKNOWN_RELATIONSHIPS = """
            (not d.relationships_complete or exists (
                select 1 from saas_commercial_document_relation r
                where r.company_id=d.company_id and r.store_id=d.store_id
                  and r.source_document_id=d.source_document_id and not exists (
                    select 1 from saas_commercial_document origin where origin.company_id=r.company_id
                      and origin.store_id=r.store_id and origin.source_document_id=r.origin_document_id)))
            """;
}
