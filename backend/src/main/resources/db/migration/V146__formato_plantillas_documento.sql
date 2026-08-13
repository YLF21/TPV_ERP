alter table plantilla_documento
    add column formato varchar(16);

update plantilla_documento
set formato = case when tipo = 'TICKET' then 'TICKET_80' else 'A4' end;

alter table plantilla_documento
    alter column formato set not null;

alter table plantilla_documento
    add constraint ck_plantilla_documento_formato
        check (formato in ('A4', 'TICKET_80'));

drop index uk_plantilla_documento_system_active;
drop index uk_plantilla_documento_company_active;
drop index uk_plantilla_documento_store_active;
drop index idx_plantilla_documento_resolucion;

create unique index uk_plantilla_documento_system_active
    on plantilla_documento (tipo, formato)
    where ambito = 'SYSTEM' and estado = 'ACTIVE';

create unique index uk_plantilla_documento_company_active
    on plantilla_documento (empresa_id, tipo, formato)
    where ambito = 'COMPANY' and estado = 'ACTIVE';

create unique index uk_plantilla_documento_store_active
    on plantilla_documento (tienda_id, tipo, formato)
    where ambito = 'STORE' and estado = 'ACTIVE';

create index idx_plantilla_documento_resolucion
    on plantilla_documento (tipo, formato, estado, tienda_id, empresa_id);
