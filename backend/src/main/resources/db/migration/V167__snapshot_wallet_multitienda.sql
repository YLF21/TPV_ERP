create table member_wallet_bootstrap_snapshot (
    snapshot_id uuid primary key,
    bootstrap_id uuid not null,
    empresa_id uuid not null references empresa(id),
    tienda_id uuid not null references tienda(id),
    saas_empresa_id uuid not null,
    saas_tienda_id uuid not null,
    cutoff_at timestamptz not null,
    account_chunk_count integer not null default 0,
    lot_chunk_count integer not null default 0,
    account_count integer not null default 0,
    lot_count integer not null default 0,
    snapshot_checksum varchar(64),
    status varchar(16) not null,
    remote_status varchar(16),
    begin_accepted boolean not null default false,
    next_account_chunk integer not null default 0,
    next_lot_chunk integer not null default 0,
    complete_sent boolean not null default false,
    attempts integer not null default 0,
    next_attempt_at timestamptz,
    last_error varchar(1000),
    conflict_reason varchar(1000),
    created_at timestamptz not null,
    submitted_at timestamptz,
    completed_at timestamptz,
    version bigint not null default 0,
    constraint uk_member_wallet_bootstrap_snapshot_store
        unique (bootstrap_id, saas_tienda_id),
    constraint ck_member_wallet_bootstrap_snapshot_status
        check (status in (
            'CAPTURING', 'CAPTURED', 'UPLOADING', 'SUBMITTED',
            'COMPLETED', 'CONFLICT', 'CANCELLED'
        )),
    constraint ck_member_wallet_bootstrap_remote_status
        check (remote_status is null or remote_status in (
            'COLLECTING', 'RECONCILING', 'CONFLICT', 'COMPLETED', 'CANCELLED'
        )),
    constraint ck_member_wallet_bootstrap_counts
        check (
            account_chunk_count >= 0
            and lot_chunk_count >= 0
            and account_count >= 0
            and lot_count >= 0
            and next_account_chunk between 0 and account_chunk_count
            and next_lot_chunk between 0 and lot_chunk_count
            and attempts >= 0
        ),
    constraint ck_member_wallet_bootstrap_checksum
        check (
            (status = 'CAPTURING' and snapshot_checksum is null)
            or (status <> 'CAPTURING' and snapshot_checksum ~ '^[0-9a-f]{64}$')
        )
);

create index ix_member_wallet_bootstrap_snapshot_progress
    on member_wallet_bootstrap_snapshot(saas_tienda_id, status, next_attempt_at, created_at);

create table member_wallet_bootstrap_snapshot_account (
    id uuid primary key,
    snapshot_id uuid not null references member_wallet_bootstrap_snapshot(snapshot_id)
        on delete cascade,
    member_id uuid not null,
    loyalty_balance numeric(19,2) not null,
    return_credit_balance numeric(19,2) not null,
    constraint uk_member_wallet_bootstrap_account_member
        unique (snapshot_id, member_id),
    constraint ck_member_wallet_bootstrap_account_balances
        check (loyalty_balance >= 0 and return_credit_balance >= 0)
);

create index ix_member_wallet_bootstrap_account_order
    on member_wallet_bootstrap_snapshot_account(snapshot_id, member_id);

create table member_wallet_bootstrap_snapshot_lot (
    id uuid primary key,
    snapshot_id uuid not null references member_wallet_bootstrap_snapshot(snapshot_id)
        on delete cascade,
    lot_id uuid not null,
    member_id uuid not null,
    balance_type varchar(24) not null,
    original_amount numeric(19,2) not null,
    remaining_amount numeric(19,2) not null,
    created_at timestamptz not null,
    expires_at timestamptz,
    source_movement_id uuid,
    document_id uuid,
    constraint uk_member_wallet_bootstrap_lot unique (snapshot_id, lot_id),
    constraint ck_member_wallet_bootstrap_lot_type
        check (balance_type in ('LOYALTY', 'RETURN_CREDIT')),
    constraint ck_member_wallet_bootstrap_lot_amounts
        check (
            original_amount >= 0
            and remaining_amount >= 0
            and remaining_amount <= original_amount
        )
);

create index ix_member_wallet_bootstrap_lot_order
    on member_wallet_bootstrap_snapshot_lot(snapshot_id, lot_id);

create index ix_member_wallet_bootstrap_lot_source
    on member_wallet_bootstrap_snapshot_lot(snapshot_id, source_movement_id)
    where source_movement_id is not null;

create table member_wallet_bootstrap_worker_state (
    tienda_id uuid primary key references tienda(id),
    empresa_id uuid not null references empresa(id),
    saas_empresa_id uuid not null,
    saas_tienda_id uuid not null,
    active_bootstrap_id uuid,
    attempts integer not null default 0,
    next_attempt_at timestamptz,
    last_error varchar(1000),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint ck_member_wallet_bootstrap_worker_attempts check (attempts >= 0)
);

create unique index uk_member_wallet_bootstrap_worker_saas_store
    on member_wallet_bootstrap_worker_state(saas_tienda_id);
