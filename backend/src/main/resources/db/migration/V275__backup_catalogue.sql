ALTER TABLE configuracion_backup ADD COLUMN backup_sequence bigint NOT NULL DEFAULT 0;
ALTER TABLE ejecucion_backup ADD COLUMN sequence_number bigint;
-- Older executions did not record their trigger; do not invent a manual origin.
ALTER TABLE ejecucion_backup ADD COLUMN origin varchar(16) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE ejecucion_backup ALTER COLUMN origin SET DEFAULT 'MANUAL';
ALTER TABLE ejecucion_backup ADD COLUMN phase varchar(32) NOT NULL DEFAULT 'QUEUED';

WITH numbered AS (
    SELECT id, row_number() OVER (PARTITION BY configuracion_id ORDER BY iniciada_en, id) AS n
    FROM ejecucion_backup
)
UPDATE ejecucion_backup e SET sequence_number = numbered.n FROM numbered WHERE numbered.id = e.id;
UPDATE configuracion_backup c SET backup_sequence = COALESCE(
    (SELECT max(sequence_number) FROM ejecucion_backup WHERE configuracion_id = c.id), 0);
UPDATE ejecucion_backup SET phase = CASE WHEN result = 'EXITO' THEN 'COMPLETED'
    WHEN result = 'FALLO' THEN 'FAILED' ELSE 'QUEUED' END;
ALTER TABLE ejecucion_backup ALTER COLUMN sequence_number SET NOT NULL;
CREATE UNIQUE INDEX ux_backup_sequence ON ejecucion_backup(configuracion_id, sequence_number);

CREATE FUNCTION assign_backup_sequence() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    UPDATE configuracion_backup SET backup_sequence = backup_sequence + 1
    WHERE id = NEW.configuracion_id RETURNING backup_sequence INTO NEW.sequence_number;
    RETURN NEW;
END $$;
CREATE TRIGGER tr_assign_backup_sequence BEFORE INSERT ON ejecucion_backup
FOR EACH ROW EXECUTE FUNCTION assign_backup_sequence();
