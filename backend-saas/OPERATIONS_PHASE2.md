# Capacidades operativas SaaS locales

## Implementado sin proveedores externos

- Políticas `BASIC`, `STANDARD`, `PREMIUM` y `ENTERPRISE` con límites efectivos para usuarios tenant, tiendas, licencias, maestros y eventos sync diarios. Los límites se aplican en servicio y mediante triggers PostgreSQL.
- Alertas dinámicas para licencias próximas, facturas vencidas con saldo, tickets urgentes abiertos y tiendas sin validación durante 48 horas.
- Importación y exportación CSV RFC-compatible básica para `customers`, `products`, `suppliers` y `warehouses`; búsqueda paginada con un máximo de 200 registros por página.
- Base fiscal de factura: serie, ejercicio, régimen IVA/IGIC, base, tipo y cuota. Las facturas creadas con el contrato anterior se registran con tipo y cuota cero para no inferir impuestos inexistentes.
- Conciliación mediante una interfaz de adaptador y adaptadores seguros `MANUAL_BANK` y `MANUAL_GATEWAY`. Las referencias externas son idempotentes por empresa/proveedor y un pago solo puede asociarse dentro de su empresa con importe y moneda coincidentes.
- Outbox bidireccional persistente para desarrollos de entrega posteriores. La entrada sync existente conserva hash, idempotencia, conflictos, reintento de proyección y estado observable.

## API añadida

- `GET /api/v1/admin/companies/{companyId}/plan-usage`
- `GET /api/v1/admin/invoices/{invoiceId}/fiscal`
- `GET|POST /api/v1/admin/companies/{companyId}/reconciliations`
- `GET|POST /api/v1/tenant/erp/{resource}/csv`
- `GET /api/v1/tenant/erp/{resource}/search?q=&page=&size=`

## Dependencias externas pendientes

No se realizan llamadas a bancos, Stripe, Redsys, AEAT ni a servicios de correo. Integrarlas requiere credenciales, selección expresa de proveedor, gestión de secretos, firma/verificación de webhooks y un contrato de reintentos propio. Hasta entonces la conciliación es manual y auditable.

No se añadió PDF porque el backend no incluye una biblioteca PDF y no debe fabricarse un documento fiscal con un generador incompleto. La salida fiscal estructurada permite incorporar más adelante una biblioteca aprobada y una plantilla legal validada.
