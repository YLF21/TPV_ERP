alter table licencia
    add column ultima_validacion_saas timestamptz;

update licencia
    set ultima_validacion_saas = importada_en
    where ultima_validacion_saas is null;

alter table licencia
    alter column ultima_validacion_saas set not null;
