alter table contador_documento drop constraint if exists contador_documento_tipo_check;
alter table contador_documento add constraint contador_documento_tipo_check
    check (tipo in ('SAL', 'ENT', 'AE', 'FE', 'AV', 'AC', 'T', 'FV', 'FC', 'FRV', 'FRC', 'TRA', 'INV'));

alter table recuento_stock add column numero varchar(40);
update recuento_stock set numero = 'INV-L-' || replace(id::text, '-', '');
alter table recuento_stock alter column numero set not null;
alter table recuento_stock add constraint uq_recuento_stock_numero unique (numero);
