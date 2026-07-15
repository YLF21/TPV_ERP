alter table familia add column family_id varchar(32);

with normalized as (
    select id,
           tienda_id,
           coalesce(
               nullif(trim(both '_' from regexp_replace(upper(trim(nombre)), '[^A-Z0-9]+', '_', 'g')), ''),
               'FAMILIA'
           ) as base
    from familia
), numbered as (
    select id,
           base,
           row_number() over (partition by tienda_id, base order by id) as duplicate_number
    from normalized
)
update familia f
set family_id = case
    when n.duplicate_number = 1 then left(n.base, 32)
    else left(n.base, 28) || '_' || n.duplicate_number
end
from numbered n
where n.id = f.id;

alter table familia alter column family_id set not null;
alter table familia alter column family_id set default 'GENERAL';
alter table familia add constraint ck_familia_family_id_not_blank check (char_length(trim(family_id)) > 0);
create unique index ux_familia_family_id_tienda_ci on familia(tienda_id, upper(trim(family_id)));

alter table subfamilia add column subfamily_id varchar(32);

with normalized as (
    select id,
           familia_id,
           coalesce(
               nullif(trim(both '_' from regexp_replace(upper(trim(nombre)), '[^A-Z0-9]+', '_', 'g')), ''),
               'SUBFAMILIA'
           ) as base
    from subfamilia
), numbered as (
    select id,
           base,
           row_number() over (partition by familia_id, base order by id) as duplicate_number
    from normalized
)
update subfamilia s
set subfamily_id = case
    when n.duplicate_number = 1 then left(n.base, 32)
    else left(n.base, 28) || '_' || n.duplicate_number
end
from numbered n
where n.id = s.id;

alter table subfamilia alter column subfamily_id set not null;
alter table subfamilia alter column subfamily_id set default 'SUBFAMILIA';
alter table subfamilia add constraint ck_subfamilia_subfamily_id_not_blank check (char_length(trim(subfamily_id)) > 0);
create unique index ux_subfamilia_subfamily_id_familia_ci on subfamilia(familia_id, upper(trim(subfamily_id)));
