alter table transicion_modo_fiscal
    add column estado varchar(16) not null default 'APLICADA',
    add column fecha_fin_verifactu date,
    add column ack_aeat varchar(128);

alter table transicion_modo_fiscal
    add constraint ck_transicion_modo_fiscal_estado
    check (estado in ('APLICADA', 'PROGRAMADA'));

alter table transicion_modo_fiscal
    add constraint ck_transicion_modo_fiscal_programada
    check (estado = 'APLICADA'
        or (fecha_fin_verifactu is not null and ack_aeat is not null
            and char_length(trim(ack_aeat)) > 0));

create index ix_transicion_modo_fiscal_programada
    on transicion_modo_fiscal(empresa_id, instalacion_id, estado, efectiva_en);
