# Factura de entrada: conflicto al volver a guardar el borrador

## Trabajo realizado

- Corregido el reemplazo de líneas de un borrador existente: se vacían las líneas anteriores y se ejecuta `flush` antes de insertar las nuevas, dentro de la misma transacción.
- Se mantiene el índice único `(entrada_id, posicion)`, la comprobación de estado BORRADOR y el bloqueo existente del documento.
- La validación de empresa/tienda, proveedor, productos y procedencia/snapshot de Excel sigue realizándose antes del borrado.
- La pantalla diferencia un fallo al guardar el borrador de un fallo posterior al enviar su confirmación. Un conflicto de guardado muestra que no se envió la confirmación y recomienda conservar la ventana abierta. Textos ES/EN/ZH, sin exponer SQL.
- No se ha consultado, modificado ni confirmado la factura del usuario. Tras la autorización posterior, se ha reiniciado únicamente el backend habitual para activar la corrección, sin cerrar las aplicaciones.

## Archivos modificados

1. `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInput.java`
2. `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputService.java`
3. `backend/src/test/java/com/tpverp/backend/inventory/WarehouseInputServiceTest.java`
4. `backend/src/test/java/com/tpverp/backend/inventory/WarehouseInputDraftPostgreSqlTest.java` (nuevo)
5. `frontend/packages/app-common/src/components/WarehouseDocumentDialog.tsx`
6. `frontend/packages/app-common/src/components/WarehouseDocumentDialog.test.tsx`
7. `frontend/packages/app-common/src/i18n/MessagesEs.ts`
8. `frontend/packages/app-common/src/i18n/MessagesEn.ts`
9. `frontend/packages/app-common/src/i18n/MessagesZh.ts`
10. `docs/warehouse-input-draft-save-fix-2026-09-08.md` (este informe)

Los archivos que ya tenían cambios se han editado de forma acotada, preservando el trabajo anterior. No se ha tocado `backend-saas/docker-compose.dev.yml`.

## Decisiones técnicas

- La causa observada en el backend habitual fue SQLSTATE 23505, índice `ux_entrada_almacen_linea_posicion`, posición 1: el guardado intentaba insertar líneas nuevas antes de eliminar las que ocupaban esas posiciones.
- Se adopta el reemplazo ordenado autorizado, sin cambiar el modelo, las migraciones ni el contrato API. `flush` ejecuta SQL, pero no confirma la transacción: un error posterior restaura también lo borrado.
- La comprobación del snapshot de Excel se mantiene antes de vaciar la colección para no validar contra un documento incompleto.
- La guía de revisión ERP ha orientado las comprobaciones de rollback, inmutabilidad de documentos confirmados, aislamiento y repetición de la confirmación.
- Se ha compilado el backend en una copia aislada bajo `output/warehouse-draft-backend`; no se sobrescribieron las clases usadas por el proceso habitual. El PostgreSQL temporal usó un contenedor dedicado con una base `tpv_erp_warehouse_draft_test` y esquemas aleatorios. El test rechaza nombres de base ajenos a `tpv_erp_*test`.

## Validaciones realizadas

- **Reproducción antes de corregir:** la prueba real PostgreSQL de guardar un borrador existente reprodujo el mismo índice/posición duplicados. Durante la preparación inicial hubo errores de datos de prueba (dirección fiscal y código de proveedor), corregidos respetando las restricciones del esquema; no eran errores del documento del usuario.
- **Backend: 41 pruebas correctas, ninguna omitida**, incluyendo tres con PostgreSQL 18 real:
  - crear, guardar dos veces, confirmar; comprobación del stock y de que una segunda confirmación no añade movimientos;
  - rechazo de modificaciones sobre el documento ya confirmado;
  - reordenar, reducir y ampliar hasta 394 líneas, guardarlas de nuevo y conservar los precios de tres decimales y posiciones únicas;
  - fallo forzado exactamente en la segunda inserción, verificando recuperación íntegra de cabecera, versión y líneas originales, incluidos sus identificadores; reintento correcto después del fallo.
- Pruebas unitarias adicionales comprueban el borrado antes del guardado final y que un snapshot Excel ausente/obsoleto no llegue al `flush`.
- **Frontend: 50 pruebas correctas**. Se comprueba Guardar/Confirmar, conservación del borrador al fallar, ausencia de llamada a `/confirm` si falla el guardado y orden PUT → POST cuando el guardado sí termina.
- Compilaciones desktop VENTA/GESTIÓN y presupuesto del bundle correctos. VENTA mantiene avisos de proximidad al límite: JS 772.222/800.000 bytes; CSS 450.119/460.000 bytes.
- `git -c core.safecrlf=false diff --check`: correcto.
- Clases del backend habitual conservan su fecha de compilación de las 12:02; el proceso original seguía escuchando en 8080 al cerrar la validación.

### Activación autorizada posterior

- Reinicio completado el 08/09/2026 a las 14:01:30 (Atlantic/Canary), conservando `dev,fiscal-dev,saas-dev` y el comando habitual `mvnw.cmd spring-boot:run`.
- Proceso anterior verificado por puerto, clase principal y classpath antes de detenerlo. Nuevo backend Java PID 80540, escuchando en `127.0.0.1:8080` (identificador de esta ejecución, no reutilizar sin verificar).
- `/actuator/health` y `/actuator/health/readiness`: ambos `UP`.
- PostgreSQL sigue siendo la BD habitual `tpv_erp_dev`, versión 18.3. Flyway verifica esquema V241 actualizado y no ejecuta migraciones.
- Los SHA-256 de las clases `WarehouseInput` y `WarehouseInputService` recompiladas coinciden con los artefactos que pasaron las pruebas aisladas.
- No se ha enviado ninguna petición de guardado o confirmación de la factura. No se han cerrado ni recargado sus ventanas.
- Registros: `output/warehouse-draft-runtime-20260908.out.log` y `.err.log`.

Evidencias locales ignoradas por Git: `output/warehouse-draft-before.log`, `warehouse-draft-after.log`, `warehouse-draft-frontend.log`, `warehouse-draft-build.log` y `warehouse-draft-bundle.log`.

## Riesgos detectados

- La corrección está activa tras el reinicio autorizado. La confirmación del documento real queda a cargo del usuario; no se da por confirmada por el mero hecho de que el backend responda.
- No se ha ejecutado una confirmación sobre el documento real ni una prueba HTTP completa contra una aplicación de prueba: la persistencia y transacciones se han comprobado con el servicio real y PostgreSQL, y la secuencia de peticiones con pruebas de la pantalla.
- Las pruebas de repetición son secuenciales; no se certifica aquí una carrera entre dos terminales.
- El mensaje de incertidumbre se conserva cuando la petición de confirmación sí fue enviada. No se presupone su resultado ante un fallo de respuesta.

## Trabajo pendiente

- Reintento de la factura real por parte del usuario una vez activo el backend nuevo. No se automatiza su confirmación.

## Próximos pasos recomendados

El servicio ya está disponible: volver a guardar y confirmar desde la misma ventana que contiene el borrador. Si aparece otro error, conservar la ventana y revisar ese intento antes de repetirlo.
