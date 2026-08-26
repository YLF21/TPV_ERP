ALTER TABLE saas_installation
    ADD COLUMN link_recovery_token_hash varchar(64);

ALTER TABLE saas_installation
    ADD COLUMN current_pairing_code_id uuid REFERENCES saas_pairing_code(id);

ALTER TABLE saas_pairing_code
    ADD COLUMN link_recovery_token_hash varchar(64),
    ADD COLUMN previous_installation_token_hash varchar(64);

ALTER TABLE saas_installation
    ADD CONSTRAINT ck_saas_installation_link_recovery_token_hash
    CHECK (
        link_recovery_token_hash IS NULL
        OR length(link_recovery_token_hash) = 64
    );

ALTER TABLE saas_pairing_code
    ADD CONSTRAINT ck_saas_pairing_code_link_recovery_token_hash
    CHECK (
        link_recovery_token_hash IS NULL
        OR length(link_recovery_token_hash) = 64
    ),
    ADD CONSTRAINT ck_saas_pairing_code_previous_installation_token_hash
    CHECK (
        previous_installation_token_hash IS NULL
        OR length(previous_installation_token_hash) = 64
    );

COMMENT ON COLUMN saas_installation.link_recovery_token_hash IS
    'SHA-256 inmutable del secreto local usado para recuperar la primera respuesta de enlace';

COMMENT ON COLUMN saas_installation.current_pairing_code_id IS
    'Ultimo codigo de enlace que puede recuperar o rotar la credencial de esta instalacion';

COMMENT ON COLUMN saas_pairing_code.link_recovery_token_hash IS
    'SHA-256 congelado del secreto de recuperacion de este intento de enlace';

COMMENT ON COLUMN saas_pairing_code.previous_installation_token_hash IS
    'SHA-256 congelado del token previo que autentico este intento de enlace';

CREATE OR REPLACE FUNCTION prevent_saas_link_recovery_token_hash_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.link_recovery_token_hash IS DISTINCT FROM NEW.link_recovery_token_hash THEN
        RAISE EXCEPTION 'link_recovery_token_hash es inmutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_saas_installation_frozen_link_recovery_token_hash
BEFORE UPDATE OF link_recovery_token_hash ON saas_installation
FOR EACH ROW
EXECUTE FUNCTION prevent_saas_link_recovery_token_hash_update();

CREATE OR REPLACE FUNCTION prevent_saas_pairing_recovery_hash_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.link_recovery_token_hash IS NOT NULL
            AND OLD.link_recovery_token_hash IS DISTINCT FROM NEW.link_recovery_token_hash THEN
        RAISE EXCEPTION 'saas_pairing_code.link_recovery_token_hash es inmutable';
    END IF;
    IF OLD.previous_installation_token_hash IS NOT NULL
            AND OLD.previous_installation_token_hash
                IS DISTINCT FROM NEW.previous_installation_token_hash THEN
        RAISE EXCEPTION 'saas_pairing_code.previous_installation_token_hash es inmutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_saas_pairing_frozen_recovery_hashes
BEFORE UPDATE OF link_recovery_token_hash, previous_installation_token_hash
ON saas_pairing_code
FOR EACH ROW
EXECUTE FUNCTION prevent_saas_pairing_recovery_hash_update();
