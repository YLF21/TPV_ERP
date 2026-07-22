# Preparación productiva de APP VENTA

Esta guía valida ventas, crédito de clientes, promociones, pagos simulados y devoluciones sin necesitar el SDK ni un datáfono físico.

## Pruebas reproducibles

Desde PowerShell, en la raíz del repositorio:

```powershell
.\tools\test-app-venta-readiness.ps1
```

Para incluir también toda la batería de APP VENTA:

```powershell
.\tools\test-app-venta-readiness.ps1 -IncludeFrontend
```

Para ejecutar además el recorrido E2E real contra PostgreSQL, backend y APP VENTA:

```powershell
.\tools\test-app-venta-readiness.ps1 -IncludeFrontend -IncludeE2E
```

El escenario E2E comprueba los probes públicos, la protección de métricas, una cotización
autoritaria con un producto real y la consulta aislada de la cuenta corriente de un cliente.

La batería backend incluye los contratos comunes de los simuladores Redsys TPV-PC, PAYTEF, PAYCOMET y Global Payments. Comprueba cobro aprobado/denegado, timeout y consulta posterior, anulación, devolución parcial, recibo y conciliación, además de los flujos de crédito, promociones y devolución.

## Health y métricas

Los probes no revelan detalles a usuarios anónimos:

```text
GET /actuator/health/liveness
GET /actuator/health/readiness
```

Las métricas requieren una sesión con rol `ADMIN`:

```text
GET /actuator/metrics/tpv.business.operations
GET /actuator/metrics/tpv.business.backlog
GET /actuator/prometheus
```

Métricas principales:

- `tpv_business_operations_total`: operaciones por dominio, operación, método y resultado.
- `tpv_business_operation_duration_seconds`: latencia de las operaciones críticas.
- `tpv_business_operation_failures_total`: errores funcionales y técnicos.
- `tpv_business_backlog`: operaciones de datáfono sin resolver, documentos vencidos y promociones activas.
- `tpv_business_monitor_collection_failures_total`: fallos al recopilar el estado operativo.
- `tpv_business_monitor_last_success`: instante Unix de la última lectura correcta.

Los intervalos pueden ajustarse con `TPV_MONITORING_INITIAL_DELAY_MS` y `TPV_MONITORING_INTERVAL_MS`.

## Auditoría

Las mutaciones críticas generan registros en la auditoría existente, asociados a tienda y usuario cuando existe sesión. Se registran el nombre estable de la operación, método, código HTTP, duración e identificador seguro `X-Request-ID`. No se registran cuerpos, contraseñas, códigos de vale, datos de tarjeta ni identificadores incluidos en la URL.

Entre los eventos están creación/anulación/devolución de tickets, reservas y finalización de pago, creación y cobro de deuda de cliente y cambios de promociones o cupones.

## Alertas recomendadas

- `tpv_business_backlog{kind="terminal_unresolved"} > 0` durante 5 minutos: revisar el estado en el proveedor antes de reintentar.
- `tpv_business_backlog{kind="receivables_overdue"}` con crecimiento continuo: revisar límites y gestión de cobro.
- incremento de `tpv_business_operation_failures_total`: correlacionar con la auditoría y `X-Request-ID`.
- ausencia de actualización de `tpv_business_monitor_last_success` durante dos intervalos: comprobar PostgreSQL y `/actuator/health/readiness`.

## Recuperación operativa

1. No repetir un cobro con un identificador nuevo si el anterior quedó incierto.
2. Consultar primero la operación existente; los simuladores y conectores usan idempotencia.
3. Si readiness falla, detener nuevas ventas, comprobar PostgreSQL y conservar los identificadores de sesión/operación.
4. Restaurar desde una copia verificada solo después de detener el backend y conservar los logs de auditoría.
5. Tras recuperar, ejecutar la batería de readiness y comprobar que los backlogs vuelven a valores esperados.

La homologación final con tarjeta continúa necesitando el SDK oficial, credenciales de prueba y un datáfono físico. El resto de la preparación es ejecutable con simulador.
