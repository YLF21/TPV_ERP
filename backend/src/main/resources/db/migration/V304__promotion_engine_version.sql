-- NULL identifies documents priced by the original, tax-homogeneous engine.
-- New promotion lines carry their engine version in the same business transaction.
ALTER TABLE documento_linea ADD COLUMN promocion_motor_version integer;
ALTER TABLE documento_linea ADD CONSTRAINT ck_documento_linea_promocion_motor_version
    CHECK (promocion_motor_version IS NULL
        OR (tipo_linea = 'PROMOTION' AND promocion_motor_version = 2));
