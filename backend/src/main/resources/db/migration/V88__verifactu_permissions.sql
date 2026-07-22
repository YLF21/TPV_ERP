insert into permiso (id, codigo, translation_key, grupo)
select gen_random_uuid(), source.codigo, source.translation_key, 'FISCAL'
from (values
    ('VERIFACTU_READ', 'verifactu.permissions.read'),
    ('VERIFACTU_CORRECT', 'verifactu.permissions.correct'),
    ('VERIFACTU_MANAGE', 'verifactu.permissions.manage')
) as source(codigo, translation_key)
where not exists (
    select 1 from permiso where permiso.codigo = source.codigo
);
