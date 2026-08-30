-- A return document is a single business operation even when the local
-- recovery is delivered with more than one operation id.  Do not choose a
-- winner for legacy collisions: stop the migration and require an explicit
-- operational reconciliation before installing the uniqueness barrier.
DO $$
DECLARE
    collision_company UUID;
    collision_return_document UUID;
    collision_count BIGINT;
BEGIN
    SELECT company_id, return_document_id, COUNT(*)
      INTO collision_company, collision_return_document, collision_count
      FROM saas_member_balance_retention_receipt
     WHERE return_document_id IS NOT NULL
     GROUP BY company_id, return_document_id
    HAVING COUNT(*) > 1
     ORDER BY company_id, return_document_id
     LIMIT 1;

    IF collision_count IS NOT NULL THEN
        RAISE EXCEPTION
            'No se puede aplicar V40: existen % receipts para company_id % y return_document_id %; requiere reconciliacion explicita',
            collision_count, collision_company, collision_return_document
            USING ERRCODE = '23505';
    END IF;
END
$$;

CREATE UNIQUE INDEX uk_saas_member_balance_retention_receipt_return_document
    ON saas_member_balance_retention_receipt(company_id, return_document_id)
    WHERE return_document_id IS NOT NULL;
