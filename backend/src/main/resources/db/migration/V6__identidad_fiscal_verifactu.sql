alter table licencia add column tax_id varchar(9);
alter table licencia add column taxpayer_type varchar(16);
alter table tienda add column codigo_tienda varchar(3);

with numbered as (
    select id,
           row_number() over (partition by empresa_id order by id)::integer as code
    from tienda
)
update tienda
set codigo_tienda = lpad(numbered.code::text, 3, '0')
from numbered
where numbered.id = tienda.id;

alter table tienda alter column codigo_tienda set not null;

alter table tienda add constraint ck_tienda_codigo_tienda
    check (codigo_tienda ~ '^[0-9]{3}$' and codigo_tienda <> '000');

alter table tienda add constraint ux_tienda_empresa_codigo_tienda
    unique (empresa_id, codigo_tienda);

alter table licencia add constraint ck_licencia_identidad_fiscal
    check (
        (format_version < 3 and tax_id is null and taxpayer_type is null)
        or
        (format_version >= 3
            and tax_id ~ '^[A-Z0-9]{9}$'
            and taxpayer_type in ('SOCIEDAD', 'AUTONOMO'))
    );
