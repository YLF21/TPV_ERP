insert into saas_company(id, name, tax_id, taxpayer_type, tax_regime, created_at)
select 'aaaaaaaa-0000-4000-8000-000000000001', 'Empresa Retail Norte', 'A10000001', 'SOCIEDAD', 'IVA', current_timestamp
where not exists (select 1 from saas_company where id = 'aaaaaaaa-0000-4000-8000-000000000001');

insert into saas_company(id, name, tax_id, taxpayer_type, tax_regime, created_at)
select 'aaaaaaaa-0000-4000-8000-000000000002', 'Empresa Hosteleria Centro', 'B20000002', 'SOCIEDAD', 'IVA', current_timestamp
where not exists (select 1 from saas_company where id = 'aaaaaaaa-0000-4000-8000-000000000002');

insert into saas_company(id, name, tax_id, taxpayer_type, tax_regime, created_at)
select 'aaaaaaaa-0000-4000-8000-000000000003', 'Autonomo Servicios Sur', 'C30000003', 'AUTONOMO', 'IGIC', current_timestamp
where not exists (select 1 from saas_company where id = 'aaaaaaaa-0000-4000-8000-000000000003');

insert into saas_store(id, company_id, code, name, created_at)
select 'bbbbbbbb-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'NORTE-01', 'Tienda Norte Principal', current_timestamp
where not exists (select 1 from saas_store where id = 'bbbbbbbb-0000-4000-8000-000000000001');

insert into saas_store(id, company_id, code, name, created_at)
select 'bbbbbbbb-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000002', 'CENTRO-01', 'Restaurante Centro', current_timestamp
where not exists (select 1 from saas_store where id = 'bbbbbbbb-0000-4000-8000-000000000002');

insert into saas_store(id, company_id, code, name, created_at)
select 'bbbbbbbb-0000-4000-8000-000000000003', 'aaaaaaaa-0000-4000-8000-000000000003', 'SUR-01', 'Oficina Sur', current_timestamp
where not exists (select 1 from saas_store where id = 'bbbbbbbb-0000-4000-8000-000000000003');

insert into saas_license(id, company_id, reference, valid_until, status, max_windows, max_pda, created_at)
select 'cccccccc-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'LIC-A10000001-NORTE01', timestamp '2027-07-18 10:00:00', 'VALIDA', 5, 2, current_timestamp
where not exists (select 1 from saas_license where id = 'cccccccc-0000-4000-8000-000000000001');

insert into saas_license(id, company_id, reference, valid_until, status, max_windows, max_pda, created_at)
select 'cccccccc-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000002', 'LIC-B20000002-CENTRO01', timestamp '2026-08-17 10:00:00', 'VALIDA', 3, 1, current_timestamp
where not exists (select 1 from saas_license where id = 'cccccccc-0000-4000-8000-000000000002');

insert into saas_license(id, company_id, reference, valid_until, status, max_windows, max_pda, created_at)
select 'cccccccc-0000-4000-8000-000000000003', 'aaaaaaaa-0000-4000-8000-000000000003', 'LIC-C30000003-SUR01', timestamp '2027-01-19 10:00:00', 'BLOQUEADA_MANUAL', 2, 0, current_timestamp
where not exists (select 1 from saas_license where id = 'cccccccc-0000-4000-8000-000000000003');

insert into saas_pairing_code(id, company_id, store_id, license_id, code, consumed_at, expires_at, created_at)
select 'dddddddd-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'bbbbbbbb-0000-4000-8000-000000000001', 'cccccccc-0000-4000-8000-000000000001', 'TPV-DEMO-N1', null, timestamp '2026-07-30 10:00:00', current_timestamp
where not exists (select 1 from saas_pairing_code where id = 'dddddddd-0000-4000-8000-000000000001');

insert into saas_pairing_code(id, company_id, store_id, license_id, code, consumed_at, expires_at, created_at)
select 'dddddddd-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000002', 'bbbbbbbb-0000-4000-8000-000000000002', 'cccccccc-0000-4000-8000-000000000002', 'TPV-DEMO-C1', null, timestamp '2026-07-30 10:00:00', current_timestamp
where not exists (select 1 from saas_pairing_code where id = 'dddddddd-0000-4000-8000-000000000002');

