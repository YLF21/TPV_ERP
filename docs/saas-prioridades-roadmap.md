# Roadmap SaaS ERP

## Estado actual implementado

El modulo SaaS ya incluye:

- Panel administrador en React, TypeScript y Vite.
- Backend SaaS Spring Boot con PostgreSQL y migraciones Flyway.
- Gestion de empresas, tiendas, licencias, instalaciones y codigos de enlace.
- Validacion y vinculacion de instalaciones TPV.
- Sincronizacion de eventos de ventas, stock y caja.
- Usuarios admin con roles y permisos.
- Auditoria administrativa.
- Soporte con tickets, comentarios y notificaciones.
- Pulso operativo por empresa.
- Facturacion basica con facturas y pagos.
- Portal cliente con licencias, tiendas, tickets, facturas y maestros.
- Maestros ERP: clientes, productos, proveedores y almacenes.

## Cambios de esta fase

- Nuevo endpoint admin `GET /api/v1/admin/status` para conocer version logica del SaaS, migracion esperada y modulos activos.
- Maestros ERP ahora permiten desactivar registros desde backend:
  - `DELETE /api/v1/admin/companies/{companyId}/erp/customers/{id}`
  - `DELETE /api/v1/admin/companies/{companyId}/erp/products/{id}`
  - `DELETE /api/v1/admin/companies/{companyId}/erp/suppliers/{id}`
  - `DELETE /api/v1/admin/companies/{companyId}/erp/warehouses/{id}`
- El frontend SaaS muestra el estado/version del backend en Soporte cuando el endpoint esta disponible.
- El frontend SaaS permite desactivar maestros activos desde la tabla de Maestros para usuarios con permiso `MANAGE_ERP_MASTERS`.

## Prioridad 1: base de produccion

Pendiente recomendado:

- Sustituir HTTP Basic por autenticacion con token, expiracion y cierre de sesion real.
- Recuperacion/cambio obligatorio de password inicial.
- Pantalla dedicada de estado SaaS con version de frontend, version de backend y estado de migraciones.
- Tests de aislamiento tenant para garantizar que un cliente no accede a datos de otra empresa.
- Endurecer errores cuando el backend arrancado no coincide con la version del frontend.

## Prioridad 2: operacion SaaS real

Pendiente recomendado:

- Planes y suscripciones configurables.
- Limites por plan: usuarios, tiendas, terminales, licencias, maestros y volumen de sincronizacion.
- Alertas automaticas por factura vencida, licencia proxima a caducar, tienda sin validar y ticket urgente.
- Importacion/exportacion CSV o Excel para maestros ERP.
- Busqueda, filtros y paginacion en facturacion, soporte y maestros.

## Prioridad 3: integraciones y ERP completo

Pendiente recomendado:

- Facturas PDF y numeracion fiscal por serie/ejercicio.
- Desglose fiscal con base imponible, IVA/IGIC, descuentos y total.
- Pasarela de pago o registro bancario conciliable.
- Sincronizacion bidireccional de maestros SaaS hacia TPV local.
- Resolucion de conflictos de sincronizacion.
- Observabilidad completa: logs, metricas, trazas, cola de eventos pendientes y panel de errores.

## Orden recomendado

1. Autenticacion token y version/migracion visible.
2. Maestros editables, importables y paginados.
3. Facturacion fiscal con PDF.
4. Suscripciones y limites por plan.
5. Sincronizacion bidireccional y observabilidad.
