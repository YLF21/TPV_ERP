\set ON_ERROR_STOP on

-- Ejecutar como propietario de los objetos afectados o como administrador.
-- La contrasena se solicita de forma interactiva por psql y nunca debe
-- incluirse en este archivo ni en la linea de comandos.
\if :{?runtime_role}
\else
  \warn 'Falta -v runtime_role=<rol de la aplicacion>'
  \set runtime_role __missing_runtime_role__
\endif

\if :{?migration_role}
\else
  \warn 'Falta -v migration_role=<rol que creo los objetos>'
  \set migration_role __missing_migration_role__
\endif

\if :{?schema_name}
\else
  \set schema_name public
\endif

select exists (select 1 from pg_roles where rolname = :'runtime_role')
       as runtime_role_exists
\gset
\if :runtime_role_exists
\else
  \warn 'El rol de ejecucion indicado no existe'
  \set runtime_role __invalid_runtime_role__
\endif

select exists (select 1 from pg_roles where rolname = :'migration_role')
       as migration_role_exists
\gset
\if :migration_role_exists
\else
  \warn 'El rol de migraciones indicado no existe'
  \set migration_role __invalid_migration_role__
\endif

select exists (select 1 from pg_namespace where nspname = :'schema_name')
       as schema_exists
\gset
\if :schema_exists
\else
  \warn 'El esquema indicado no existe'
  \set schema_name __invalid_schema__
\endif

begin;

select format('grant usage on schema %I to %I', :'schema_name', :'runtime_role')
\gexec

select format(
    'grant select, insert, update, delete on all tables in schema %I to %I',
    :'schema_name', :'runtime_role')
\gexec

select format(
    'grant usage, select, update on all sequences in schema %I to %I',
    :'schema_name', :'runtime_role')
\gexec

select format(
    'alter default privileges for role %I in schema %I '
    'grant select, insert, update, delete on tables to %I',
    :'migration_role', :'schema_name', :'runtime_role')
\gexec

select format(
    'alter default privileges for role %I in schema %I '
    'grant usage, select, update on sequences to %I',
    :'migration_role', :'schema_name', :'runtime_role')
\gexec

commit;

select not exists (
    select 1
    from pg_class relation
    join pg_namespace namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = :'schema_name'
      and relation.relkind in ('r', 'p')
      and not (
          has_table_privilege(:'runtime_role', relation.oid, 'SELECT')
          and has_table_privilege(:'runtime_role', relation.oid, 'INSERT')
          and has_table_privilege(:'runtime_role', relation.oid, 'UPDATE')
          and has_table_privilege(:'runtime_role', relation.oid, 'DELETE')
      )
) as table_privileges_ok
\gset

select not exists (
    select 1
    from pg_class relation
    join pg_namespace namespace on namespace.oid = relation.relnamespace
    where namespace.nspname = :'schema_name'
      and relation.relkind = 'S'
      and not (
          has_sequence_privilege(:'runtime_role', relation.oid, 'USAGE')
          and has_sequence_privilege(:'runtime_role', relation.oid, 'SELECT')
          and has_sequence_privilege(:'runtime_role', relation.oid, 'UPDATE')
      )
) as sequence_privileges_ok
\gset

\if :table_privileges_ok
\else
  \warn 'La verificacion de permisos sobre tablas ha fallado'
  select 1 / 0 as table_privilege_verification_failed;
\endif

\if :sequence_privileges_ok
  \echo 'Permisos de ejecucion reconciliados y verificados correctamente.'
\else
  \warn 'La verificacion de permisos sobre secuencias ha fallado'
  select 1 / 0 as sequence_privilege_verification_failed;
\endif