insert into saas_installation(id, company_id, store_id, license_id, installation_id, installation_reference, installation_public_key, token_hash, linked_at, last_validated_at, app_version, operating_system, terminal_name, last_ip)
select 'eeeeeeee-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'bbbbbbbb-0000-4000-8000-000000000001', 'cccccccc-0000-4000-8000-000000000001', 'eeeeeeee-1000-4000-8000-000000000001', 'TPV-NORTE-CAJA-01', null, 'demo-token-norte', timestamp '2026-07-13 10:00:00', timestamp '2026-07-23 09:00:00', '1.4.0', 'Windows 11', 'Caja Norte 01', '192.168.1.20'
where not exists (select 1 from saas_installation where id = 'eeeeeeee-0000-4000-8000-000000000001');

insert into saas_installation(id, company_id, store_id, license_id, installation_id, installation_reference, installation_public_key, token_hash, linked_at, last_validated_at, app_version, operating_system, terminal_name, last_ip)
select 'eeeeeeee-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000002', 'bbbbbbbb-0000-4000-8000-000000000002', 'cccccccc-0000-4000-8000-000000000002', 'eeeeeeee-1000-4000-8000-000000000002', 'TPV-CENTRO-BARRA-01', null, 'demo-token-centro', timestamp '2026-07-17 10:00:00', timestamp '2026-07-21 04:00:00', '1.3.8', 'Windows 10', 'Barra Centro', '192.168.2.15'
where not exists (select 1 from saas_installation where id = 'eeeeeeee-0000-4000-8000-000000000002');

insert into saas_company_operations(company_id, plan_name, billing_status, renewal_date, monthly_price, support_status, contact_name, contact_email, notes, updated_at)
select 'aaaaaaaa-0000-4000-8000-000000000001', 'PRO', 'PAGADO', timestamp '2026-08-22 10:00:00', '89.00', 'NORMAL', 'Laura Norte', 'laura.norte@example.com', 'Cliente demo con actividad estable.', current_timestamp
where not exists (select 1 from saas_company_operations where company_id = 'aaaaaaaa-0000-4000-8000-000000000001');

insert into saas_company_operations(company_id, plan_name, billing_status, renewal_date, monthly_price, support_status, contact_name, contact_email, notes, updated_at)
select 'aaaaaaaa-0000-4000-8000-000000000002', 'STANDARD', 'PENDIENTE', timestamp '2026-07-31 10:00:00', '49.00', 'ATENCION', 'Mario Centro', 'mario.centro@example.com', 'Renovacion cercana y terminal sin validar.', current_timestamp
where not exists (select 1 from saas_company_operations where company_id = 'aaaaaaaa-0000-4000-8000-000000000002');

insert into saas_company_operations(company_id, plan_name, billing_status, renewal_date, monthly_price, support_status, contact_name, contact_email, notes, updated_at)
select 'aaaaaaaa-0000-4000-8000-000000000003', 'BASIC', 'IMPAGADO', timestamp '2026-07-11 10:00:00', '29.00', 'BLOQUEADO', 'Ana Sur', 'ana.sur@example.com', 'Cliente demo bloqueado para revisar flujo de riesgo.', current_timestamp
where not exists (select 1 from saas_company_operations where company_id = 'aaaaaaaa-0000-4000-8000-000000000003');

insert into saas_erp_customer(id, company_id, code, name, tax_id, email, phone, active, created_at)
select '10000000-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'CLI-001', 'Cliente Mostrador Norte', '11111111A', 'cliente.norte@example.com', '600100100', true, current_timestamp
where not exists (select 1 from saas_erp_customer where id = '10000000-0000-4000-8000-000000000001');

insert into saas_erp_customer(id, company_id, code, name, tax_id, email, phone, active, created_at)
select '10000000-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000002', 'CLI-REST-01', 'Mesa Empresa Centro', '22222222B', 'cliente.centro@example.com', '600200200', true, current_timestamp
where not exists (select 1 from saas_erp_customer where id = '10000000-0000-4000-8000-000000000002');

