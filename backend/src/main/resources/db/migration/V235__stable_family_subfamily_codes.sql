-- Stable numeric catalog codes. The existing family_id/subfamily_id columns are
-- deliberately retained as legacy business aliases for old clients.
ALTER TABLE familia
    ADD COLUMN family_code VARCHAR(3),
    ADD COLUMN orden INTEGER NOT NULL DEFAULT 0;

ALTER TABLE subfamilia
    ADD COLUMN subfamily_suffix VARCHAR(3),
    ADD COLUMN subfamily_code VARCHAR(6),
    ADD COLUMN orden INTEGER NOT NULL DEFAULT 0;

CREATE TABLE familia_codigo_reservado (
    tienda_id UUID NOT NULL REFERENCES tienda(id),
    family_code VARCHAR(3) NOT NULL,
    reservado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tienda_id, family_code),
    CONSTRAINT ck_familia_codigo_reservado_formato CHECK (family_code ~ '^[0-9]{3}$')
);

CREATE TABLE subfamilia_codigo_reservado (
    familia_id UUID NOT NULL,
    subfamily_suffix VARCHAR(3) NOT NULL,
    reservado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (familia_id, subfamily_suffix),
    CONSTRAINT ck_subfamilia_codigo_reservado_formato CHECK (subfamily_suffix ~ '^[0-9]{3}$')
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM familia
        WHERE NOT predeterminada
        GROUP BY tienda_id
        HAVING count(*) > 999
    ) THEN
        RAISE EXCEPTION 'No se pueden asignar mas de 999 familias normales por tienda';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM subfamilia
        GROUP BY familia_id
        HAVING count(*) > 999
    ) THEN
        RAISE EXCEPTION 'No se pueden asignar mas de 999 subfamilias por familia';
    END IF;
END $$;

UPDATE familia
SET nombre = 'GENERAL',
    family_code = '000',
    orden = 0
WHERE predeterminada;

-- Preserve pre-existing numeric aliases whenever they are valid and not 000.
-- If legacy data contains a collision, only the deterministic lowest UUID keeps
-- the code; the remaining row is allocated below.
WITH candidates AS (
    SELECT id, tienda_id, upper(trim(family_id)) AS family_code,
           row_number() OVER (
               PARTITION BY tienda_id, upper(trim(family_id)) ORDER BY id
           ) AS duplicate_position
    FROM familia
    WHERE NOT predeterminada
      AND trim(family_id) ~ '^[0-9]{3}$'
      AND trim(family_id) <> '000'
)
UPDATE familia family
SET family_code = candidates.family_code
FROM candidates
WHERE family.id = candidates.id
  AND candidates.duplicate_position = 1;

-- Assign the smallest free code to the remaining families, deterministically.
WITH stores AS (
    SELECT DISTINCT tienda_id FROM familia
), candidates AS (
    SELECT stores.tienda_id, lpad(code::text, 3, '0') AS family_code
    FROM stores
    CROSS JOIN generate_series(1, 999) AS code
), used AS (
    SELECT tienda_id, family_code FROM familia WHERE family_code IS NOT NULL
    UNION
    SELECT tienda_id, family_code FROM familia_codigo_reservado
), free_codes AS (
    SELECT candidates.tienda_id, candidates.family_code,
           row_number() OVER (
               PARTITION BY candidates.tienda_id ORDER BY candidates.family_code
           ) AS position
    FROM candidates
    LEFT JOIN used
      ON used.tienda_id = candidates.tienda_id
     AND used.family_code = candidates.family_code
    WHERE used.family_code IS NULL
), remaining AS (
    SELECT id, tienda_id,
           row_number() OVER (
               PARTITION BY tienda_id ORDER BY lower(nombre), id
           ) AS position
    FROM familia
    WHERE family_code IS NULL
)
UPDATE familia family
SET family_code = free_codes.family_code,
    orden = free_codes.position
FROM remaining
JOIN free_codes
  ON free_codes.tienda_id = remaining.tienda_id
 AND free_codes.position = remaining.position
WHERE family.id = remaining.id;

