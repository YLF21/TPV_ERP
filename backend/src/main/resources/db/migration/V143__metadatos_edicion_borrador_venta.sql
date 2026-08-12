alter table documento_linea
    add column nombre_temporal_override boolean not null default false,
    add column precio_temporal_override boolean not null default false;
