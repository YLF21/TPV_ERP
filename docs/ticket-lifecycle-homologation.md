# Homologación del ciclo de vida del ticket

Esta lista separa las comprobaciones automatizables de las que necesitan una
caja, impresora y datáfono físicos. Ninguna integración debe activarse en
producción solo porque el simulador haya terminado correctamente.

## Cobertura automatizada

| Flujo | Evidencia automática | Resultado exigido |
|---|---|---|
| Venta en efectivo | E2E de APP VENTA | Un ticket confirmado, un único movimiento de caja y carrito limpio |
| Tarjeta aprobada | Simulador de terminal | Un único cobro y recibo asociado |
| Tarjeta rechazada | Simulador de terminal | Venta recuperable y ningún cobro confirmado |
| Timeout y consulta | Simulador `TIMEOUT` + `QUERY` | La misma operación se recupera sin cobrar dos veces |
| Conversión a factura | E2E + prueba de servicio | Un único F3, sin duplicar stock ni pagos; repetir la petición devuelve la misma factura |
| Anulación | Pruebas de servicio | Compensaciones idempotentes, caja/vale/tarjeta coherentes y evento fiscal |
| Devolución parcial | Pruebas de servicio | Se respetan cantidad, serie, promoción histórica y medio de pago original |
| Promociones | Integración de documentos | Cotización autoritativa, cupón, miembro y Buy-X-Pay-Y no confían en importes del cliente |
| Trazabilidad | Timeline operacional | Operador, autorizador, terminal y documento relacionado, sin contraseñas ni PAN |
| Interfaz táctil | Playwright a 1280 × 800 | Sin desbordamiento, nombres accesibles y acciones de al menos 44 px |

Comandos reproducibles:

```powershell
cd frontend
npm test
npm run build
npm run check:bundle
npm run test:e2e -- app-venta-operational-flows.spec.ts app-venta-responsive-accessibility.spec.ts

cd ..\backend
.\mvnw.cmd -Dtest=DocumentServiceTest,TicketReturnServiceTest,TicketCancellationServiceTest,DocumentPromotionIntegrationTest,DocumentFiscalIntegrationTest test
```

## Matriz manual de caja

Registrar para cada ejecución: fecha, tienda, terminal, versión de APP VENTA,
versión backend, modelo/firmware del datáfono, impresora, operador y resultado.

- [ ] Venta en efectivo exacto; apertura de cajón una sola vez.
- [ ] Venta con cambio; importe entregado y cambio coinciden con el ticket.
- [ ] Tarjeta aprobada; referencia del recibo coincide con el proveedor.
- [ ] Tarjeta rechazada; se puede elegir otro medio sin recrear la venta.
- [ ] Desconexión después de autorizar; `Consultar estado` recupera la operación.
- [ ] Doble toque en pagar/convertir/anular; solo existe una operación durable.
- [ ] Devolución parcial con tarjeta y efectivo; no supera lo pagado originalmente.
- [ ] Anulación con vale consumido/generado; saldo y reimpresión son coherentes.
- [ ] Conversión a factura; el QR y registro F3 sustituyen al ticket sin segunda salida de stock.
- [ ] Impresora sin papel después de confirmar; reimpresión sin repetir el movimiento económico.
- [ ] Reinicio de frontend durante un cobro incierto; la sesión reaparece y permite consultar.
- [ ] Reinicio de backend; readiness vuelve a `UP` y no aparecen operaciones huérfanas.
- [ ] Pantalla táctil real en horizontal; lectura, desplazamiento y teclado numérico sin pulsaciones accidentales.

## Criterios de bloqueo

No desplegar si ocurre cualquiera de estos casos:

1. Una repetición crea un segundo pago, documento fiscal, movimiento de caja o
   movimiento de stock.
2. Una operación incierta invita a iniciar otro cobro en vez de consultar la
   operación existente.
3. Auditoría o logs contienen contraseña, PAN, CVV, PIN o pista de tarjeta.
4. La factura resultante no conserva la relación con el ticket o duplica sus
   pagos/stock.
5. El estado mostrado al operador no distingue entre procesando, completado,
   rechazado, incierto y pendiente de revisión.

## Evidencias que deben archivarse

- Informe de Playwright y salida de Maven.
- Capturas de ticket, factura, devolución y anulación.
- Recibos del datáfono con datos sensibles enmascarados.
- Timeline operacional y `X-Request-ID` de cada fallo ensayado.
- Acta del adquirente para el modelo y firmware exactos del datáfono.
- Resultado de conciliación del lote antes de habilitar LIVE.
