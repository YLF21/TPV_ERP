alter table cliente rename column code_client to client_id;
alter table cliente rename column code_member to member_id;
alter table proveedor rename column code_supplier to supplier_id;
alter table comercial rename column code_commercial to commercial_id;

alter table cliente rename constraint ck_cliente_code_client to ck_cliente_client_id;
alter table cliente rename constraint ck_cliente_code_member to ck_cliente_member_id;
alter table cliente rename constraint ux_cliente_empresa_code_client to ux_cliente_empresa_client_id;
alter table cliente rename constraint ux_cliente_empresa_code_member to ux_cliente_empresa_member_id;
alter table proveedor rename constraint ck_proveedor_code_supplier to ck_proveedor_supplier_id;
alter table proveedor rename constraint ux_proveedor_empresa_code_supplier to ux_proveedor_empresa_supplier_id;
alter table comercial rename constraint ck_comercial_code_commercial to ck_comercial_commercial_id;
alter table comercial rename constraint ux_comercial_empresa_code_commercial to ux_comercial_empresa_commercial_id;
