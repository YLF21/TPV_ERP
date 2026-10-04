-- Operational terminal UUIDs and economic foreign keys remain unchanged.
alter table terminal add column workstation_code varchar(12);
alter table terminal add column current_binding_id uuid;
update terminal set workstation_code = '001' where tipo = 'SERVIDOR';
alter table terminal add constraint terminal_workstation_code_ck check (
    workstation_code is null or
    (workstation_code ~ '^[0-9]{3,9}$' and workstation_code::bigint > 0
     and workstation_code = lpad((workstation_code::bigint)::text,
                                greatest(3, length((workstation_code::bigint)::text)), '0')
     and ((tipo = 'SERVIDOR' and workstation_code = '001')
          or (tipo = 'TERMINAL_VENTA' and workstation_code <> '001'))));
create unique index terminal_workstation_code_uq on terminal(tienda_id, workstation_code)
    where workstation_code is not null;

create table terminal_physical_binding (
    id uuid primary key,
    terminal_id uuid not null references terminal(id),
    request_id uuid not null unique,
    device_id uuid not null,
    credential_hash varchar(255) not null,
    name varchar(128) not null,
    device_name varchar(128) not null,
    status varchar(16) not null,
    created_at timestamptz not null,
    expires_at timestamptz,
    approved_at timestamptz,
    ended_at timestamptz,
    last_seen_at timestamptz,
    legacy_adoption_pending boolean not null default false,
    version bigint not null default 0,
    constraint terminal_binding_status_ck check
        (status in ('PENDING','ACTIVE','DISABLED','RELEASED','CANCELLED','EXPIRED')),
    constraint terminal_binding_lifecycle_ck check
        ((status = 'PENDING' and expires_at is not null and ended_at is null)
        or (status in ('ACTIVE','DISABLED') and expires_at is null and ended_at is null and approved_at is not null)
        or (status in ('RELEASED','CANCELLED','EXPIRED') and expires_at is null and ended_at is not null)),
    unique (id, terminal_id)
);
create unique index terminal_binding_occupied_uq on terminal_physical_binding(terminal_id)
    where status in ('PENDING','ACTIVE','DISABLED');
alter table terminal add constraint terminal_current_binding_fk
    foreign key (current_binding_id, id) references terminal_physical_binding(id, terminal_id);
alter table sesion add column terminal_binding_id uuid;
alter table sesion add constraint session_terminal_binding_fk
    foreign key (terminal_binding_id, terminal_id) references terminal_physical_binding(id, terminal_id);
create index terminal_binding_history_ix on terminal_physical_binding(terminal_id, created_at desc);
