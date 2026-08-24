alter table requerimiento_fiscal
    alter column referencia type varchar(18);

alter table requerimiento_fiscal
    add constraint ck_requerimiento_fiscal_referencia
    check (char_length(trim(referencia)) between 1 and 18);
