create index if not exists sesion_caja_tienda_terminal_cierre_idx
    on sesion_caja(tienda_id, terminal_id, cerrada_en desc, id)
    where estado = 'CERRADA';

create index if not exists sesion_caja_tienda_usuario_cierre_idx
    on sesion_caja(tienda_id, usuario_cierre_id, cerrada_en desc, id)
    where estado = 'CERRADA';
