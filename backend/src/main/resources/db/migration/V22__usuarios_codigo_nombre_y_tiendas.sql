create sequence if not exists usuario_user_id_seq;

alter table usuario
    add column user_id varchar(8),
    add column user_name varchar(128);

update usuario
set user_id = 'E-' || lpad(nextval('usuario_user_id_seq')::text, 6, '0'),
    user_name = nombre
where user_id is null;

alter table usuario
    alter column user_id set not null,
    alter column user_name set not null,
    alter column user_id set default ('E-' || lpad(nextval('usuario_user_id_seq')::text, 6, '0')),
    add constraint usuario_user_id_uq unique (user_id),
    add constraint usuario_user_id_ck check (user_id ~ '^E-[0-9]{6}$'),
    add constraint usuario_user_name_ck check (char_length(trim(user_name)) > 0);

create table usuario_tienda (
    usuario_id uuid not null references usuario(id) on delete cascade,
    tienda_id uuid not null references tienda(id) on delete cascade,
    primary key (usuario_id, tienda_id)
);

insert into usuario_tienda (usuario_id, tienda_id)
select id, tienda_id
from usuario
on conflict do nothing;

create index ix_usuario_tienda_tienda on usuario_tienda(tienda_id);
