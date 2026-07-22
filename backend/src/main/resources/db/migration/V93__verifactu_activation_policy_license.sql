alter table licencia
    add column verifactu_activation_date date,
    add column verifactu_policy_version bigint,
    add column verifactu_policy_updated_at timestamp with time zone;

alter table licencia add constraint ck_licencia_verifactu_policy_version
    check (verifactu_policy_version is null or verifactu_policy_version >= 0);

comment on column licencia.verifactu_activation_date is
    'Fecha de activacion VERI*FACTU distribuida por la politica fiscal global SaaS';
comment on column licencia.verifactu_policy_version is
    'Version de la politica fiscal SaaS recibida por la instalacion';
