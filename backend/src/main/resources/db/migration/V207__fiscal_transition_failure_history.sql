-- Scheduled transitions are append-only. A failed application is therefore a
-- separate immutable row linked to the original request, never an in-place
-- mutation that could erase fiscal history.
alter table transicion_modo_fiscal
    add column transicion_origen_id uuid references transicion_modo_fiscal(id),
    add column ultimo_error_codigo varchar(64),
    add column ultimo_error text;

alter table transicion_modo_fiscal
    drop constraint ck_transicion_modo_fiscal_estado,
    add constraint ck_transicion_modo_fiscal_estado
        check (estado in ('APLICADA', 'PROGRAMADA', 'FALLIDA'));

alter table transicion_modo_fiscal
    drop constraint ck_transicion_modo_fiscal_programada,
    add constraint ck_transicion_modo_fiscal_programada
        check (estado = 'APLICADA'
            or (fecha_fin_verifactu is not null and ack_aeat is not null
                and char_length(trim(ack_aeat)) > 0)),
    add constraint ck_transicion_modo_fiscal_fallida
        check ((estado <> 'FALLIDA' and transicion_origen_id is null
                    and ultimo_error_codigo is null and ultimo_error is null)
            or (estado = 'FALLIDA' and transicion_origen_id is not null
                    and char_length(trim(ultimo_error_codigo)) > 0
                    and char_length(trim(ultimo_error)) > 0));

create unique index ux_transicion_modo_fiscal_fallo_origen
    on transicion_modo_fiscal(transicion_origen_id)
    where estado = 'FALLIDA';
