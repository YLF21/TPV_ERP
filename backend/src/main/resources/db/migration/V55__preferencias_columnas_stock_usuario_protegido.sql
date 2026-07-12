alter table preferencia_columnas_stock
    drop constraint preferencia_columnas_stock_usuario_tienda_fk;

alter table preferencia_columnas_stock
    add constraint preferencia_columnas_stock_usuario_fk
        foreign key (usuario_id)
        references usuario(id) on delete cascade;
