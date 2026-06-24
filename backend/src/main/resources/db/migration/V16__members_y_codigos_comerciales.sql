alter table cliente rename column saldo_socio to member_balance;
alter table movimiento_saldo_socio rename to member_balance_movement;
alter table cliente rename constraint cliente_saldo_socio_check
    to cliente_member_balance_check;

do $$
declare
    constraint_row record;
begin
    for constraint_row in
        select conname
        from pg_constraint
        where conrelid = 'member_balance_movement'::regclass
          and conname ilike '%socio%'
    loop
        execute format(
            'alter table member_balance_movement rename constraint %I to %I',
            constraint_row.conname,
            replace(constraint_row.conname, 'socio', 'member'));
    end loop;
end;
$$;

alter table cliente
    add column code_client varchar(12),
    add column client_code_store_id uuid references tienda(id),
    add column is_member boolean not null default false,
    add column code_member varchar(12),
    add column member_code_store_id uuid references tienda(id),
    add column num_member varchar(255),
    add column member_since date;

alter table proveedor add column code_supplier varchar(8);
alter table comercial add column code_commercial varchar(9);

alter table cliente drop constraint if exists cliente_tarifa_check;
alter table producto_precio drop constraint if exists producto_precio_tarifa_check;

update cliente set is_member = (tarifa = 'SOCIO');
update cliente set tarifa = 'MEMBER' where tarifa = 'SOCIO';
update producto_precio set tarifa = 'MEMBER' where tarifa = 'SOCIO';

with first_store as (
    select distinct on (empresa_id) empresa_id, id, codigo_tienda
    from tienda
    order by empresa_id, codigo_tienda, id
), numbered as (
    select c.id,
           fs.id as store_id,
           fs.codigo_tienda,
           row_number() over (
               partition by c.empresa_id
               order by upper(trim(c.numero_documento)), c.id) as sequence_number
    from cliente c
    join first_store fs on fs.empresa_id = c.empresa_id
)
update cliente c
set code_client = 'C-' || numbered.codigo_tienda || '-'
        || lpad(numbered.sequence_number::text, 6, '0'),
    client_code_store_id = numbered.store_id
from numbered
where numbered.id = c.id;

with first_store as (
    select distinct on (empresa_id) empresa_id, id, codigo_tienda
    from tienda
    order by empresa_id, codigo_tienda, id
), numbered as (
    select c.id,
           fs.id as store_id,
           fs.codigo_tienda,
           row_number() over (
               partition by c.empresa_id
               order by upper(trim(c.numero_documento)), c.id) as sequence_number
    from cliente c
    join first_store fs on fs.empresa_id = c.empresa_id
    where c.is_member
)
update cliente c
set code_member = 'M-' || numbered.codigo_tienda || '-'
        || lpad(numbered.sequence_number::text, 6, '0'),
    member_code_store_id = numbered.store_id,
    member_since = current_date
from numbered
where numbered.id = c.id;

with numbered as (
    select p.id,
           row_number() over (
               partition by p.empresa_id
               order by upper(trim(p.numero_documento)), p.id) as sequence_number
    from proveedor p
)
update proveedor p
set code_supplier = 'S-' || lpad(numbered.sequence_number::text, 6, '0')
from numbered
where numbered.id = p.id;

with numbered as (
    select c.id,
           row_number() over (
               partition by c.empresa_id
               order by upper(trim(c.nombre)), c.id) as sequence_number
    from comercial c
)
update comercial c
set code_commercial = 'CO-' || lpad(numbered.sequence_number::text, 6, '0')
from numbered
where numbered.id = c.id;

alter table cliente
    alter column code_client set not null,
    alter column client_code_store_id set not null,
    add constraint ck_cliente_code_client
        check (code_client ~ '^C-[0-9]{3}-[0-9]{6}$'),
    add constraint ck_cliente_code_member
        check (code_member is null or code_member ~ '^M-[0-9]{3}-[0-9]{6}$'),
    add constraint ck_cliente_member_fields
        check ((code_member is null and member_code_store_id is null and member_since is null)
            or (code_member is not null and member_code_store_id is not null
                and member_since is not null)),
    add constraint ck_cliente_member_rate
        check ((is_member and tarifa = 'MEMBER')
            or (not is_member and tarifa = 'VENTA')),
    add constraint ck_cliente_tarifa check (tarifa in ('VENTA', 'MEMBER')),
    add constraint ux_cliente_empresa_code_client unique (empresa_id, code_client),
    add constraint ux_cliente_empresa_code_member unique (empresa_id, code_member),
    add constraint ux_cliente_empresa_num_member unique (empresa_id, num_member);

alter table proveedor
    alter column code_supplier set not null,
    add constraint ck_proveedor_code_supplier
        check (code_supplier ~ '^S-[0-9]{6}$'),
    add constraint ux_proveedor_empresa_code_supplier
        unique (empresa_id, code_supplier);

alter table comercial
    alter column code_commercial set not null,
    add constraint ck_comercial_code_commercial
        check (code_commercial ~ '^CO-[0-9]{6}$'),
    add constraint ux_comercial_empresa_code_commercial
        unique (empresa_id, code_commercial);

alter table producto_precio
    add constraint ck_producto_precio_tarifa
        check (tarifa in ('VENTA', 'MEMBER', 'MAYORISTA', 'OFERTA'));

create table party_code_counter (
    scope_id uuid not null,
    code_type varchar(16) not null,
    last_number bigint not null default 0,
    primary key (scope_id, code_type),
    check (code_type in ('CLIENT', 'MEMBER', 'SUPPLIER', 'COMMERCIAL')),
    check (last_number >= 0)
);

insert into party_code_counter (scope_id, code_type, last_number)
select client_code_store_id, 'CLIENT', count(*)
from cliente
group by client_code_store_id;

insert into party_code_counter (scope_id, code_type, last_number)
select member_code_store_id, 'MEMBER', count(*)
from cliente
where code_member is not null
group by member_code_store_id;

insert into party_code_counter (scope_id, code_type, last_number)
select empresa_id, 'SUPPLIER', count(*)
from proveedor
group by empresa_id;

insert into party_code_counter (scope_id, code_type, last_number)
select empresa_id, 'COMMERCIAL', count(*)
from comercial
group by empresa_id;
