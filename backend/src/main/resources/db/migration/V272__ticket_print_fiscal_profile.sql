alter table documento
    add column perfil_fiscal_impresion_ticket varchar(32);

alter table documento
    add constraint documento_ticket_print_fiscal_profile_check
        check (perfil_fiscal_impresion_ticket is null
            or (tipo = 'TICKET' and perfil_fiscal_impresion_ticket in ('IVA', 'IGIC', 'IGIC_MINORISTA')));
