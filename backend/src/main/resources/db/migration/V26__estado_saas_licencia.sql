alter table licencia
    add column estado_saas varchar(32) not null default 'VALIDA';

alter table licencia
    add constraint licencia_estado_saas_check
    check (estado_saas in ('VALIDA', 'BLOQUEADA_MANUAL'));
