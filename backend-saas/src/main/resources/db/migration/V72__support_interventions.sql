create table saas_support_intervention (
    ticket_id uuid primary key references saas_support_ticket(id),
    status varchar(24) not null check (status in ('REMOTE_PENDING','REMOTE_IN_PROGRESS','ONSITE_REQUIRED','ONSITE_IN_PROGRESS','RESOLVED')),
    version bigint not null check (version > 0),
    team_viewer_id varchar(15) check (team_viewer_id ~ '^[0-9]{6,15}$')
);

create table saas_support_intervention_event (
    request_id uuid primary key,
    ticket_id uuid not null references saas_support_ticket(id),
    version bigint not null check (version > 0),
    expected_ticket_status varchar(16) not null check (expected_ticket_status in ('ABIERTO','EN_CURSO','RESUELTO')),
    action varchar(24) not null check (action in ('START_REMOTE','REQUIRE_ONSITE','START_ONSITE','RESOLVE','REOPEN')),
    status varchar(24) not null check (status in ('REMOTE_PENDING','REMOTE_IN_PROGRESS','ONSITE_REQUIRED','ONSITE_IN_PROGRESS','RESOLVED')),
    note varchar(2000) not null check (length(note) between 5 and 2000),
    team_viewer_id varchar(15) check (team_viewer_id ~ '^[0-9]{6,15}$'),
    requested_team_viewer_id varchar(15) check (requested_team_viewer_id ~ '^[0-9]{6,15}$'),
    actor varchar(80) not null,
    created_at timestamptz not null,
    unique(ticket_id, version)
);
create index idx_saas_store_failure_manual_ticket_company on saas_store_failure_manual(ticket_id, company_id);
