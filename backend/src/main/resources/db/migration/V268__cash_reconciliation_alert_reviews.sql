-- Reviews annotate reconciliation evidence without changing its financial values.
create table intento_arqueo_caja_revision (
    intento_id uuid primary key references intento_arqueo_caja(id),
    revisada_por uuid not null references usuario(id),
    revisada_en timestamptz not null,
    comentario varchar(1000) not null check (length(trim(comentario)) > 0),
    version bigint not null default 1 check (version > 0)
);

create index intento_arqueo_caja_alerta_idx on intento_arqueo_caja(creado_en desc, id desc)
    where descuadre <> 0;
