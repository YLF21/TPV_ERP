alter table recuento_stock add column fecha date;
update recuento_stock set fecha = (creado_en at time zone 'UTC')::date;
alter table recuento_stock alter column fecha set not null;
alter table recuento_stock add column revision_edicion bigint not null default 0;
alter table recuento_stock_linea alter column cantidad_contada drop not null;