insert into saas_erp_product(id, company_id, sku, name, category, price, tax_rate, min_stock, active, created_at)
select '20000000-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'SKU-CAF-001', 'Cafe Premium 250g', 'Alimentacion', '4.95', '10.00', '12.00', true, current_timestamp
where not exists (select 1 from saas_erp_product where id = '20000000-0000-4000-8000-000000000001');

insert into saas_erp_product(id, company_id, sku, name, category, price, tax_rate, min_stock, active, created_at)
select '20000000-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000002', 'SKU-MENU-001', 'Menu diario', 'Restaurante', '12.50', '10.00', '0.00', true, current_timestamp
where not exists (select 1 from saas_erp_product where id = '20000000-0000-4000-8000-000000000002');

insert into saas_erp_supplier(id, company_id, code, name, tax_id, email, phone, active, created_at)
select '30000000-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'PROV-001', 'Distribuciones Norte', '33333333C', 'proveedor.norte@example.com', '600300300', true, current_timestamp
where not exists (select 1 from saas_erp_supplier where id = '30000000-0000-4000-8000-000000000001');

insert into saas_erp_supplier(id, company_id, code, name, tax_id, email, phone, active, created_at)
select '30000000-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000002', 'PROV-REST-01', 'Proveedor Hosteleria Centro', '44444444D', 'proveedor.centro@example.com', '600400400', true, current_timestamp
where not exists (select 1 from saas_erp_supplier where id = '30000000-0000-4000-8000-000000000002');

insert into saas_erp_warehouse(id, company_id, code, name, address, active, created_at)
select '40000000-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'ALM-NORTE', 'Almacen Norte', 'Calle Norte 1', true, current_timestamp
where not exists (select 1 from saas_erp_warehouse where id = '40000000-0000-4000-8000-000000000001');

insert into saas_erp_warehouse(id, company_id, code, name, address, active, created_at)
select '40000000-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000002', 'ALM-CENTRO', 'Almacen Centro', 'Plaza Centro 2', true, current_timestamp
where not exists (select 1 from saas_erp_warehouse where id = '40000000-0000-4000-8000-000000000002');

insert into saas_billing_invoice(id, company_id, number, concept, amount, currency, status, issued_at, due_at, created_at)
select '50000000-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'SAAS-2026-0001', 'Suscripcion PRO julio', '89.00', 'EUR', 'PAGADA', timestamp '2026-07-11 10:00:00', timestamp '2026-08-10 10:00:00', current_timestamp
where not exists (select 1 from saas_billing_invoice where id = '50000000-0000-4000-8000-000000000001');

insert into saas_billing_invoice(id, company_id, number, concept, amount, currency, status, issued_at, due_at, created_at)
select '50000000-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000002', 'SAAS-2026-0002', 'Suscripcion STANDARD julio', '49.00', 'EUR', 'PENDIENTE', timestamp '2026-07-18 10:00:00', timestamp '2026-07-28 10:00:00', current_timestamp
where not exists (select 1 from saas_billing_invoice where id = '50000000-0000-4000-8000-000000000002');

insert into saas_billing_payment(id, invoice_id, amount, method, reference, paid_at, created_at)
select '60000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000001', '89.00', 'TRANSFERENCIA', 'TR-DEMO-001', timestamp '2026-07-13 10:00:00', current_timestamp
where not exists (select 1 from saas_billing_payment where id = '60000000-0000-4000-8000-000000000001');

insert into saas_subscription(id, company_id, plan_name, status, billing_cycle, amount, currency, started_at, next_billing_at, cancelled_at, created_at)
select '70000000-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'PRO', 'ACTIVA', 'MENSUAL', '89.00', 'EUR', timestamp '2026-04-24 10:00:00', timestamp '2026-08-12 10:00:00', null, current_timestamp
where not exists (select 1 from saas_subscription where id = '70000000-0000-4000-8000-000000000001');

insert into saas_subscription(id, company_id, plan_name, status, billing_cycle, amount, currency, started_at, next_billing_at, cancelled_at, created_at)
select '70000000-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000002', 'STANDARD', 'ACTIVA', 'MENSUAL', '49.00', 'EUR', timestamp '2026-06-08 10:00:00', timestamp '2026-07-31 10:00:00', null, current_timestamp
where not exists (select 1 from saas_subscription where id = '70000000-0000-4000-8000-000000000002');