UPDATE familia
SET orden = 0
WHERE predeterminada;

-- Preserve a three-digit legacy suffix, or a six-digit legacy composite when
-- its prefix still belongs to the family. Duplicate mapped suffixes keep the
-- deterministic lowest UUID and are allocated a new suffix below.
WITH candidates AS (
    SELECT subfamily.id, subfamily.familia_id, family.family_code,
           CASE
               WHEN trim(subfamily.subfamily_id) ~ '^[0-9]{3}$'
                    AND trim(subfamily.subfamily_id) <> '000'
                   THEN trim(subfamily.subfamily_id)
               WHEN trim(subfamily.subfamily_id) ~ '^[0-9]{6}$'
                    AND left(trim(subfamily.subfamily_id), 3) = family.family_code
                    AND right(trim(subfamily.subfamily_id), 3) <> '000'
                   THEN right(trim(subfamily.subfamily_id), 3)
           END AS suffix
    FROM subfamilia subfamily
    JOIN familia family ON family.id = subfamily.familia_id
), ranked AS (
    SELECT candidates.*,
           row_number() OVER (
               PARTITION BY familia_id, suffix ORDER BY id
           ) AS duplicate_position
    FROM candidates
    WHERE suffix IS NOT NULL
)
UPDATE subfamilia subfamily
SET subfamily_suffix = ranked.suffix,
    subfamily_code = ranked.family_code || ranked.suffix
FROM ranked
WHERE subfamily.id = ranked.id
  AND ranked.duplicate_position = 1;

WITH families AS (
    SELECT DISTINCT familia_id FROM subfamilia
), candidates AS (
    SELECT families.familia_id, lpad(code::text, 3, '0') AS suffix
    FROM families
    CROSS JOIN generate_series(1, 999) AS code
), used AS (
    SELECT familia_id, subfamily_suffix FROM subfamilia WHERE subfamily_suffix IS NOT NULL
    UNION
    SELECT familia_id, subfamily_suffix FROM subfamilia_codigo_reservado
), free_codes AS (
    SELECT candidates.familia_id, candidates.suffix,
           row_number() OVER (
               PARTITION BY candidates.familia_id ORDER BY candidates.suffix
           ) AS position
    FROM candidates
    LEFT JOIN used
      ON used.familia_id = candidates.familia_id
     AND used.subfamily_suffix = candidates.suffix
    WHERE used.subfamily_suffix IS NULL
), remaining AS (
    SELECT id, familia_id,
           row_number() OVER (
               PARTITION BY familia_id ORDER BY lower(nombre), id
           ) AS position
    FROM subfamilia
    WHERE subfamily_suffix IS NULL
)
UPDATE subfamilia subfamily
SET subfamily_suffix = free_codes.suffix,
    subfamily_code = family.family_code || free_codes.suffix,
    orden = free_codes.position
FROM remaining
JOIN free_codes
  ON free_codes.familia_id = remaining.familia_id
 AND free_codes.position = remaining.position
JOIN familia family ON family.id = remaining.familia_id
WHERE subfamily.id = remaining.id;

WITH ranked AS (
    SELECT id, familia_id,
           row_number() OVER (
               PARTITION BY familia_id ORDER BY lower(nombre), id
           ) AS position
    FROM subfamilia
)
UPDATE subfamilia subfamily
SET orden = ranked.position
FROM ranked
WHERE subfamily.id = ranked.id;

-- Make persisted order deterministic after both preserved and newly assigned
-- codes have been materialized.
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY tienda_id
               ORDER BY predeterminada DESC, lower(nombre), id
           ) - 1 AS position
    FROM familia
)
UPDATE familia family
SET orden = ranked.position
FROM ranked
WHERE family.id = ranked.id;

ALTER TABLE familia
    ALTER COLUMN family_code SET NOT NULL,
    ADD CONSTRAINT ck_familia_family_code_formato CHECK (family_code ~ '^[0-9]{3}$'),
    ADD CONSTRAINT ck_familia_general_code CHECK (
        (predeterminada AND family_code = '000' AND nombre = 'GENERAL' AND orden = 0)
        OR (NOT predeterminada AND family_code <> '000' AND orden > 0)
    ),
    ADD CONSTRAINT ck_familia_orden_no_negativo CHECK (orden >= 0);

