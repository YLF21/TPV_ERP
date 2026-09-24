create table configuracion_stock_almacen (
    almacen_id uuid primary key,
    tienda_id uuid not null,
    permitir_stock_negativo boolean not null,
    stock_minimo_predeterminado numeric(19,3) not null,
    alertas_habilitadas boolean not null,
    version bigint not null default 0,
    constraint fk_configuracion_stock_almacen_tienda
        foreign key (tienda_id, almacen_id) references almacen(tienda_id, id) on delete cascade,
    constraint ck_configuracion_stock_almacen_minimo check (stock_minimo_predeterminado >= 0)
);

insert into configuracion_stock_almacen (
    almacen_id, tienda_id, permitir_stock_negativo, stock_minimo_predeterminado, alertas_habilitadas
)
select warehouse.id, warehouse.tienda_id,
       coalesce(settings.permitir_stock_negativo, true),
       coalesce(settings.stock_minimo_predeterminado, 5.000),
       coalesce(settings.alertas_habilitadas, true)
from almacen warehouse
left join configuracion_stock settings on settings.tienda_id = warehouse.tienda_id;
