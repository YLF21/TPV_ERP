-- Keep receipt and cross-operation alias ownership mutually exclusive even
-- while old and new SaaS nodes are processing the same operation concurrently.
-- V41 already preflights this collision; this migration installs a durable
-- guard for all future INSERTs without deleting or choosing a legacy winner.
-- Serialize the preflight/backfill with old nodes that do not know the guard
-- table yet. The lock is held by Flyway for the migration transaction.
LOCK TABLE saas_member_balance_retention_receipt,
           saas_member_balance_retention_receipt_alias
    IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE IF NOT EXISTS saas_member_balance_retention_operation_owner (
    operation_id UUID NOT NULL,
    owner_kind VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_saas_member_balance_retention_operation_owner
        PRIMARY KEY (operation_id, owner_kind),
    CONSTRAINT ck_saas_member_balance_retention_operation_owner_kind
        CHECK (owner_kind IN ('RECEIPT', 'ALIAS'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_saas_member_balance_retention_operation_owner_operation
    ON saas_member_balance_retention_operation_owner(operation_id);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM saas_member_balance_retention_receipt receipt
          JOIN saas_member_balance_retention_receipt_alias alias_row
            ON alias_row.operation_id = receipt.operation_id
    ) THEN
        RAISE EXCEPTION
            'No se puede aplicar V42: operation_id existe simultaneamente como receipt y alias'
            USING ERRCODE = '23505';
    END IF;
END
$$;

INSERT INTO saas_member_balance_retention_operation_owner(operation_id, owner_kind)
SELECT operation_id, 'RECEIPT'
  FROM saas_member_balance_retention_receipt
ON CONFLICT (operation_id) DO NOTHING;

INSERT INTO saas_member_balance_retention_operation_owner(operation_id, owner_kind)
SELECT operation_id, 'ALIAS'
  FROM saas_member_balance_retention_receipt_alias
ON CONFLICT (operation_id) DO NOTHING;

CREATE OR REPLACE FUNCTION saas_claim_member_balance_retention_operation_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    current_kind VARCHAR(16);
BEGIN
    INSERT INTO saas_member_balance_retention_operation_owner(operation_id, owner_kind)
    VALUES (NEW.operation_id, TG_ARGV[0])
    ON CONFLICT (operation_id) DO NOTHING;

    SELECT owner_kind
      INTO current_kind
      FROM saas_member_balance_retention_operation_owner
     WHERE operation_id = NEW.operation_id
     FOR UPDATE;

    IF current_kind <> TG_ARGV[0] THEN
        RAISE EXCEPTION
            'El operation_id ya pertenece a otro tipo de retention (% vs %)',
            current_kind, TG_ARGV[0]
            USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_saas_member_balance_retention_receipt_operation_owner
    ON saas_member_balance_retention_receipt;
CREATE TRIGGER trg_saas_member_balance_retention_receipt_operation_owner
    BEFORE INSERT ON saas_member_balance_retention_receipt
    FOR EACH ROW
    EXECUTE FUNCTION saas_claim_member_balance_retention_operation_owner('RECEIPT');

DROP TRIGGER IF EXISTS trg_saas_member_balance_retention_alias_operation_owner
    ON saas_member_balance_retention_receipt_alias;
CREATE TRIGGER trg_saas_member_balance_retention_alias_operation_owner
    BEFORE INSERT ON saas_member_balance_retention_receipt_alias
    FOR EACH ROW
    EXECUTE FUNCTION saas_claim_member_balance_retention_operation_owner('ALIAS');
