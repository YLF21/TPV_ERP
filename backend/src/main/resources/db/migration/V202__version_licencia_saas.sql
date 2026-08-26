alter table licencia
    add column saas_license_version bigint;

alter table licencia
    add constraint ck_licencia_saas_license_version_positive
        check (saas_license_version is null or saas_license_version >= 1);
