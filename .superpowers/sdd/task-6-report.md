# Task 6 report — Dynamic provider configuration frontend

## Resultado

Estado: `DONE_WITH_CONCERNS`

Se completó la configuración dinámica de los cuatro proveedores permitidos, modos
MANUAL/SIMULATED/LIVE, capacidades, esquema de campos no sensibles, estado de
emparejamiento, prueba de conexión independiente del modo simulado, i18n y
protección write-only de secretos.

## Auditoría inicial

- El selector estaba hardcodeado a `REDSYS_TPV_PC` aunque las reglas ya permitían
  REDSYS_TPV_PC, PAYTEF, PAYCOMET y GLOBAL_PAYMENTS.
- El frontend representaba SIMULATED/LIVE con un checkbox `testMode`; no existía
  un modo LIVE explícito ni el diagnóstico `SDK no instalado`.
- La prueba de conexión estaba deshabilitada fuera de test mode, pese a ser una
  capacidad independiente.
- No se mostraban capacidades, esquemas de campos ni estado de pairing.
- Backend filtraba parámetros sensibles, pero no exponía descriptores.
- La vista backend devolvía `secretReference`; esto contradecía el requisito
  write-only, aunque fuese opaca.
- Se preservó compatibilidad frontend con respuestas antiguas rules-only mediante
  descriptores derivados localmente.

## RED / GREEN (TDD)

RED observado:

- Vitest: 2 fallos esperados: PAYTEF/PAYCOMET/GLOBAL_PAYMENTS ausentes y pairing
  ausente/conexión indebidamente dependiente del test mode.
- JUnit: descriptor `providerDescriptors` ausente (tamaño 0 frente a 4).
- Vitest de compatibilidad: respuesta legacy no mostraba Redsys y deshabilitaba
  conexión.

GREEN:

- Los proveedores se generan desde descriptores filtrados por reglas.
- LIVE aparece deshabilitado con `SDK_NOT_INSTALLED` / texto localizado.
- Todos los simuladores exponen outcomes; Redsys además declara el campo no
  sensible `ip` para LIVE.
- Capacidades declaradas incluyen pairing, connection test, charge, query, void,
  refund, receipt y reconciliation.
- Pairing se presenta como estado independiente; connection test depende de su
  capacidad, no de SIMULATED.
- Los payloads no incluyen entradas secretas ni referencias rehidratadas.
- La vista conserva `secretConfigured` y `secretVersion`, pero elimina
  `secretReference` del contrato de lectura.

## Archivos

