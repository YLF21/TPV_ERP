alter table licencia drop constraint licencia_estado_saas_check;

alter table licencia add constraint licencia_estado_saas_check
    check (estado_saas in ('VALIDA', 'BLOQUEADA_MANUAL', 'CADUCADA', 'REQUIERE_ACTUALIZACION'));
