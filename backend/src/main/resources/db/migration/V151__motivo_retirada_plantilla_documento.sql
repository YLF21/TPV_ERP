alter table plantilla_documento
    add column motivo_retirada varchar(40);

alter table plantilla_documento
    add constraint ck_plantilla_documento_motivo_retirada
        check (motivo_retirada is null or motivo_retirada in (
            'REPLACED_BY_TEMPLATE',
            'BUILT_IN_DESIGN_SELECTED'
        ));
