alter table documento
    drop constraint documento_estado_check;

alter table documento
    add constraint documento_estado_check
        check (estado in ('BORRADOR', 'CONFIRMADO', 'ANULADO', 'PENDIENTE', 'PARCIAL', 'PAGADO'));
