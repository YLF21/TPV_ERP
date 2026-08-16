create table vale_familia (
    id uuid primary key,
    empresa_id uuid not null references empresa(id) on delete restrict,
    tienda_origen_id uuid not null references tienda(id) on delete restrict,
    consecutivo integer not null,
    identificador varchar(10) not null,
    creado_en timestamptz not null,
    constraint vale_familia_consecutivo_ck
        check (consecutivo between 1 and 999999),
    constraint vale_familia_identificador_ck
        check (identificador ~ '^[0-9]{3}-[0-9]{6}$'),
    constraint vale_familia_tienda_consecutivo_uk
        unique (tienda_origen_id, consecutivo),
    constraint vale_familia_empresa_identificador_uk
        unique (empresa_id, identificador)
);

create table vale_familia_contador (
    tienda_id uuid primary key references tienda(id) on delete restrict,
    ultimo_consecutivo integer not null,
    constraint vale_familia_contador_rango_ck
        check (ultimo_consecutivo between 0 and 999999)
);

alter table vale
    add column familia_id uuid;

create temporary table tmp_vale_familia_migracion on commit drop as
with cadenas as (
    select
        t.empresa_id,
        coalesce(nullif(v.tickets_origen ->> 0, ''), v.id::text) as clave_cadena,
        (array_agg(v.tienda_id order by v.creado_en, v.id))[1] as tienda_origen_id,
        min(v.creado_en) as creado_en
    from vale v
    join tienda t on t.id = v.tienda_id
    group by t.empresa_id,
             coalesce(nullif(v.tickets_origen ->> 0, ''), v.id::text)
), numeradas as (
    select
        gen_random_uuid() as familia_id,
        c.*,
        row_number() over (
            partition by c.tienda_origen_id
            order by c.creado_en, c.clave_cadena
        )::integer as consecutivo
    from cadenas c
)
select
    n.familia_id,
    n.empresa_id,
    n.clave_cadena,
    n.tienda_origen_id,
    n.consecutivo,
    ti.codigo_tienda || '-' || lpad(n.consecutivo::text, 6, '0') as identificador,
    n.creado_en
from numeradas n
join tienda ti on ti.id = n.tienda_origen_id;

do $$
begin
    if exists (
        select 1
        from tmp_vale_familia_migracion
        where consecutivo > 999999
    ) then
        raise exception 'No se pueden migrar mas de 999999 familias de vales por tienda';
    end if;
end $$;

insert into vale_familia (
    id, empresa_id, tienda_origen_id, consecutivo, identificador, creado_en
)
select
    familia_id, empresa_id, tienda_origen_id, consecutivo, identificador, creado_en
from tmp_vale_familia_migracion;

update vale v
set familia_id = m.familia_id
from tienda t,
     tmp_vale_familia_migracion m
where t.id = v.tienda_id
  and m.empresa_id = t.empresa_id
  and m.clave_cadena = coalesce(
      nullif(v.tickets_origen ->> 0, ''), v.id::text
  );

alter table vale
    alter column familia_id set not null,
    add constraint vale_familia_fk
        foreign key (familia_id) references vale_familia(id) on delete restrict;

insert into vale_familia_contador (tienda_id, ultimo_consecutivo)
select t.id, coalesce(max(f.consecutivo), 0)
from tienda t
left join vale_familia f on f.tienda_origen_id = t.id
group by t.id;

create index vale_familia_empresa_idx
    on vale_familia(empresa_id, identificador);

create index vale_familia_tienda_origen_idx
    on vale_familia(tienda_origen_id, creado_en desc);

create index vale_familia_vales_idx
    on vale(familia_id, creado_en);

create unique index vale_codigo_global_uk
    on vale(lower(codigo));
