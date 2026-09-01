-- Convert the retired-code ledgers into atomic issued-code ledgers. Active and
-- retired codes share the same primary key, so an INSERT claims a code before
-- the catalog row is written and can never race a concurrent DELETE/INSERT.
-- The claim rolls back automatically if the catalog INSERT later fails.
--
-- The migration lock provides a consistent one-time backfill. Runtime trigger
-- functions do not take table or owner-row locks: uniqueness on each ledger is
-- the only serialization point, avoiding row/owner lock-order cycles.

LOCK TABLE tienda IN EXCLUSIVE MODE;

LOCK TABLE familia, subfamilia,
    familia_codigo_reservado, subfamilia_codigo_reservado
    IN SHARE ROW EXCLUSIVE MODE;

INSERT INTO familia_codigo_reservado (tienda_id, family_code)
SELECT tienda_id, family_code
FROM familia
ON CONFLICT (tienda_id, family_code) DO NOTHING;

INSERT INTO subfamilia_codigo_reservado (familia_id, subfamily_suffix)
SELECT familia_id, subfamily_suffix
FROM subfamilia
ON CONFLICT (familia_id, subfamily_suffix) DO NOTHING;

CREATE OR REPLACE FUNCTION tpv_reserve_family_code_before_delete()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.predeterminada THEN
        RAISE EXCEPTION 'La familia GENERAL no se puede eliminar';
    END IF;
    EXECUTE format(
        'INSERT INTO %I.familia_codigo_reservado (tienda_id, family_code) '
        'VALUES ($1, $2) ON CONFLICT (tienda_id, family_code) DO NOTHING',
        TG_TABLE_SCHEMA)
    USING OLD.tienda_id, OLD.family_code;
    RETURN OLD;
END;
$$;

CREATE OR REPLACE FUNCTION tpv_validate_family_code_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    candidate INTEGER;
    selected_code VARCHAR(3);
    claimed_code VARCHAR(3);
    code_claimed BOOLEAN := FALSE;
BEGIN
    IF NEW.family_code IS NULL THEN
        IF NEW.predeterminada THEN
            NEW.family_code := '000';
        ELSE
            FOR candidate IN 1..999 LOOP
                selected_code := lpad(candidate::text, 3, '0');
                EXECUTE format(
                    'INSERT INTO %I.familia_codigo_reservado '
                    '(tienda_id, family_code) VALUES ($1, $2) '
                    'ON CONFLICT (tienda_id, family_code) DO NOTHING '
                    'RETURNING family_code',
                    TG_TABLE_SCHEMA)
                INTO claimed_code
                USING NEW.tienda_id, selected_code;
                IF claimed_code IS NOT NULL THEN
                    NEW.family_code := selected_code;
                    code_claimed := TRUE;
                    EXIT;
                END IF;
            END LOOP;
            IF NEW.family_code IS NULL THEN
                RAISE EXCEPTION 'No quedan familyCode disponibles para la tienda';
            END IF;
        END IF;
    END IF;
    IF (NEW.predeterminada AND (NEW.family_code <> '000' OR NEW.nombre <> 'GENERAL'))
       OR (NOT NEW.predeterminada AND NEW.family_code = '000') THEN
        RAISE EXCEPTION 'family_code no es valido para la familia';
    END IF;
    IF NOT code_claimed THEN
        EXECUTE format(
            'INSERT INTO %I.familia_codigo_reservado '
            '(tienda_id, family_code) VALUES ($1, $2) '
            'ON CONFLICT (tienda_id, family_code) DO NOTHING '
            'RETURNING family_code',
            TG_TABLE_SCHEMA)
        INTO claimed_code
        USING NEW.tienda_id, NEW.family_code;
        IF claimed_code IS NULL THEN
            RAISE EXCEPTION 'family_code ya fue emitido o reservado';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION tpv_reserve_subfamily_code_before_delete()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format(
        'INSERT INTO %I.subfamilia_codigo_reservado (familia_id, subfamily_suffix) '
        'VALUES ($1, $2) ON CONFLICT (familia_id, subfamily_suffix) DO NOTHING',
        TG_TABLE_SCHEMA)
    USING OLD.familia_id, OLD.subfamily_suffix;
    RETURN OLD;
END;
$$;

