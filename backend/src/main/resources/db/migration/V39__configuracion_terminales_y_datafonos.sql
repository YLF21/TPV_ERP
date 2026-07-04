insert into permiso (id, codigo, translation_key, grupo)
select gen_random_uuid(), 'CONFIGURACION_TERMINAL', 'terminal.permissions.configure', 'TERMINAL'
where not exists (
    select 1 from permiso where codigo = 'CONFIGURACION_TERMINAL'
);

create table configuracion_pago_tienda (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    card_manual_enabled boolean not null default true,
    card_manual_reference_required boolean not null default false,
    integrated_card_enabled boolean not null default true,
    manual_fallback_enabled boolean not null default true,
    allowed_payment_terminal_providers text not null default 'REDSYS_TPV_PC,PAYTEF,PAYCOMET,GLOBAL_PAYMENTS',
    version bigint not null default 0,
    unique (tienda_id)
);

create table configuracion_pago_terminal (
    id uuid primary key,
    terminal_id uuid not null references terminal(id),
    card_mode varchar(16) not null,
    provider varchar(32) not null,
    display_name varchar(128),
    enabled boolean not null default false,
    test_mode boolean not null default false,
    last_connection_test_at timestamptz,
    last_connection_status varchar(16),
    provider_parameters jsonb not null default '{}'::jsonb,
    secret_reference varchar(255),
    version bigint not null default 0,
    unique (terminal_id),
    check (card_mode in ('MANUAL', 'INTEGRATED')),
    check (provider in ('NONE', 'REDSYS_TPV_PC', 'PAYTEF', 'PAYCOMET', 'GLOBAL_PAYMENTS')),
    check (last_connection_status is null or last_connection_status in ('OK', 'ERROR')),
    check ((card_mode = 'MANUAL' and provider = 'NONE')
        or (card_mode = 'INTEGRATED' and provider <> 'NONE'))
);

alter table documento_pago
    add column terminal_pago_modo varchar(16),
    add column terminal_pago_provider varchar(32),
    add column terminal_pago_estado varchar(16),
    add column autorizacion_tarjeta varchar(64),
    add column terminal_cobro_id uuid references terminal(id);

alter table documento_pago
    add constraint ck_documento_pago_terminal_modo
        check (terminal_pago_modo is null or terminal_pago_modo in ('MANUAL', 'INTEGRATED')),
    add constraint ck_documento_pago_terminal_provider
        check (terminal_pago_provider is null or terminal_pago_provider in (
            'NONE', 'REDSYS_TPV_PC', 'PAYTEF', 'PAYCOMET', 'GLOBAL_PAYMENTS')),
    add constraint ck_documento_pago_terminal_estado
        check (terminal_pago_estado is null or terminal_pago_estado in (
            'PENDING', 'SENT', 'APPROVED', 'DECLINED', 'CANCELLED', 'TIMEOUT', 'ERROR')),
    add constraint ck_documento_pago_integrado_aprobado
        check (terminal_pago_modo is null
            or terminal_pago_modo <> 'INTEGRATED'
            or terminal_pago_estado = 'APPROVED'),
    add constraint ck_documento_pago_manual_provider
        check (terminal_pago_modo is null
            or terminal_pago_modo <> 'MANUAL'
            or terminal_pago_provider is null
            or terminal_pago_provider = 'NONE'),
    add constraint ck_documento_pago_integrated_provider
        check (terminal_pago_modo is null
            or terminal_pago_modo <> 'INTEGRATED'
            or (terminal_pago_provider is not null and terminal_pago_provider <> 'NONE')),
    add constraint ck_documento_pago_integrated_terminal
        check (terminal_pago_modo is null
            or terminal_pago_modo <> 'INTEGRATED'
            or terminal_cobro_id is not null);
