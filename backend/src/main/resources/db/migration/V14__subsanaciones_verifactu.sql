alter table estado_envio_fiscal
    drop constraint estado_envio_fiscal_estado_check;

alter table estado_envio_fiscal
    add constraint estado_envio_fiscal_estado_check
    check (estado in (
        'PENDIENTE', 'ENVIANDO', 'ENVIADO', 'ACEPTADO',
        'ACEPTADO_CON_ERRORES', 'RECHAZADO', 'DEFECTUOSO', 'SUBSANADO'
    ));
