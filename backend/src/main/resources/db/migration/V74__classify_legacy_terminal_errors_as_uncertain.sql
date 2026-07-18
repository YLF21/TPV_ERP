-- Los ERROR anteriores no distinguian fallo final de efecto incierto. Al
-- migrarlos se conserva el criterio seguro: exigir consulta/reconciliacion.
update payment_terminal_operation
set completed_at = null
where status = 'ERROR';
