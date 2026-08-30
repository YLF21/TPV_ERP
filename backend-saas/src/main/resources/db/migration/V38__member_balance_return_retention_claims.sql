alter table saas_member_balance_reservation
    add column if not exists retention_revision bigint not null default 0;

alter table saas_member_balance_reservation
    add column if not exists retention_fingerprint varchar(128) not null default '';

alter table saas_member_balance_reservation
    add column if not exists retention_attributed_amount numeric(19,2) not null default 0;

create table if not exists saas_member_balance_retention_receipt (
    operation_id uuid primary key,
    company_id uuid not null,
    store_id uuid not null,
    member_id uuid not null,
    source_document_id uuid not null,
    return_document_id uuid,
    attributed_amount numeric(19,2) not null,
    fingerprint varchar(128) not null,
    status varchar(16) not null,
    recovered_known numeric(19,2) not null default 0,
    pending_missing numeric(19,2) not null default 0,
    spent_shortfall numeric(19,2) not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint ck_saas_member_balance_retention_receipt_amounts
        check (attributed_amount >= 0 and recovered_known >= 0
            and pending_missing >= 0 and spent_shortfall >= 0
            and recovered_known + pending_missing + spent_shortfall = attributed_amount),
    constraint ck_saas_member_balance_retention_receipt_status
        check (status in ('COMMITTED'))
);

create table if not exists saas_member_balance_retention_claim (
    id uuid primary key,
    reservation_id uuid references saas_member_balance_reservation(id),
    receipt_id uuid references saas_member_balance_retention_receipt(operation_id),
    lot_id uuid not null,
    source_movement_id uuid not null,
    source_document_id uuid not null,
    amount_original numeric(19,2) not null,
    amount numeric(19,2) not null,
    held_amount numeric(19,2) not null default 0,
    status varchar(24) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint ck_saas_member_balance_retention_claim_amount
        check (amount > 0 and amount <= amount_original and held_amount >= 0 and held_amount <= amount),
    constraint ck_saas_member_balance_retention_claim_status
        check (status in ('HELD_KNOWN', 'HELD_MISSING', 'COMMITTED_PENDING', 'APPLIED', 'CANCELLED')),
    constraint uq_saas_member_balance_retention_claim
        unique (reservation_id, lot_id),
    constraint uq_saas_member_balance_retention_claim_receipt
        unique (receipt_id, lot_id),
    constraint ck_saas_member_balance_retention_claim_owner
        check ((reservation_id is not null) <> (receipt_id is not null))
);

create index if not exists ix_saas_member_balance_retention_claim_lot_status
    on saas_member_balance_retention_claim(lot_id, status);

create index if not exists ix_saas_member_balance_retention_receipt_scope
    on saas_member_balance_retention_receipt(company_id, member_id, source_document_id);
