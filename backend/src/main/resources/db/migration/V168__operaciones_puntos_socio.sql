create table member_points_operation (
    operation_id uuid primary key,
    miembro_id uuid not null references miembro(id),
    empresa_id uuid not null references empresa(id),
    tienda_id uuid not null references tienda(id),
    operation_type varchar(32) not null,
    amount bigint not null,
    source_document_id uuid references documento(id),
    original_document_id uuid references documento(id),
    occurred_at timestamptz not null,
    local_points_delta bigint not null,
    local_debt_delta bigint not null,
    source_checkpoint varchar(64),
    payload_hash varchar(64) not null,
    constraint ck_member_points_operation_type check (operation_type in (
        'SALE_EARN', 'RETURN_REVERSAL', 'SALE_CANCELLATION',
        'RETURN_CANCELLATION', 'MANUAL_ADJUSTMENT'
    )),
    constraint ck_member_points_operation_amount check (
        (operation_type = 'MANUAL_ADJUSTMENT' and amount <> 0)
        or (operation_type <> 'MANUAL_ADJUSTMENT' and amount >= 0)
    ),
    constraint ck_member_points_operation_documents check (
        (operation_type = 'SALE_EARN'
            and source_document_id is not null
            and original_document_id is null
            and source_checkpoint is not null)
        or (operation_type in ('RETURN_REVERSAL', 'RETURN_CANCELLATION')
            and source_document_id is not null
            and original_document_id is not null
            and source_document_id <> original_document_id
            and source_checkpoint is null)
        or (operation_type = 'SALE_CANCELLATION'
            and source_document_id is null
            and original_document_id is not null
            and source_checkpoint is null)
        or (operation_type = 'MANUAL_ADJUSTMENT'
            and source_document_id is null
            and original_document_id is null
            and source_checkpoint is null)
    ),
    constraint ck_member_points_operation_payload_hash check (
        payload_hash ~ '^[0-9a-f]{64}$'
    )
);

create index ix_member_points_operation_member
    on member_points_operation(miembro_id, occurred_at, operation_id);

create unique index uk_member_points_operation_sale_checkpoint
    on member_points_operation(source_document_id, source_checkpoint)
    where operation_type = 'SALE_EARN';

create unique index uk_member_points_operation_return_source
    on member_points_operation(source_document_id)
    where operation_type = 'RETURN_REVERSAL';

create unique index uk_member_points_operation_sale_cancellation
    on member_points_operation(original_document_id)
    where operation_type = 'SALE_CANCELLATION';

create unique index uk_member_points_operation_return_cancellation
    on member_points_operation(source_document_id)
    where operation_type = 'RETURN_CANCELLATION';
