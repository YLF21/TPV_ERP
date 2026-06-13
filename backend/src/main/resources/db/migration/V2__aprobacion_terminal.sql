alter table terminal
    add column aprobada boolean not null default true;

create index ix_terminal_solicitudes_pendientes
    on terminal(tienda_id, aprobada)
    where not aprobada;
