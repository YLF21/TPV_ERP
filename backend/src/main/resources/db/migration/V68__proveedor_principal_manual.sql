update producto_proveedor
set principal = false,
    version = version + 1
where principal = true;
