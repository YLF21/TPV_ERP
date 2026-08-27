ALTER TABLE saas_installation
    ADD COLUMN active boolean NOT NULL DEFAULT true,
    ADD COLUMN revoked_at timestamp with time zone,
    ADD COLUMN revoked_by varchar(120),
    ADD COLUMN revocation_reason varchar(500),
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY store_id
               ORDER BY linked_at DESC, id DESC
           ) AS position
      FROM saas_installation
)
UPDATE saas_installation installation
   SET active = false,
       revoked_at = CURRENT_TIMESTAMP,
       revoked_by = 'migration-v32',
       revocation_reason = 'Instalacion anterior sustituida al activar el modelo de revocacion'
  FROM ranked
 WHERE installation.id = ranked.id
   AND ranked.position > 1;

ALTER TABLE saas_installation
    ADD CONSTRAINT ck_saas_installation_revocation_state
    CHECK (
        (active AND revoked_at IS NULL AND revoked_by IS NULL AND revocation_reason IS NULL)
        OR
        (NOT active AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND revocation_reason IS NOT NULL)
    );

CREATE UNIQUE INDEX ux_saas_installation_store_active
    ON saas_installation(store_id)
    WHERE active;

INSERT INTO saas_admin_permission(code)
VALUES ('REVOKE_INSTALLATION');

INSERT INTO saas_admin_role_permission(role_id, permission_code)
SELECT id, 'REVOKE_INSTALLATION'
  FROM saas_admin_role
 WHERE name = 'ADMIN';
