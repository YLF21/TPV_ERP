alter table sync_outbox
    add column store_sequence bigint;

alter table sync_outbox
    add constraint ck_sync_outbox_store_sequence
    check (store_sequence is null or store_sequence > 0);

alter table member_points_operation
    add column store_sequence bigint;

with ranked as (
    select operation_id,
           row_number() over (
               partition by tienda_id
               order by occurred_at, operation_id::text
           ) as store_sequence
    from member_points_operation
)
update member_points_operation operation
set store_sequence = ranked.store_sequence
from ranked
where ranked.operation_id = operation.operation_id;

alter table member_points_operation
    alter column store_sequence set not null;

alter table member_points_operation
    add constraint ck_member_points_operation_store_sequence
    check (store_sequence > 0);

create unique index uk_member_points_operation_store_sequence
    on member_points_operation(tienda_id, store_sequence);

create table member_points_projection_state (
    tienda_id uuid primary key references tienda(id),
    empresa_id uuid not null references empresa(id),
    status varchar(32) not null default 'LOCAL_ACTIVE',
    last_sequence bigint not null default 0,
    cut_sequence bigint,
    projected_through_sequence bigint not null default 0,
    official_through_sequence bigint not null default 0,
    bootstrap_id uuid,
    snapshot_id uuid,
    cutoff_at timestamptz,
    version bigint not null default 0,
    constraint ck_member_points_projection_status check (status in (
        'LOCAL_ACTIVE', 'FROZEN', 'WAITING_OFFICIAL',
        'CATCHING_UP', 'CONFLICT'
    )),
    constraint ck_member_points_projection_sequences check (
        last_sequence >= 0
        and projected_through_sequence >= 0
        and official_through_sequence >= 0
        and projected_through_sequence <= last_sequence
        and official_through_sequence <= last_sequence
        and (cut_sequence is null
            or (cut_sequence >= 0 and cut_sequence <= last_sequence))
    ),
    constraint ck_member_points_projection_freeze_metadata check (
        (status = 'LOCAL_ACTIVE'
            and cut_sequence is null
            and bootstrap_id is null
            and snapshot_id is null
            and cutoff_at is null)
        or (status <> 'LOCAL_ACTIVE'
            and cut_sequence is not null
            and bootstrap_id is not null
            and snapshot_id is not null
            and cutoff_at is not null)
    )
);

insert into member_points_projection_state (
    tienda_id, empresa_id, status, last_sequence,
    projected_through_sequence, official_through_sequence
)
select tienda.id,
       tienda.empresa_id,
       'LOCAL_ACTIVE',
       coalesce(max(operation.store_sequence), 0),
       coalesce(max(operation.store_sequence), 0),
       0
from tienda
left join member_points_operation operation
    on operation.tienda_id = tienda.id
group by tienda.id, tienda.empresa_id
on conflict (tienda_id) do nothing;

create index ix_member_points_projection_company_status
    on member_points_projection_state(empresa_id, status, tienda_id);

create table member_points_bootstrap_snapshot (
    snapshot_id uuid primary key,
    bootstrap_id uuid not null,
    empresa_id uuid not null references empresa(id),
    tienda_id uuid not null references tienda(id),
    cutoff_at timestamptz not null,
    cut_sequence bigint not null,
    account_chunk_count integer not null,
    operation_chunk_count integer not null,
    account_count bigint not null,
    operation_count bigint not null,
    snapshot_checksum varchar(64) not null,
    created_at timestamptz not null,
    constraint uk_member_points_bootstrap_store
        unique (bootstrap_id, tienda_id),
    constraint ck_member_points_bootstrap_counts check (
        cut_sequence >= 0
        and account_chunk_count >= 0
        and operation_chunk_count >= 0
        and account_count >= 0
        and operation_count >= 0
    ),
    constraint ck_member_points_bootstrap_checksum check (
        snapshot_checksum ~ '^[0-9a-f]{64}$'
    )
);

create table member_points_bootstrap_account (
    id uuid primary key,
    snapshot_id uuid not null references member_points_bootstrap_snapshot(snapshot_id),
    member_id uuid not null references miembro(id),
    points bigint not null,
    points_debt bigint not null,
    constraint uk_member_points_bootstrap_account
        unique (snapshot_id, member_id),
    constraint ck_member_points_bootstrap_account_values check (
        points >= 0 and points_debt >= 0
    )
);

create index ix_member_points_bootstrap_account_order
    on member_points_bootstrap_account(snapshot_id, member_id);

create table member_points_bootstrap_operation (
    id uuid primary key,
    snapshot_id uuid not null references member_points_bootstrap_snapshot(snapshot_id),
    operation_id uuid not null references member_points_operation(operation_id),
    contract_hash varchar(64) not null,
    source_sequence bigint not null,
    constraint uk_member_points_bootstrap_operation
        unique (snapshot_id, operation_id),
    constraint ck_member_points_bootstrap_operation_values check (
        source_sequence > 0
        and contract_hash ~ '^[0-9a-f]{64}$'
    )
);

create index ix_member_points_bootstrap_operation_order
    on member_points_bootstrap_operation(snapshot_id, operation_id);

alter table member_document_loyalty_settlement
    add column deferred_points bigint not null default 0,
    add column reversed_deferred_points bigint not null default 0;

alter table member_document_loyalty_settlement
    drop constraint ck_member_document_loyalty_non_negative,
    drop constraint ck_member_document_loyalty_points_breakdown,
    drop constraint ck_member_document_loyalty_reversal_limits;

alter table member_document_loyalty_settlement
    add constraint ck_member_document_loyalty_non_negative check (
        document_amount >= 0
        and eligible_document_amount >= 0
        and eligible_paid_amount >= 0
        and generated_points >= 0
        and granted_points >= 0
        and points_applied_to_debt >= 0
        and deferred_points >= 0
        and reversed_deferred_points >= 0
        and generated_balance >= 0
        and granted_balance >= 0
        and balance_applied_to_debt >= 0
        and member_balance_used >= 0
        and reversed_eligible_amount >= 0
        and reversed_points >= 0
        and reversed_balance >= 0
        and restored_member_balance >= 0
        and return_points_debt_created >= 0
        and return_balance_debt_created >= 0),
    add constraint ck_member_document_loyalty_points_breakdown check (
        generated_points = granted_points + points_applied_to_debt + deferred_points),
    add constraint ck_member_document_loyalty_reversal_limits check (
        eligible_document_amount <= document_amount
        and eligible_paid_amount <= eligible_document_amount
        and reversed_eligible_amount <= eligible_document_amount
        and reversed_points <= generated_points
        and reversed_deferred_points <= generated_points
        and reversed_balance <= generated_balance
        and restored_member_balance <= member_balance_used);