insert into saas_subscription(id, company_id, plan_name, status, billing_cycle, amount, currency, started_at, next_billing_at, cancelled_at, created_at)
select '70000000-0000-4000-8000-000000000003', 'aaaaaaaa-0000-4000-8000-000000000003', 'BASIC', 'SUSPENDIDA', 'MENSUAL', '29.00', 'EUR', timestamp '2026-03-25 10:00:00', timestamp '2026-07-11 10:00:00', null, current_timestamp
where not exists (select 1 from saas_subscription where id = '70000000-0000-4000-8000-000000000003');

insert into saas_sales_document(id, company_id, store_id, document_number, customer_code, total, currency, status, issued_at, created_at)
select '80000000-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'bbbbbbbb-0000-4000-8000-000000000001', 'VTA-NORTE-0001', 'CLI-001', '38.70', 'EUR', 'CONFIRMADA', timestamp '2026-07-20 10:00:00', current_timestamp
where not exists (select 1 from saas_sales_document where id = '80000000-0000-4000-8000-000000000001');

insert into saas_sales_document(id, company_id, store_id, document_number, customer_code, total, currency, status, issued_at, created_at)
select '80000000-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000002', 'bbbbbbbb-0000-4000-8000-000000000002', 'VTA-CENTRO-0001', 'CLI-REST-01', '126.40', 'EUR', 'CONFIRMADA', timestamp '2026-07-22 10:00:00', current_timestamp
where not exists (select 1 from saas_sales_document where id = '80000000-0000-4000-8000-000000000002');

insert into saas_inventory_movement(id, company_id, warehouse_code, product_sku, movement_type, quantity, reason, moved_at, created_at)
select '90000000-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'ALM-NORTE', 'SKU-CAF-001', 'ENTRADA', '40.00', 'Stock inicial demo', timestamp '2026-07-16 10:00:00', current_timestamp
where not exists (select 1 from saas_inventory_movement where id = '90000000-0000-4000-8000-000000000001');

insert into saas_inventory_movement(id, company_id, warehouse_code, product_sku, movement_type, quantity, reason, moved_at, created_at)
select '90000000-0000-4000-8000-000000000002', 'aaaaaaaa-0000-4000-8000-000000000001', 'ALM-NORTE', 'SKU-CAF-001', 'VENTA', '6.00', 'Venta mostrador', timestamp '2026-07-20 10:00:00', current_timestamp
where not exists (select 1 from saas_inventory_movement where id = '90000000-0000-4000-8000-000000000002');

insert into saas_inventory_movement(id, company_id, warehouse_code, product_sku, movement_type, quantity, reason, moved_at, created_at)
select '90000000-0000-4000-8000-000000000003', 'aaaaaaaa-0000-4000-8000-000000000002', 'ALM-CENTRO', 'SKU-MENU-001', 'ENTRADA', '15.00', 'Preparacion cocina demo', timestamp '2026-07-21 10:00:00', current_timestamp
where not exists (select 1 from saas_inventory_movement where id = '90000000-0000-4000-8000-000000000003');

insert into saas_integration_endpoint(id, company_id, name, integration_type, status, target_url, api_key, last_sync_at, created_at)
select 'a0000000-0000-4000-8000-000000000001', 'aaaaaaaa-0000-4000-8000-000000000001', 'Webhook ventas Norte', 'WEBHOOK', 'ACTIVA', 'https://example.com/webhooks/sales', 'demo-key-norte-001', timestamp '2026-07-23 08:00:00', current_timestamp
where not exists (select 1 from saas_integration_endpoint where id = 'a0000000-0000-4000-8000-000000000001');

insert into saas_integration_endpoint(id, company_id, name, integration_type, status, target_url, api_key, last_sync_at, created_at)
select 'a0000000-0000-4000-8000-000000000002', null, 'Exportacion contable global', 'ACCOUNTING_EXPORT', 'PAUSADA', 'https://example.com/accounting/export', 'demo-key-global-002', null, current_timestamp
where not exists (select 1 from saas_integration_endpoint where id = 'a0000000-0000-4000-8000-000000000002');
