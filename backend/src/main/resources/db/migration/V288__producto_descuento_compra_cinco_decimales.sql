-- Widen precision without rounding existing values or changing the 0..100 constraint.
alter table producto
    alter column descuento_compra_porcentaje type numeric(8,5);
