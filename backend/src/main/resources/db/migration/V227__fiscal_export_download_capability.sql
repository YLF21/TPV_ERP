create table fiscal_export_download_token (
    token_hash varchar(64) primary key,
    job_id uuid not null references trabajo_exportacion_fiscal(id) on delete cascade,
    empresa_id uuid not null,
    tienda_id uuid not null,
    instalacion_id uuid not null,
    solicitado_por varchar(128) not null,
    expira_en timestamptz not null,
    consumido_en timestamptz,
    constraint fk_fiscal_export_download_token_company
        foreign key (empresa_id) references empresa(id) on delete cascade,
    constraint fk_fiscal_export_download_token_store
        foreign key (tienda_id) references tienda(id) on delete cascade,
    constraint fk_fiscal_export_download_token_installation
        foreign key (instalacion_id) references instalacion(id) on delete cascade,
    constraint ck_fiscal_export_download_token_hash check (token_hash ~* '^[0-9a-f]{64}$'),
    constraint ck_fiscal_export_download_token_scope check (
        empresa_id is not null and tienda_id is not null and instalacion_id is not null)
);

create index ix_fiscal_export_download_token_expiry
    on fiscal_export_download_token(expira_en);