CREATE OR REPLACE FUNCTION tpv_validate_subfamily_code()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    parent_code VARCHAR(3);
    candidate INTEGER;
    selected_suffix VARCHAR(3);
    claimed_suffix VARCHAR(3);
    parent_default BOOLEAN;
    code_claimed BOOLEAN := FALSE;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.familia_id <> OLD.familia_id THEN
        RAISE EXCEPTION 'La familia de una subfamilia es inmutable';
    END IF;
    EXECUTE format(
        'SELECT family_code, predeterminada FROM %I.familia WHERE id = $1',
        TG_TABLE_SCHEMA)
    INTO parent_code, parent_default
    USING NEW.familia_id;
    IF parent_default THEN
        RAISE EXCEPTION 'La familia GENERAL no admite subfamilias';
    END IF;
    IF NEW.subfamily_suffix IS NULL THEN
        IF TG_OP <> 'INSERT' THEN
            RAISE EXCEPTION 'subfamily_suffix no puede quedar vacio';
        END IF;
        FOR candidate IN 1..999 LOOP
            selected_suffix := lpad(candidate::text, 3, '0');
            EXECUTE format(
                'INSERT INTO %I.subfamilia_codigo_reservado '
                '(familia_id, subfamily_suffix) VALUES ($1, $2) '
                'ON CONFLICT (familia_id, subfamily_suffix) DO NOTHING '
                'RETURNING subfamily_suffix',
                TG_TABLE_SCHEMA)
            INTO claimed_suffix
            USING NEW.familia_id, selected_suffix;
            IF claimed_suffix IS NOT NULL THEN
                NEW.subfamily_suffix := selected_suffix;
                NEW.subfamily_code := parent_code || selected_suffix;
                code_claimed := TRUE;
                EXIT;
            END IF;
        END LOOP;
        IF NEW.subfamily_suffix IS NULL THEN
            RAISE EXCEPTION 'No quedan subfamilySuffix disponibles para la familia';
        END IF;
    END IF;
    IF parent_code IS NULL OR NEW.subfamily_suffix = '000'
       OR NEW.subfamily_code <> parent_code || NEW.subfamily_suffix THEN
        RAISE EXCEPTION 'subfamily_code no coincide con la familia y el sufijo';
    END IF;
    IF TG_OP = 'INSERT' AND NOT code_claimed THEN
        EXECUTE format(
            'INSERT INTO %I.subfamilia_codigo_reservado '
            '(familia_id, subfamily_suffix) VALUES ($1, $2) '
            'ON CONFLICT (familia_id, subfamily_suffix) DO NOTHING '
            'RETURNING subfamily_suffix',
            TG_TABLE_SCHEMA)
        INTO claimed_suffix
        USING NEW.familia_id, NEW.subfamily_suffix;
        IF claimed_suffix IS NULL THEN
            RAISE EXCEPTION 'subfamilySuffix ya fue emitido o reservado';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION tpv_guard_catalog_code_ledger_append_only()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'El registro de codigos emitidos es append-only';
END;
$$;

CREATE TRIGGER tr_familia_codigo_reservado_append_only
BEFORE UPDATE OR DELETE ON familia_codigo_reservado
FOR EACH ROW EXECUTE FUNCTION tpv_guard_catalog_code_ledger_append_only();

CREATE TRIGGER tr_subfamilia_codigo_reservado_append_only
BEFORE UPDATE OR DELETE ON subfamilia_codigo_reservado
FOR EACH ROW EXECUTE FUNCTION tpv_guard_catalog_code_ledger_append_only();

CREATE TRIGGER tr_familia_codigo_reservado_no_truncate
BEFORE TRUNCATE ON familia_codigo_reservado
FOR EACH STATEMENT EXECUTE FUNCTION tpv_guard_catalog_code_ledger_append_only();

CREATE TRIGGER tr_subfamilia_codigo_reservado_no_truncate
BEFORE TRUNCATE ON subfamilia_codigo_reservado
FOR EACH STATEMENT EXECUTE FUNCTION tpv_guard_catalog_code_ledger_append_only();

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM familia f
        LEFT JOIN familia_codigo_reservado r
          ON r.tienda_id = f.tienda_id
         AND r.family_code = f.family_code
        WHERE r.tienda_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Hay codigos de familia activos fuera del registro de emitidos';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM subfamilia sf
        LEFT JOIN subfamilia_codigo_reservado r
          ON r.familia_id = sf.familia_id
         AND r.subfamily_suffix = sf.subfamily_suffix
        WHERE r.familia_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Hay codigos de subfamilia activos fuera del registro de emitidos';
    END IF;
END;
$$;
