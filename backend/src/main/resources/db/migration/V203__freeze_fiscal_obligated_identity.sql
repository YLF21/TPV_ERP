-- New artifacts freeze the obligated taxpayer identity used by the AEAT batch
-- header. Historical rows stay explicitly unfrozen instead of being silently
-- rewritten from mutable current company data.
alter table artefacto_registro_fiscal
    add column obligado_nombre varchar(250),
    add column obligado_nif varchar(9),
    add constraint ck_artefacto_obligado_identidad_conjunta
        check ((obligado_nombre is null and obligado_nif is null)
            or (char_length(trim(obligado_nombre)) > 0
                and char_length(trim(obligado_nif)) > 0));
