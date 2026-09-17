-- Preserve fractional quantities already accepted by APP VENTA, including refunds.
alter table venta_linea_eliminada
    alter column cantidad type numeric(19,3) using cantidad::numeric(19,3);

-- Before deferred delivery, eliminado_en was always the server reception time.
-- Keep that evidence for existing audit rows without changing their occurrence time.
alter table venta_operacion_eliminacion add column recibido_en timestamptz;
update venta_operacion_eliminacion set recibido_en = eliminado_en;
alter table venta_operacion_eliminacion
    alter column recibido_en set not null,
    alter column recibido_en set default current_timestamp;

alter table venta_linea_eliminada add column recibido_en timestamptz;
update venta_linea_eliminada set recibido_en = eliminado_en;
alter table venta_linea_eliminada
    alter column recibido_en set not null,
    alter column recibido_en set default current_timestamp;