- `backend/src/main/java/com/tpverp/backend/terminal/TerminalPaymentConfigurationView.java`
- `backend/src/test/java/com/tpverp/backend/terminal/TerminalPaymentConfigurationViewTest.java`
- `frontend/packages/app-common/src/components/PaymentTerminalSettings.tsx`
- `frontend/packages/app-common/src/components/PaymentTerminalSettings.test.tsx`
- `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- `frontend/packages/app-common/src/i18n/MessagesZh.ts`

## Verificación final

- `npm.cmd test -- --run packages/app-common/src/components/PaymentTerminalSettings.test.tsx packages/app-common/src/i18n/messages.test.ts`
  - 2 archivos, 16 tests, 0 fallos.
- `npm.cmd run build --workspace @tpverp/app-gestion`
  - TypeScript y Vite, exit 0.
- `npm.cmd run build --workspace @tpverp/app-venta`
  - TypeScript y Vite, exit 0.
- `mvn.cmd '-Dtest=TerminalPaymentConfigurationViewTest,TerminalPaymentConfigurationServiceTest,TerminalPaymentConfigurationControllerContractTest' test`
  - 21 tests, 0 fallos, `BUILD SUCCESS`.
- `git diff --check`
  - Sin errores (solo avisos de normalización LF/CRLF de Git en Windows).

## Auto-revisión

- Seguridad: no se serializa `secretReference`; los esquemas publicados solo
  contienen `simulatorOutcome` e `ip`; `secretInput` se ignora deliberadamente.
- Compatibilidad: permanecen `cardMode` y `testMode` en el wire contract, y el
  frontend acepta la respuesta legacy sin `providerDescriptors`.
- Alcance: no se tocaron gateways ni operaciones de cobro de Tasks 1–5.
- i18n: nuevas claves presentes en es/en/zh; test de paridad aprobado.
- Concern: el estado backend de pairing es actualmente `NOT_PAIRED`/`NOT_REQUIRED`
  derivado de la configuración. No existe aún persistencia de pairing por terminal
  en el modelo previo ni un endpoint de configuración para cambiarlo; cuando esa
  infraestructura se añada, la UI ya consume `pairingStatus` sin acoplarlo a
  `testMode`.

## Commit

Implementación: `38d5421af08b80b67966d79f9e20a0dc44b2af0c`

El informe se versiona en un commit documental posterior para poder registrar el
hash inmutable del commit de implementación.

## Corrección de hallazgos posterior

Se eliminaron las limitaciones registradas en la primera entrega:

- Pairing real: `POST /pairing` inicia la operación y `GET /pairing/{pairingId}`
  consulta el estado usando `CardTerminalGateway.pair` y `pairingStatus`, la
  configuración efectiva y un `PaymentTerminalGatewayContext`. Los simuladores
  responden `PAIRED`; LIVE conserva `SDK_NOT_INSTALLED` tipado. No fue necesaria
  una migración ni persistencia duplicada: el identificador de pairing es el
  contrato existente y el gateway es la fuente del estado.
- La UI ofrece acciones separadas de emparejar, consultar pairing y probar
  conexión, ninguna condicionada por `testMode`.
- `fieldSchemas` se renderiza genéricamente por `type`, `required`, `label`,
  `options` y `modes`; incluye `ip` cuando el descriptor/modo lo declara.
- El payload usa `providerParameters`, elimina campos fuera de modo y cualquier
  clave con semántica de secreto; nunca envía `secretInput` ni rehidrata secretos.
- Los modos se generan exclusivamente desde `supportedModes`; el motivo se toma
  de `unavailableReason` y se localiza.
- Los descriptores se intersectan defensivamente con
  `rules.allowedPaymentTerminalProviders`; hay regresión para respuestas
  backend inconsistentes.

### RED adicional

- Frontend: tres fallos esperados por campo IP ausente, descriptor no intersectado
  y APIs de pairing inexistentes; regresión adicional confirmó que
  `simulatorOutcome` contaminaba un payload LIVE.
- Backend: compilación de tests falló de forma esperada porque aún no existían
  `pair`, `pairingStatus`, `PairingRequest` ni endpoints.

### GREEN y verificación adicional

- Frontend focal + i18n: 2 archivos, 20 tests, 0 fallos.
- Backend configuración/controlador/vista/gateways: 72 tests, 0 fallos,
  `BUILD SUCCESS`.
- `@tpverp/app-gestion`: TypeScript + Vite, exit 0.
- `@tpverp/app-venta`: TypeScript + Vite, exit 0.

Commit correctivo: se registra en el handoff final, ya que este informe forma
parte del mismo commit adicional.

## Cierre de hallazgos restantes

- La vista rehidrata todos los parámetros permitidos explícitamente por
  `fieldSchemas`: `simulatorOutcome` e `ip` para Redsys. El test roundtrip de
  entidad/configuración/vista comprueba `ip`; referencias y claves con semántica
  de secreto siguen ausentes.
- Se eliminó la rama visual especial de `simulatorOutcome`. El único renderer de
  campos decide por `type` (`TEXT`/`SELECT`) y respeta `label` literal o clave
  i18n, `required`, `options` y `modes` para cualquier schema.
- `normalizeTerminalMode` se aplica al cargar y cambiar proveedor. Si el modo
  actual no pertenece a `supportedModes`, usa el primero declarado y sincroniza
  el booleano wire-compatible `testMode`; el payload elimina parámetros
  incompatibles.
- Pairing aplica `PAIRING` capability en SIMULATED y LIVE. Los stubs LIVE anuncian
  esa capability porque soportan el contrato devolviendo el resultado tipado
  `SDK_NOT_INSTALLED`; un gateway LIVE que no la anuncie es rechazado antes de
  invocarlo.

### RED final

- Frontend: label hardcodeado para outcome y helper de normalización inexistente.
- Backend: `ip` ausente de la vista, pairing LIVE sin capability no rechazado y
  stub LIVE sin capability declarada.

### Verificación final

- Frontend focal + i18n: 22 tests, 0 fallos.
- Backend config/controller/view/gateway: 74 tests, 0 fallos, `BUILD SUCCESS`.
- `@tpverp/app-gestion`: TypeScript + Vite, exit 0.
- `@tpverp/app-venta`: TypeScript + Vite, exit 0.
- `git diff --check`: sin errores; únicamente avisos LF/CRLF de Windows.
