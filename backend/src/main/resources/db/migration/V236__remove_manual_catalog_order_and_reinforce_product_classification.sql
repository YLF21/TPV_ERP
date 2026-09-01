-- V235 introduced stable numeric codes together with a transitional manual
-- order. Codes are now the sole catalog order and the transitional order is
-- removed without changing UUIDs or commercial aliases.

-- Fail fast before any DDL or data update. Flyway is transactional, but this
-- makes the no-mutation guarantee explicit for unsafe historical data.
DO $$
DECLARE
    invalid_count BIGINT;
BEGIN
    SELECT count(*) INTO invalid_count
      FROM subfamilia sf
      JOIN familia f ON f.id = sf.familia_id
     WHERE f.predeterminada;
    IF invalid_count > 0 THEN
        RAISE EXCEPTION 'La familia GENERAL tiene % subfamilias; resuelvelas antes de V236', invalid_count;
    END IF;
    SELECT count(*) INTO invalid_count
      FROM producto p
      JOIN familia f ON f.id = p.familia_id
     WHERE p.tienda_id <> f.tienda_id;
    IF invalid_count > 0 THEN
        RAISE EXCEPTION 'Hay % productos con familia de otra tienda; V236 no puede corregirlos', invalid_count;
    END IF;
    SELECT count(*) INTO invalid_count
      FROM producto p
      JOIN subfamilia sf ON sf.id = p.subfamilia_id
      JOIN familia f ON f.id = sf.familia_id
     WHERE p.tienda_id <> f.tienda_id;
    IF invalid_count > 0 THEN
        RAISE EXCEPTION 'Hay % productos con subfamilia de otra tienda; V236 no puede corregirlos', invalid_count;
    END IF;
END;
$$;

DROP INDEX IF EXISTS ix_familia_tienda_orden;
DROP INDEX IF EXISTS ix_subfamilia_familia_orden;

ALTER TABLE familia DROP CONSTRAINT IF EXISTS ck_familia_general_code;
ALTER TABLE familia DROP CONSTRAINT IF EXISTS ck_familia_orden_no_negativo;
ALTER TABLE subfamilia DROP CONSTRAINT IF EXISTS ck_subfamilia_orden_no_negativo;

DROP TRIGGER IF EXISTS tr_familia_reserve_code_before_delete ON familia;
DROP TRIGGER IF EXISTS tr_familia_guard_code_update ON familia;
DROP TRIGGER IF EXISTS tr_familia_validate_code_insert ON familia;
DROP TRIGGER IF EXISTS tr_subfamilia_reserve_code_before_delete ON subfamilia;
DROP TRIGGER IF EXISTS tr_subfamilia_guard_code_update ON subfamilia;
DROP TRIGGER IF EXISTS tr_subfamilia_validate_code ON subfamilia;

-- Replace V235 trigger functions before dropping orden. The functions remain
-- responsible for code allocation/immutability and deleted-code reservation.
-- Trigger queries use TG_TABLE_SCHEMA explicitly so a schema-qualified INSERT
-- remains correct even when the caller's search_path points somewhere else.
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

CREATE OR REPLACE FUNCTION tpv_guard_family_code_update()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tienda_id <> OLD.tienda_id THEN
        RAISE EXCEPTION 'La tienda de una familia es inmutable';
    END IF;
    IF NEW.family_id <> OLD.family_id THEN
        RAISE EXCEPTION 'El alias legado de una familia es inmutable';
    END IF;
    IF OLD.predeterminada AND (NOT NEW.predeterminada OR NEW.nombre <> 'GENERAL') THEN
        RAISE EXCEPTION 'La familia GENERAL no se puede renombrar';
    END IF;
    IF NOT OLD.predeterminada AND NEW.predeterminada THEN
        RAISE EXCEPTION 'La familia GENERAL no se puede reasignar';
    END IF;
    IF NEW.family_code <> OLD.family_code THEN
        RAISE EXCEPTION 'family_code es inmutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION tpv_validate_family_code_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    candidate INTEGER;
    selected_code VARCHAR(3);
    code_in_use BOOLEAN;
    code_reserved BOOLEAN;
