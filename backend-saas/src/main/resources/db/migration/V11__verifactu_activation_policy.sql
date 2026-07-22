create table verifactu_activation_policy (
    taxpayer_type varchar(32) primary key,
    activation_date date not null,
    version bigint not null default 0,
    updated_at timestamp with time zone not null,
    updated_by varchar(80) not null,
    reason text not null,
    constraint ck_verifactu_activation_policy_taxpayer
        check (taxpayer_type in ('SOCIEDAD', 'AUTONOMO')),
    constraint ck_verifactu_activation_policy_reason
        check (length(trim(reason)) between 3 and 500)
);

insert into verifactu_activation_policy (
    taxpayer_type, activation_date, version, updated_at, updated_by, reason)
values
    ('SOCIEDAD', date '2027-01-01', 0, current_timestamp, 'system',
     'Plazo vigente para contribuyentes del Impuesto sobre Sociedades'),
    ('AUTONOMO', date '2027-07-01', 0, current_timestamp, 'system',
     'Plazo vigente para el resto de obligados tributarios');

insert into saas_admin_permission(code) values ('MANAGE_FISCAL_POLICY');

insert into saas_admin_role_permission(role_id, permission_code)
select id, 'MANAGE_FISCAL_POLICY'
from saas_admin_role
where name = 'ADMIN';

alter table saas_admin_audit_log add column details text;
