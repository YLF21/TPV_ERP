alter table payment_terminal_operation
    add column if not exists refund_line_selection text;

alter table documento_linea
    add column if not exists original_document_line_id uuid;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'fk_documento_linea_refund_origin'
          and conrelid = 'documento_linea'::regclass
    ) then
        alter table documento_linea
            add constraint fk_documento_linea_refund_origin
            foreign key (original_document_line_id) references documento_linea(id);
    end if;
end
$$;

create index if not exists idx_documento_linea_refund_origin
    on documento_linea(original_document_line_id)
    where original_document_line_id is not null;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'chk_payment_terminal_refund_line_selection'
          and conrelid = 'payment_terminal_operation'::regclass
    ) then
        alter table payment_terminal_operation
            add constraint chk_payment_terminal_refund_line_selection
            check (refund_line_selection is null or operation_type = 'REFUND');
    end if;
end
$$;
