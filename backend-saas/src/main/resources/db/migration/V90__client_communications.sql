create table saas_communication (
    id uuid primary key,
    subject varchar(200) not null,
    html text not null,
    plain_text text not null,
    audience_mode varchar(8) not null check (audience_mode in ('ALL','SELECTED')),
    recipient_ids uuid[] not null check (cardinality(recipient_ids) <= 10000),
    version bigint not null default 0,
    review_id uuid,
    reviewed_count bigint not null default 0,
    smtp_version bigint,
    from_address varchar(254),
    from_name varchar(120),
    reply_to varchar(254),
    created_by varchar(80) not null,
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    queued_at timestamptz,
    queued_version bigint,
    cancelled_at timestamptz,
    check (queued_at is null or (review_id is not null and reviewed_count > 0)),
    check (cancelled_at is null or queued_at is not null)
);
create index ix_saas_communication_history on saas_communication(updated_at desc,id);

-- One envelope and one stable RFC Message-ID per reviewed address, including after an explicit retry.
create table saas_communication_recipient (
    id uuid primary key,
    communication_id uuid not null references saas_communication(id) on delete cascade,
    source_id uuid not null,
    label text not null,
    email varchar(254) not null,
    status varchar(12) not null check (status in ('REVIEW','PENDING','SENDING','SENT','FAILED','UNCERTAIN','CANCELLED')),
    attempts integer not null default 0 check (attempts >= 0),
    claimed_at timestamptz,
    last_error varchar(64),
    sent_at timestamptz,
    check ((status='SENDING') = (claimed_at is not null)),
    check ((status='SENT') = (sent_at is not null))
);
create unique index ux_saas_communication_email on saas_communication_recipient(communication_id,lower(email));
create index ix_saas_communication_recipient_page on saas_communication_recipient(communication_id,lower(label),id);
create index ix_saas_communication_pending on saas_communication_recipient(communication_id,id) where status='PENDING';
create index ix_saas_communication_claim on saas_communication_recipient(claimed_at) where status='SENDING';

create table saas_communication_pacing (
    singleton boolean primary key check (singleton),
    next_claim_at timestamptz not null default '-infinity'
);
insert into saas_communication_pacing(singleton) values (true);
