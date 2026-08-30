-- The operational projection asks for the newest accepted AEAT attempt. Keep
-- only accepted outcomes in this index so the ORDER BY/LIMIT query can walk
-- recent attempts first, then join each candidate to registro_fiscal by id.
create index concurrently if not exists ix_intento_envio_fiscal_accepted_fecha_record
    on intento_envio_fiscal(intentado_en desc, registro_id)
    where estado in ('ACEPTADO', 'ACEPTADO_CON_ERRORES');
