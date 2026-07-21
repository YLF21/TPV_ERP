alter table documento
    add column if not exists return_request_id uuid;

alter table payment_terminal_operation
    add column if not exists document_managed_externally boolean not null default false;

create unique index if not exists uk_documento_return_request_id
    on documento(return_request_id)
    where return_request_id is not null;

create table if not exists documento_devolucion_pago (
    id uuid primary key,
    documento_devolucion_id uuid not null,
    tipo varchar(16) not null,
    importe numeric(19,2) not null,
    documento_pago_original_id uuid,
    terminal_operacion_id uuid,
    referencia varchar(128),
    creado_en timestamptz not null,
    constraint fk_documento_devolucion_pago_documento
        foreign key (documento_devolucion_id) references documento(id),
    constraint fk_documento_devolucion_pago_original
        foreign key (documento_pago_original_id) references documento_pago(id),
    constraint fk_documento_devolucion_pago_terminal
        foreign key (terminal_operacion_id) references payment_terminal_operation(id),
    constraint chk_documento_devolucion_pago_tipo
        check (tipo in ('CASH', 'CARD')),
    constraint chk_documento_devolucion_pago_importe
        check (importe > 0),
    constraint chk_documento_devolucion_pago_terminal
        check ((tipo = 'CARD' and terminal_operacion_id is not null)
            or (tipo = 'CASH' and terminal_operacion_id is null))
);

create unique index if not exists uk_documento_devolucion_pago_terminal
    on documento_devolucion_pago(terminal_operacion_id)
    where terminal_operacion_id is not null;

create index if not exists idx_documento_devolucion_pago_documento
    on documento_devolucion_pago(documento_devolucion_id);
