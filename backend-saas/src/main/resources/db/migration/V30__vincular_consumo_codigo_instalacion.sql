alter table saas_pairing_code
    add column consumed_installation_id uuid references saas_installation(id);

create index idx_saas_pairing_code_consumed_installation
    on saas_pairing_code(consumed_installation_id)
    where consumed_installation_id is not null;
