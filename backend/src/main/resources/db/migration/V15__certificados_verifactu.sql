create table certificado_verifactu (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    status varchar(16) not null,
    subject varchar(512) not null,
    issuer varchar(512) not null,
    serial_number varchar(128) not null,
    tax_id varchar(9) not null,
    valid_from timestamptz not null,
    valid_until timestamptz not null,
    fingerprint varchar(64) not null,
    public_chain bytea not null,
    secret_path varchar(512),
    imported_at timestamptz not null,
    imported_by uuid not null references usuario(id),
    replaced_at timestamptz,
    replaced_by uuid references usuario(id),
    deleted_at timestamptz,
    deleted_by uuid references usuario(id),
    last_warning_date date,
    version bigint not null default 0,
    check (status in ('ACTIVO', 'ANTERIOR', 'ELIMINADO')),
    check (valid_until > valid_from),
    check (fingerprint ~ '^[0-9A-F]{64}$'),
    check (tax_id ~ '^[0-9A-Z]{9}$'),
    check (
        (status in ('ACTIVO', 'ANTERIOR') and secret_path is not null)
        or (status = 'ELIMINADO' and secret_path is null)
    )
);

create unique index uq_certificado_verifactu_activo
    on certificado_verifactu(empresa_id) where status = 'ACTIVO';

create unique index uq_certificado_verifactu_anterior
    on certificado_verifactu(empresa_id) where status = 'ANTERIOR';

create index ix_certificado_verifactu_purga
    on certificado_verifactu(status, replaced_at);
