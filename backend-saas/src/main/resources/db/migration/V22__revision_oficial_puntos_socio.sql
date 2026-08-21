CREATE SEQUENCE IF NOT EXISTS saas_member_points_official_revision_seq;

ALTER TABLE saas_member_balance_account
    ADD COLUMN IF NOT EXISTS official_revision BIGINT;

UPDATE saas_member_balance_account
SET official_revision = nextval('saas_member_points_official_revision_seq')
WHERE official_revision IS NULL;

ALTER TABLE saas_member_balance_account
    ALTER COLUMN official_revision SET DEFAULT nextval('saas_member_points_official_revision_seq'),
    ALTER COLUMN official_revision SET NOT NULL;

CREATE OR REPLACE FUNCTION touch_saas_member_points_official_revision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.points IS DISTINCT FROM OLD.points
            OR NEW.points_debt IS DISTINCT FROM OLD.points_debt THEN
        NEW.official_revision := nextval('saas_member_points_official_revision_seq');
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_saas_member_points_official_revision
    ON saas_member_balance_account;

CREATE TRIGGER trg_saas_member_points_official_revision
BEFORE UPDATE OF points, points_debt ON saas_member_balance_account
FOR EACH ROW
EXECUTE FUNCTION touch_saas_member_points_official_revision();

CREATE INDEX IF NOT EXISTS idx_saas_member_points_official_feed
    ON saas_member_balance_account (company_id, official_revision, member_id);
