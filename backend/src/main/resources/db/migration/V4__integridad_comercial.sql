drop index if exists ux_producto_identificador_tienda_valor;

create or replace function validar_identificador_producto_cruzado()
returns trigger
language plpgsql
as $$
begin
    perform pg_advisory_xact_lock(
        hashtextextended(new.tienda_id::text || ':' || new.valor, 0));
    if exists (
        select 1
        from producto_identificador existente
        where existente.tienda_id = new.tienda_id
          and existente.valor = new.valor
          and existente.producto_id <> new.producto_id
    ) then
        raise exception 'El identificador ya pertenece a otro producto'
            using errcode = '23505';
    end if;
    return new;
end;
$$;

create trigger tr_producto_identificador_cruzado
before insert or update of tienda_id, producto_id, valor
on producto_identificador
for each row execute function validar_identificador_producto_cruzado();

create unique index ux_movimiento_stock_compensacion
    on movimiento_stock(compensacion_de_id)
    where compensacion_de_id is not null;
