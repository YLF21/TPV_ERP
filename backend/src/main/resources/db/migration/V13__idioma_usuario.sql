alter table usuario
    add column idioma varchar(2) not null default 'ES';

alter table usuario
    add constraint ck_usuario_idioma
    check (idioma in ('ES', 'EN', 'ZH'));
