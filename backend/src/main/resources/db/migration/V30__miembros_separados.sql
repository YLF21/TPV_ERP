create table miembro (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    cliente_id uuid not null references cliente(id),
    member_id varchar(12) not null,
    member_code_store_id uuid references tienda(id),
    num_member varchar(255),
    member_since date not null,
    member_balance numeric(19,2) not null default 0,
    active boolean not null,
    version bigint not null default 0,
    constraint ck_miembro_member_id
        check (member_id ~ '^M-[0-9]{3}-[0-9]{6}$'),
    constraint ck_miembro_member_balance
        check (member_balance >= 0),
    constraint ux_miembro_cliente unique (cliente_id),
    constraint ux_miembro_empresa_member_id unique (empresa_id, member_id),
    constraint ux_miembro_empresa_num_member unique (empresa_id, num_member)
);

insert into miembro (
    id,
    empresa_id,
    cliente_id,
    member_id,
    member_code_store_id,
    num_member,
    member_since,
    member_balance,
    active,
    version
)
select gen_random_uuid(),
       empresa_id,
       id,
       member_id,
       member_code_store_id,
       num_member,
       member_since,
       member_balance,
       is_member,
       0
from cliente
where member_id is not null;

alter table cliente drop constraint if exists ck_cliente_member_fields;
alter table cliente drop constraint if exists ck_cliente_member_rate;
alter table cliente drop constraint if exists ck_cliente_member_id;
alter table cliente drop constraint if exists ux_cliente_empresa_member_id;
alter table cliente drop constraint if exists ux_cliente_empresa_num_member;
alter table cliente drop constraint if exists cliente_member_balance_check;

update cliente set tarifa = 'VENTA' where tarifa = 'MEMBER';

alter table cliente drop column is_member;
alter table cliente drop column member_id;
alter table cliente drop column member_code_store_id;
alter table cliente drop column num_member;
alter table cliente drop column member_since;
alter table cliente drop column member_balance;

create index ix_miembro_empresa_active on miembro(empresa_id, active);
