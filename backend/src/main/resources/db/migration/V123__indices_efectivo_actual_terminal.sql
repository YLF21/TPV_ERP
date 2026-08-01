create index if not exists movimiento_caja_sesion_fecha_idx
    on movimiento_caja(sesion_caja_id, creado_en)
    where sesion_caja_id is not null;

create index if not exists movimiento_caja_terminal_entre_sesiones_fecha_idx
    on movimiento_caja(terminal_id, creado_en)
    where sesion_caja_id is null;
