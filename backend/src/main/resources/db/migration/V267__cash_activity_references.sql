-- One daily sequence per store for the visible cash activity. Cash receipts affect
-- the balance, but are deliberately excluded from this activity and its limit.
-- Keep concurrent writers outside the backfill-to-trigger cutover. Flyway holds
-- both locks until this migration commits, so no activity can escape allocation.
lock table sesion_caja, movimiento_caja in share row exclusive mode;

create table caja_actividad_contador (
    tienda_id uuid not null references tienda(id),
    fecha_local date not null,
    ultimo_numero integer not null check (ultimo_numero between 1 and 999),
    primary key (tienda_id, fecha_local)
);

create table caja_actividad_referencia (
    tipo_evento varchar(10) not null check (tipo_evento in ('OPENING', 'MOVEMENT', 'CLOSING')),
    evento_id uuid not null,
    tienda_id uuid not null references tienda(id),
    fecha_local date not null,
    numero integer check (numero between 1 and 999),
    referencia varchar(9),
    primary key (tipo_evento, evento_id),
    unique (tienda_id, fecha_local, numero),
    check ((numero is null and referencia is null)
        or (numero is not null and referencia is not null and referencia ~ '^[0-9]{9}$'))
);

-- Backfill exactly once, across all terminals of each store. Existing historical
-- days above the limit remain readable with their source ID and no invented or
-- duplicated short reference. They cannot accept new numbered activity.
with events as (
    select 'OPENING' as tipo_evento, id as evento_id, tienda_id, abierta_en as at, 0 as priority
    from sesion_caja
    union all
    select 'MOVEMENT', id, tienda_id, creado_en, 1
    from movimiento_caja where tipo <> 'COBRO_EFECTIVO'
    union all
    select 'CLOSING', id, tienda_id, cerrada_en, 2
    from sesion_caja where estado = 'CERRADA' and cerrada_en is not null
), dated as (
    select e.*, (e.at at time zone t.timezone)::date as fecha_local
    from events e join tienda t on t.id = e.tienda_id
), numbered as (
    select *, row_number() over (partition by tienda_id, fecha_local order by at, priority, evento_id) as n
    from dated
)
insert into caja_actividad_referencia(tipo_evento, evento_id, tienda_id, fecha_local, numero, referencia)
select tipo_evento, evento_id, tienda_id, fecha_local,
    case when n <= 999 then n::integer end,
    case when n <= 999 then to_char(fecha_local, 'YYMMDD') || lpad(n::text, 3, '0') end
from numbered;

insert into caja_actividad_contador(tienda_id, fecha_local, ultimo_numero)
select tienda_id, fecha_local, least(count(*), 999)::integer
from caja_actividad_referencia group by tienda_id, fecha_local;

create function asignar_referencia_actividad_caja(p_tipo text, p_evento uuid, p_tienda uuid, p_fecha timestamptz)
returns void language plpgsql as $$
declare
    v_fecha date;
    v_numero integer;
begin
    if exists (select 1 from caja_actividad_referencia where tipo_evento = p_tipo and evento_id = p_evento) then
        return;
    end if;
    select (p_fecha at time zone timezone)::date into strict v_fecha from tienda where id = p_tienda;
    insert into caja_actividad_contador as contador(tienda_id, fecha_local, ultimo_numero)
    values (p_tienda, v_fecha, 1)
    on conflict (tienda_id, fecha_local) do update
        set ultimo_numero = contador.ultimo_numero + 1
        where contador.ultimo_numero < 999
    returning ultimo_numero into v_numero;
    if v_numero is null then
        raise exception 'cash_activity_daily_limit'
            using errcode = '23514', constraint = 'cash_activity_daily_limit';
    end if;
    insert into caja_actividad_referencia(tipo_evento, evento_id, tienda_id, fecha_local, numero, referencia)
    values (p_tipo, p_evento, p_tienda, v_fecha, v_numero, to_char(v_fecha, 'YYMMDD') || lpad(v_numero::text, 3, '0'));
end;
$$;

create function registrar_referencia_sesion_caja() returns trigger language plpgsql as $$
begin
    if TG_OP = 'INSERT' then
        perform asignar_referencia_actividad_caja('OPENING', new.id, new.tienda_id, new.abierta_en);
    end if;
    if new.estado = 'CERRADA' and new.cerrada_en is not null then
        perform asignar_referencia_actividad_caja('CLOSING', new.id, new.tienda_id, new.cerrada_en);
    end if;
    return new;
end;
$$;

create trigger sesion_caja_referencia_actividad
after insert or update of estado, cerrada_en on sesion_caja
for each row execute function registrar_referencia_sesion_caja();

create function registrar_referencia_movimiento_caja() returns trigger language plpgsql as $$
begin
    perform asignar_referencia_actividad_caja('MOVEMENT', new.id, new.tienda_id, new.creado_en);
    return new;
end;
$$;

create trigger movimiento_caja_referencia_actividad
after insert on movimiento_caja
for each row when (new.tipo <> 'COBRO_EFECTIVO')
execute function registrar_referencia_movimiento_caja();
