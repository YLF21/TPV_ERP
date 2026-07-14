# Global fix report — simulator shutdown cleanup

## Hallazgos corregidos

### Critical — terminalidad de `CANCELLED`

- `SalePaymentSession.finalizeWith(...)` exige ahora estado `COVERED`, además de cobertura contable.
- `SalePaymentSessionService.finalizeSession(...)` repite el invariant inmediatamente después de adquirir/cargar la sesión bloqueada y antes de consultar operaciones, métodos, snapshots o crear documentos.
- Una sesión `COVERED` descartada por simulador permanece `CANCELLED`, sin `ticketId` ni `ticketNumber`.
- La regresión de servicio verifica cero interacciones con `DocumentService`, snapshot, métodos y operaciones, y cero guardados al rechazar la finalización.
- El orden inverso continúa protegido por la prueba existente: una sesión ya `FINALIZED` no puede descartarse como simulación y conserva el primer ticket.

### Important — limpieza frontend single-flight

- `SalePaymentCheckout` mantiene una única promesa de limpieza en vuelo por `sessionId`.
- La limpieza automática de entrada, `prepareLogout()` y `prepareApplicationClose()` comparten esa misma promesa y emiten una sola petición.
- Todos los consumidores observan el mismo resultado: solo una respuesta `CANCELLED` produce `READY`; fallo o respuesta no terminal producen `BLOCKED`.
- El guard se elimina en `finally` únicamente si sigue apuntando a la promesa que termina. Esto permite un reintento explícito posterior sin provocar loops del efecto automático.
- Las pruebas usan una respuesta diferida real para solapar las tres rutas y comprueban tanto éxito como fallo seguido de reintento.

## Evidencia TDD

- Backend RED: 2 fallos esperados; el dominio permitía finalizar y el servicio alcanzaba dependencias de creación de documento.
- Backend GREEN focalizado inicial: 26/26.
- Frontend RED: el caso exitoso emitió 3 descartes; el caso fallido no compartió el resultado y devolvió éxito desde peticiones posteriores.
- Frontend GREEN focalizado inicial: 69/69.

## Verificación final

- Frontend focalizado (`SaleScreen.paymentCleanup.integration`, `SalePaymentCheckout`, `SessionTopControls`, `SaleScreen`): **116/116**, 4 archivos, exit 0.
- Backend focalizado (`SalePaymentSessionTest`, `SalePaymentSessionServiceTest`, `SalePaymentSessionControllerContractTest`, `ApiExceptionHandlerTest`): **41/41**, `BUILD SUCCESS`.
- `git diff --check`: sin errores (solo avisos informativos de conversión LF/CRLF de Git).