ALTER TABLE subfamilia
    ALTER COLUMN subfamily_suffix SET NOT NULL,
    ALTER COLUMN subfamily_code SET NOT NULL,
    ADD CONSTRAINT ck_subfamilia_suffix_formato CHECK (subfamily_suffix ~ '^[0-9]{3}$'),
    ADD CONSTRAINT ck_subfamilia_code_formato CHECK (subfamily_code ~ '^[0-9]{6}$'),
    ADD CONSTRAINT ck_subfamilia_orden_no_negativo CHECK (orden >= 0);

CREATE UNIQUE INDEX ux_familia_family_code_tienda
    ON familia (tienda_id, family_code);

CREATE UNIQUE INDEX ux_subfamilia_suffix_familia
    ON subfamilia (familia_id, subfamily_suffix);

CREATE UNIQUE INDEX ux_subfamilia_code_familia
    ON subfamilia (familia_id, subfamily_code);

CREATE INDEX ix_familia_tienda_orden
    ON familia (tienda_id, orden, lower(nombre), id);

CREATE INDEX ix_subfamilia_familia_orden
    ON subfamilia (familia_id, orden, lower(nombre), id);

COMMENT ON COLUMN familia.family_id IS 'Legacy business alias retained for compatibility; family_code is the stable numeric code.';
COMMENT ON COLUMN subfamilia.subfamily_id IS 'Legacy business alias retained for compatibility; subfamily_code is the stable numeric code.';

CREATE OR REPLACE FUNCTION tpv_reserve_family_code_before_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.predeterminada THEN
        RAISE EXCEPTION 'La familia GENERAL no se puede eliminar';
    END IF;
    INSERT INTO familia_codigo_reservado (tienda_id, family_code)
    VALUES (OLD.tienda_id, OLD.family_code)
    ON CONFLICT (tienda_id, family_code) DO NOTHING;
    RETURN OLD;
END;
$$;

CREATE OR REPLACE FUNCTION tpv_guard_family_code_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
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
    IF OLD.predeterminada AND NEW.orden <> OLD.orden THEN
        RAISE EXCEPTION 'La familia GENERAL no se puede reordenar';
    END IF;
    IF NOT NEW.predeterminada AND NEW.orden <= 0 THEN
        RAISE EXCEPTION 'El orden de una familia normal debe ser positivo';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION tpv_validate_family_code_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    candidate INTEGER;
    selected_code VARCHAR(3);
BEGIN
    IF NEW.family_code IS NULL THEN
        IF NEW.predeterminada THEN
            NEW.family_code := '000';
            NEW.orden := 0;
        ELSE
            FOR candidate IN 1..999 LOOP
                selected_code := lpad(candidate::text, 3, '0');
                IF NOT EXISTS (
                    SELECT 1 FROM familia
                    WHERE tienda_id = NEW.tienda_id AND family_code = selected_code
                ) AND NOT EXISTS (
                    SELECT 1 FROM familia_codigo_reservado
                    WHERE tienda_id = NEW.tienda_id AND family_code = selected_code
                ) THEN
                    NEW.family_code := selected_code;
                    NEW.orden := coalesce(NEW.orden, candidate);
                    EXIT;
                END IF;
            END LOOP;
            IF NEW.family_code IS NULL THEN
                RAISE EXCEPTION 'No quedan familyCode disponibles para la tienda';
            END IF;
        END IF;
    END IF;
    IF NEW.predeterminada THEN
        NEW.orden := 0;
    ELSIF coalesce(NEW.orden, 0) <= 0 THEN
        SELECT coalesce(max(orden), 0) + 1
        INTO NEW.orden
        FROM familia
        WHERE tienda_id = NEW.tienda_id;
    END IF;
    IF (NEW.predeterminada AND (NEW.family_code <> '000' OR NEW.nombre <> 'GENERAL' OR NEW.orden <> 0))
            OR (NOT NEW.predeterminada AND (NEW.family_code = '000' OR NEW.orden <= 0)) THEN
        RAISE EXCEPTION 'family_code no es valido para la familia';
    END IF;
    IF EXISTS (
        SELECT 1 FROM familia_codigo_reservado
        WHERE tienda_id = NEW.tienda_id AND family_code = NEW.family_code
    ) THEN
        RAISE EXCEPTION 'family_code fue reservado tras un borrado';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION tpv_reserve_subfamily_code_before_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO subfamilia_codigo_reservado (familia_id, subfamily_suffix)
    VALUES (OLD.familia_id, OLD.subfamily_suffix)
    ON CONFLICT (familia_id, subfamily_suffix) DO NOTHING;
    RETURN OLD;
