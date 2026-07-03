create table member_category (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    code varchar(32) not null,
    name varchar(64) not null,
    min_points bigint not null default 0,
    discount_percent numeric(5,2) not null default 0,
    discount_enabled boolean not null default false,
    manual_only boolean not null default false,
    active boolean not null default true,
    sort_order integer not null default 0,
    version bigint not null default 0,
    constraint ux_member_category_empresa_name unique (empresa_id, name),
    constraint ux_member_category_empresa_code unique (empresa_id, code),
    constraint ck_member_category_points check (min_points >= 0),
    constraint ck_member_category_discount check (discount_percent between 0 and 100)
);

insert into member_category (
    id, empresa_id, code, name, min_points, discount_percent,
    discount_enabled, manual_only, active, sort_order, version
)
select gen_random_uuid(), id, 'EMPLEADO', 'Empleado', 0, 15.00,
       true, true, true, 9000, 0
from empresa;

alter table miembro
    add column member_points bigint not null default 0,
    add column member_category_id uuid references member_category(id),
    add column auto_category_locked boolean not null default false,
    add column official_member_balance numeric(19,2) not null default 0,
    add column official_member_points bigint not null default 0,
    add column official_category_id uuid references member_category(id),
    add column official_synced_at timestamptz,
    add constraint ck_miembro_member_points check (member_points >= 0),
    add constraint ck_miembro_official_member_balance check (official_member_balance >= 0),
    add constraint ck_miembro_official_member_points check (official_member_points >= 0);

create table member_category_history (
    id uuid primary key,
    miembro_id uuid not null references miembro(id),
    previous_category_id uuid references member_category(id),
    new_category_id uuid references member_category(id),
    reason varchar(255),
    manual boolean not null,
    auto_category_locked boolean not null,
    usuario_id uuid references usuario(id),
    created_at timestamptz not null
);

create table member_settings (
    empresa_id uuid primary key references empresa(id),
    balance_accrual_percent numeric(5,2) not null default 0,
    balance_expiration_policy varchar(16) not null default 'NO_CADUCA',
    points_per_euro numeric(8,2) not null default 1,
    category_auto_enabled boolean not null default true,
    member_welcome_enabled boolean not null default false,
    member_card_code_format varchar(16) not null default 'QR',
    welcome_subject_template text,
    welcome_body_template text,
    version bigint not null default 0,
    constraint ck_member_settings_balance_percent check (balance_accrual_percent between 0 and 100),
    constraint ck_member_settings_points_per_euro check (points_per_euro >= 0),
    constraint ck_member_settings_expiration check (
        balance_expiration_policy in ('NO_CADUCA', 'UN_MES', 'TRES_MESES', 'SEIS_MESES', 'UN_ANO')),
    constraint ck_member_settings_card_format check (member_card_code_format in ('QR', 'BARCODE'))
);

insert into member_settings (empresa_id)
select id from empresa;

insert into metodo_pago (id, empresa_id, nombre, protegido, activo, requiere_referencia, abre_caja_registradora)
select gen_random_uuid(), id, 'SALDO_MIEMBRO', true, true, false, false
from empresa
where not exists (
    select 1
    from metodo_pago
    where metodo_pago.empresa_id = empresa.id
      and metodo_pago.nombre = 'SALDO_MIEMBRO'
);

create table member_movement (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    tienda_id uuid references tienda(id),
    miembro_id uuid not null references miembro(id),
    documento_id uuid references documento(id),
    type varchar(32) not null,
    balance_amount numeric(19,2) not null default 0,
    points_amount bigint not null default 0,
    previous_category_id uuid references member_category(id),
    new_category_id uuid references member_category(id),
    reason varchar(255),
    created_by_user_id uuid references usuario(id),
    created_at timestamptz not null,
    source_event_id uuid,
    version bigint not null default 0,
    constraint ux_member_movement_source_event unique (source_event_id),
    constraint ck_member_movement_type check (type in (
        'ALTA_MIEMBRO', 'DESACTIVACION_MIEMBRO', 'CAMBIO_CATEGORIA',
        'ACUMULACION_PUNTOS', 'ACUMULACION_SALDO', 'USO_SALDO',
        'CADUCIDAD_SALDO', 'AJUSTE_MANUAL_SALDO', 'AJUSTE_MANUAL_PUNTOS', 'AJUSTE_SAAS'))
);

create index ix_member_movement_miembro_created on member_movement(miembro_id, created_at desc);

create table member_balance_lot (
    id uuid primary key,
    miembro_id uuid not null references miembro(id),
    documento_id uuid references documento(id),
    source_movement_id uuid references member_movement(id),
    amount_original numeric(19,2) not null,
    amount_remaining numeric(19,2) not null,
    created_at timestamptz not null,
    expires_at timestamptz,
    expired_at timestamptz,
    version bigint not null default 0,
    constraint ck_member_balance_lot_amounts check (
        amount_original >= 0 and amount_remaining >= 0 and amount_remaining <= amount_original)
);

create table member_balance_lot_consumption (
    movement_id uuid not null references member_movement(id),
    lot_id uuid not null references member_balance_lot(id),
    amount numeric(19,2) not null,
    primary key (movement_id, lot_id),
    constraint ck_member_balance_lot_consumption_amount check (amount > 0)
);

create table member_card_delivery (
    id uuid primary key,
    miembro_id uuid not null references miembro(id),
    email varchar(320) not null,
    subject text not null,
    body text not null,
    card_code_format varchar(16) not null,
    card_code varchar(64) not null,
    status varchar(16) not null default 'PENDIENTE',
    created_at timestamptz not null,
    sent_at timestamptz,
    version bigint not null default 0,
    constraint ck_member_card_delivery_format check (card_code_format in ('QR', 'BARCODE')),
    constraint ck_member_card_delivery_status check (status in ('PENDIENTE', 'ENVIADO', 'ERROR'))
);