BEGIN
    IF NEW.family_code IS NULL THEN
        IF NEW.predeterminada THEN
            NEW.family_code := '000';
        ELSE
            FOR candidate IN 1..999 LOOP
                selected_code := lpad(candidate::text, 3, '0');
                EXECUTE format(
                    'SELECT EXISTS (SELECT 1 FROM %I.familia '
                    'WHERE tienda_id = $1 AND family_code = $2)',
                    TG_TABLE_SCHEMA)
                INTO code_in_use
                USING NEW.tienda_id, selected_code;
                EXECUTE format(
                    'SELECT EXISTS (SELECT 1 FROM %I.familia_codigo_reservado '
                    'WHERE tienda_id = $1 AND family_code = $2)',
                    TG_TABLE_SCHEMA)
                INTO code_reserved
                USING NEW.tienda_id, selected_code;
                IF NOT code_in_use AND NOT code_reserved THEN
                    NEW.family_code := selected_code;
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
    EXECUTE format(
        'SELECT EXISTS (SELECT 1 FROM %I.familia_codigo_reservado '
        'WHERE tienda_id = $1 AND family_code = $2)',
        TG_TABLE_SCHEMA)
    INTO code_reserved
    USING NEW.tienda_id, NEW.family_code;
    IF code_reserved THEN
        RAISE EXCEPTION 'family_code fue reservado tras un borrado';
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
    parent_default BOOLEAN;
    code_in_use BOOLEAN;
    code_reserved BOOLEAN;
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
        FOR candidate IN 1..999 LOOP
            selected_suffix := lpad(candidate::text, 3, '0');
            EXECUTE format(
                'SELECT EXISTS (SELECT 1 FROM %I.subfamilia '
                'WHERE familia_id = $1 AND subfamily_suffix = $2)',
                TG_TABLE_SCHEMA)
            INTO code_in_use
            USING NEW.familia_id, selected_suffix;
            EXECUTE format(
                'SELECT EXISTS (SELECT 1 FROM %I.subfamilia_codigo_reservado '
                'WHERE familia_id = $1 AND subfamily_suffix = $2)',
                TG_TABLE_SCHEMA)
            INTO code_reserved
            USING NEW.familia_id, selected_suffix;
            IF NOT code_in_use AND NOT code_reserved THEN
                NEW.subfamily_suffix := selected_suffix;
                NEW.subfamily_code := parent_code || selected_suffix;
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
    EXECUTE format(
        'SELECT EXISTS (SELECT 1 FROM %I.subfamilia_codigo_reservado '
        'WHERE familia_id = $1 AND subfamily_suffix = $2)',
        TG_TABLE_SCHEMA)
    INTO code_reserved
    USING NEW.familia_id, NEW.subfamily_suffix;
    IF code_reserved THEN
        RAISE EXCEPTION 'subfamilySuffix fue reservado tras un borrado';
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE familia DROP COLUMN IF EXISTS orden;
ALTER TABLE subfamilia DROP COLUMN IF EXISTS orden;

CREATE TRIGGER tr_familia_reserve_code_before_delete
BEFORE DELETE ON familia FOR EACH ROW EXECUTE FUNCTION tpv_reserve_family_code_before_delete();
CREATE TRIGGER tr_familia_guard_code_update
BEFORE UPDATE OF tienda_id, family_id, family_code, nombre, predeterminada ON familia
FOR EACH ROW EXECUTE FUNCTION tpv_guard_family_code_update();
CREATE TRIGGER tr_familia_validate_code_insert
BEFORE INSERT ON familia FOR EACH ROW EXECUTE FUNCTION tpv_validate_family_code_insert();
CREATE TRIGGER tr_subfamilia_reserve_code_before_delete
BEFORE DELETE ON subfamilia FOR EACH ROW EXECUTE FUNCTION tpv_reserve_subfamily_code_before_delete();
CREATE TRIGGER tr_subfamilia_guard_code_update
BEFORE UPDATE OF subfamily_id, subfamily_suffix, subfamily_code ON subfamilia
FOR EACH ROW EXECUTE FUNCTION tpv_guard_subfamily_code_update();
CREATE TRIGGER tr_subfamilia_validate_code
BEFORE INSERT OR UPDATE OF familia_id, subfamily_suffix, subfamily_code ON subfamilia
FOR EACH ROW EXECUTE FUNCTION tpv_validate_subfamily_code();

-- Correct historical incoherence deterministically from the child relation;
-- this is the approved source of truth before installing the composite FK.
UPDATE producto p
SET familia_id = sf.familia_id
FROM subfamilia sf
WHERE p.subfamilia_id = sf.id
  AND p.familia_id <> sf.familia_id;

ALTER TABLE subfamilia
    ADD CONSTRAINT uq_subfamilia_familia_id UNIQUE (familia_id, id);

ALTER TABLE familia
    ADD CONSTRAINT uq_familia_tienda_id UNIQUE (tienda_id, id);

ALTER TABLE producto
    ADD CONSTRAINT fk_producto_tienda_familia
    FOREIGN KEY (tienda_id, familia_id)
    REFERENCES familia (tienda_id, id);

ALTER TABLE producto
    ADD CONSTRAINT fk_producto_familia_subfamilia
    FOREIGN KEY (familia_id, subfamilia_id)
    REFERENCES subfamilia (familia_id, id);

COMMENT ON CONSTRAINT fk_producto_familia_subfamilia ON producto
    IS 'La familia del producto debe ser el padre de su subfamilia';