END;
$$;

CREATE OR REPLACE FUNCTION tpv_guard_subfamily_code_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.subfamily_id <> OLD.subfamily_id THEN
        RAISE EXCEPTION 'El alias legado de una subfamilia es inmutable';
    END IF;
    IF NEW.subfamily_suffix <> OLD.subfamily_suffix
            OR NEW.subfamily_code <> OLD.subfamily_code THEN
        RAISE EXCEPTION 'El codigo de subfamilia es inmutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION tpv_validate_subfamily_code()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_code VARCHAR(3);
    candidate INTEGER;
    selected_suffix VARCHAR(3);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.familia_id <> OLD.familia_id THEN
        RAISE EXCEPTION 'La familia de una subfamilia es inmutable';
    END IF;
    SELECT family_code INTO parent_code FROM familia WHERE id = NEW.familia_id;
    IF NEW.subfamily_suffix IS NULL THEN
        FOR candidate IN 1..999 LOOP
            selected_suffix := lpad(candidate::text, 3, '0');
            IF NOT EXISTS (
                SELECT 1 FROM subfamilia
                WHERE familia_id = NEW.familia_id AND subfamily_suffix = selected_suffix
            ) AND NOT EXISTS (
                SELECT 1 FROM subfamilia_codigo_reservado
                WHERE familia_id = NEW.familia_id AND subfamily_suffix = selected_suffix
            ) THEN
                NEW.subfamily_suffix := selected_suffix;
                NEW.subfamily_code := parent_code || selected_suffix;
                NEW.orden := coalesce(NEW.orden, candidate);
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
    IF EXISTS (
        SELECT 1 FROM subfamilia_codigo_reservado
        WHERE familia_id = NEW.familia_id AND subfamily_suffix = NEW.subfamily_suffix
    ) THEN
        RAISE EXCEPTION 'subfamilySuffix fue reservado tras un borrado';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_familia_reserve_code_before_delete
BEFORE DELETE ON familia
FOR EACH ROW EXECUTE FUNCTION tpv_reserve_family_code_before_delete();

CREATE TRIGGER tr_familia_guard_code_update
BEFORE UPDATE OF tienda_id, family_id, family_code, orden, nombre, predeterminada ON familia
FOR EACH ROW EXECUTE FUNCTION tpv_guard_family_code_update();

CREATE TRIGGER tr_familia_validate_code_insert
BEFORE INSERT ON familia
FOR EACH ROW EXECUTE FUNCTION tpv_validate_family_code_insert();

CREATE TRIGGER tr_subfamilia_reserve_code_before_delete
BEFORE DELETE ON subfamilia
FOR EACH ROW EXECUTE FUNCTION tpv_reserve_subfamily_code_before_delete();

CREATE TRIGGER tr_subfamilia_guard_code_update
BEFORE UPDATE OF subfamily_id, subfamily_suffix, subfamily_code ON subfamilia
FOR EACH ROW EXECUTE FUNCTION tpv_guard_subfamily_code_update();

CREATE TRIGGER tr_subfamilia_validate_code
BEFORE INSERT OR UPDATE OF familia_id, subfamily_suffix, subfamily_code ON subfamilia
FOR EACH ROW EXECUTE FUNCTION tpv_validate_subfamily_code();
