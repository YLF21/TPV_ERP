-- Application evidence is independent of business transactions and contains no messages or payloads.
create table local_application_failure (
    id uuid primary key,
    empresa_id uuid not null,
    tienda_id uuid not null,
    instalacion_id uuid not null,
    fingerprint varchar(64) not null,
    module varchar(20) not null check (module in ('SALES','PRINTING','SYNC','APPLICATION')),
    app_version varchar(80),
    trace_id varchar(128) not null,
    exception_type varchar(160),
    error_location varchar(240),
    first_seen_at timestamptz not null,
    last_seen_at timestamptz not null,
    occurrences bigint not null default 1 check (occurrences > 0),
    revision bigint not null default 0 check (revision >= 0),
    unique (empresa_id, tienda_id, instalacion_id, fingerprint)
);
create index ix_local_application_failure_scan
    on local_application_failure (empresa_id, tienda_id, instalacion_id, last_seen_at, id);
