create table configuracion_interfaz_terminal (
    id uuid primary key,
    terminal_id uuid not null,
    sale_mode varchar(16) not null default 'KEYBOARD',
    version bigint not null default 0,
    constraint fk_configuracion_interfaz_terminal_terminal
        foreign key (terminal_id) references terminal (id),
    constraint uq_configuracion_interfaz_terminal_terminal unique (terminal_id),
    constraint ck_configuracion_interfaz_terminal_sale_mode
        check (sale_mode in ('KEYBOARD', 'TOUCH'))
);
