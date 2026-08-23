-- The software identity is part of the frozen fiscal evidence. It may be
-- superseded by a new version, but an existing identity cannot be rewritten.
create trigger tr_version_sistema_fiscal_inmutable
before update or delete on version_sistema_fiscal
for each row execute function impedir_mutacion_fiscal();
