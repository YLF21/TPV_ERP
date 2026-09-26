# Simulación local de incidencias SaaS

Empresa: **DEMO - Laboratorio de incidencias**.

Abre http://127.0.0.1:5175/#/failures, filtra por esa empresa o busca `DEMO`, y pulsa **Aplicar filtros**.
Los datos son ficticios y están guardados en la base de datos local. No se han provocado errores en ninguna tienda existente.

| Caso | Práctica | Resultado simulado |
| --- | --- | --- |
| DEMO 01 - Sincronizacion recuperable | Abre Detalle, escribe un motivo y solicita el reintento remoto. | La instalación ficticia confirma la entrega y envía la recuperación. Pulsa Actualizar para ver el fallo resuelto. |
| DEMO 02 - Sincronizacion requiere asistencia | Solicita el reintento; al fallar, crea el seguimiento manual. | El reintento devuelve RETRY_FAILED. Practica la asistencia remota y, si no basta, la intervención presencial. |
| DEMO 03 - Impresora sin respuesta | Abre el ticket de soporte ya vinculado desde Detalle. | Practica iniciar asistencia remota, registrar diagnóstico, solicitar visita presencial y resolver el ticket. |

Para las intervenciones puedes registrar notas ficticias como «DEMO: comprobar cola de impresión» o «DEMO: cable de impresora sustituido».
Deja vacío el ID de TeamViewer: esta práctica no necesita conectarse a ningún equipo.
Cerrar el ticket registra la intervención; no cambia por sí solo el error de aplicación reportado por la tienda.

## Arranque

Con el backend SaaS local funcionando:

```powershell
node tools/saas-incident-demo.mjs start
```

El arranque reutiliza la misma empresa y los mismos casos; no reinicia resultados ni crea duplicados.
El proceso permanece activo hasta ocho horas y solo consume órdenes de sus dos instalaciones ficticias de sincronización.
Cada resultado tarda unos segundos y se refleja con el sondeo del panel.

También existen los modos `setup` (preparar sin proceso residente) y `once` (procesar una sola ronda).
El modo normal `start` es suficiente para continuar la práctica después de cerrar o reiniciar el equipo.

Estado local y tokens ficticios: `.codex-runtime/saas-incident-demo/state.json` (excluido de Git).
Log: `.codex-runtime/saas-incident-demo/worker.log`.
Para detener el simulador, termina únicamente el PID mostrado por el comando de arranque.
Las incidencias permanecen disponibles; sin el simulador, los reintentos quedarán pendientes hasta caducar.

## Alcance y verificación

El script está limitado a http://127.0.0.1:8090 y a la base local tpv_erp_saas en 127.0.0.1:5432.
La preparación inserta una empresa, una licencia de prueba, tres tiendas y tres instalaciones propias en una transacción.
Los fallos y su recuperación se publican por la API autenticada STORE_FAILURE; las órdenes y resultados usan la API real de reparaciones.
No envía ventas ni documentos fiscales, no contacta con servicios externos y no toca instalaciones existentes.

Se verificaron el resultado SUCCEEDED, el resultado FAILED y la recepción de la recuperación.
DEMO 01 se volvió a abrir con una revisión superior para dejar los tres casos abiertos para practicar.
El historial conserva los reintentos de verificación, identificados con el motivo DEMO.
