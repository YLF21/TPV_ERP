-- Preserve rule identities, events and append-only versions when removing a configuration.
alter table control_regla
    add column retirada boolean not null default false,
    add constraint control_regla_retirada_inactiva_ck check (not retirada or not activa);

alter table control_regla_version
    add column retirada boolean not null default false,
    add constraint control_regla_version_retirada_inactiva_ck check (not retirada or not activa);
