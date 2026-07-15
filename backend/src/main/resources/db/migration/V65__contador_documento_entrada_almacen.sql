alter table contador_documento
    drop constraint if exists contador_documento_tipo_check;

alter table contador_documento
    add constraint contador_documento_tipo_check
    check (tipo in ('SAL', 'ENT', 'AV', 'AC', 'T', 'FV', 'FC', 'FRV', 'FRC'));
