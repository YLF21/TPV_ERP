do $$
declare
    constraint_name text;
begin
    for constraint_name in
        select con.conname
        from pg_constraint con
        where con.conrelid = 'artefacto_registro_fiscal'::regclass
          and pg_get_constraintdef(con.oid) like '%xml_firmado%'
    loop
        execute format('alter table artefacto_registro_fiscal drop constraint %I', constraint_name);
    end loop;
end $$;

alter table artefacto_registro_fiscal
    add constraint ck_artefacto_registro_fiscal_signed
    check (
        (modo_fiscal = 'NO_VERIFACTU' and xml_firmado is not null)
        or (modo_fiscal = 'VERIFACTU')
    );

alter table artefacto_registro_fiscal
    add constraint ck_artefacto_registro_fiscal_veri_unsigned
    check (modo_fiscal <> 'VERIFACTU' or xml_firmado is null);
