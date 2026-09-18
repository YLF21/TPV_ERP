# Cierre productivo VERI*FACTU

Línea base: `4448cc32` (main), revisión de 18/09/2026. Rama de trabajo:
`codex/verifactu-cierre-productivo`. Este expediente distingue implementación,
pruebas técnicas y autorización de producción; no es una declaración responsable.

## Alcance de entrega

- Producción: `VERIFACTU_ONLY`, tienda local con envío asíncrono AEAT y control
  central SaaS de licencia/política/estado. No trasladar la facturación al SaaS.
- DEV: conservar el laboratorio dual y los transportes de prueba aislados.
- Preservar registros, relaciones, secuencias, XML y snapshots de impresión.
- No habilitar producción ni reescribir históricos como parte del desarrollo.
- No ampliar a NO VERI*FACTU productivo: la validación temporal de firmas
  históricas y su evidencia integral siguen siendo un alcance independiente.

## Matriz de aceptación

| Bloque | Resultado de implementación | Aceptación pendiente |
| --- | --- | --- |
| Subsanación | ALTA original determinista; versión actual en nuevos registros; clave idempotente, conflictos HTTP 409 y bloqueo por documento; cierre de ancestros; recuperación de intento en gestión | Aceptación operativa completa con AEAT TEST e impresión |
| Recuperación AEAT | V249 conserva incidencia por empresa/instalación/entorno; el sobre indica incidencia sin modificar XML congelado; lotes compatibles y control de leases/pacing | Intercambio real y recuperación ante caída en staging |
| Estado y licencia | TPV usa modalidad efectiva; gestión limita transiciones a la capacidad del producto; se conserva la activación obligatoria existente en emisión | Ensayo integrado del artefacto final y política SaaS real |
| SaaS | Fallos de sincronización visibles por código/contador sin datos fiscales ni credenciales; se conserva el aislamiento y la proyección existentes | Ensayo local/SaaS de desconexión y recuperación |
| Publicación | Identidad/esquema V249 coherentes; defaults desde fuentes únicas; NSIS opt-in, SHA-256 y control de firmante/sello de tiempo | Checkout limpio, PDF real, runtime instalado coherente, generación y verificación del instalador firmado |
| Windows | Registro detenido → ACL → arranque validado; permisos de secretos/exportaciones coherentes; preflight estricto | VM limpia: instalación, actualización, certificado, exportación, reinicio |
| Recuperación | CLI cargada desde el mismo fat JAR verificado, sin depender de target/classes; preflight de identidad | Restauración completa en otro equipo, journal y continuidad fiscal |
| Volumen | Medición reproducible con 1 millón de registros; páginas acotadas y sin XML/snapshot; A/B documentado | SLA y carga HTTP/concurrente; optimización selectiva si el volumen real la exige |
| AEAT TEST | Certificado válido, intercambio real, ACK, evidencia redactada vinculada al artefacto final | Externo, no ejecutado |
| Declaración/piloto | Datos y firma del productor, PDF de la versión distribuida, acceso en app; aprobación documentada del piloto | Externo, no ejecutado |

## Secuencia de trabajo

1. Corregir recorridos fiscales y añadir regresiones, antes de optimizaciones.
2. Unificar estado/UI y eliminar contradicciones de publicación e instalación.
3. Ejecutar pruebas focalizadas y después las verificaciones integradas del repo.
4. Preparar un artefacto limpio con declaración real y evidencias reproducibles.
5. Ensayar AEAT TEST, impresión física, instalación y recuperación en staging.
6. Aprobar piloto antes de habilitar remisión de registros reales.

Las pruebas con bases de datos usarán exclusivamente bases aisladas. Los cambios
Flyway son aditivos; no modificar migraciones aplicadas. No convertir una prueba
omitida por falta de infraestructura en un resultado satisfactorio.

## Decisiones que reducen complejidad

- Reutilizar la cola y el flujo por ámbito existentes. La incidencia es un dato
  durable adicional, no una segunda cola ni un nuevo transporte.
- Conservar `secuencia`, XML y QR históricos. Una subsanación crea otro registro;
  nunca sustituye el artefacto impreso original.
- Mantener producción `VERIFACTU_ONLY` y DEV dual. No introducir de forma parcial
  NO VERI*FACTU productivo ni centralizar el detalle fiscal en SaaS.
- No añadir V250 ni índices duplicados: V210/V211 ya cubren fecha y prefijo.
  La materialización global de candidatos ensayada empeora búsquedas amplias;
  se descarta. Véase [medición y A/B](../tools/performance/fiscal-keyset-1m-20260918.md).
- Separar registro, permisos y arranque del servicio Windows permite resolver su
  SID antes de otorgar permisos, sin arrancar con configuración incompleta.

## Evidencias técnicas de esta revisión

- Frontend: 19 archivos / 129 pruebas de la batería afectada, más una regresión
  de cierre del panel antes del POST (130 pruebas únicas). Builds APP VENTA y
  APP GESTIÓN correctos; gestión reconstruida tras el último ajuste.
- Windows/release: 30 pruebas Pester (15 despliegue, 10 ACL, 5 restore)
  y ocho parseos de scripts. La última regresión ejecuta el recorrido de
  directorios en Windows PowerShell 5.1 y detectó/corrigió una combinación
  inválida de parámetros de `Split-Path` en el aprovisionador de certificados.
  Usan fixtures/mocks: no acreditan un servicio real.
  Smoke del JAR: `PropertiesLauncher` carga `OfflineRestoreCli` y devuelve
  `Falta --backup`, sin iniciar Spring ni restaurar una base.
- Backend: `mvnw.cmd --batch-mode verify` ejecutó 4.171 pruebas, con un fallo:
  `DocumentSyncPublisherPostgreSqlTest` esperaba V248 tras añadir V249. Corregida
  esa expectativa y la protección del último intento `DEFECTUOSO`, se ejecutó
  `mvnw.cmd --batch-mode -Dtest=FiscalCorrectionServiceTest,FiscalRecordServiceTest,FiscalChainPostgreSqlTest,DocumentSyncPublisherPostgreSqlTest verify`:
  **63 pruebas, cero fallos/errores/omisiones, BUILD SUCCESS**, con recompilación y
  empaquetado del fat JAR DEV. No se repitió innecesariamente toda la batería.
  La primera ejecución completa no fue verde; los últimos resultados por clase
  incluyen su fallo corregido y cuatro regresiones adicionales de subsanación.
  Consolidación de ambos logs: **745 clases, 4.175 pruebas, cero fallos/errores y
  siete omisiones**. Es un resultado acumulado, no una segunda ejecución completa.
- Siete pruebas de la batería general quedaron omitidas por la plataforma:
  seis sobre enlaces simbólicos y una sobre permisos POSIX. No se han relajado
  sus condiciones ni se consideran verificadas; requieren el entorno adecuado.
- PostgreSQL de pruebas: contenedor exclusivo en loopback 55449, bases
  `verifactu_closure_test` y `tpv_erp_test`, credenciales temporales. No se han
  conectado estas pruebas a las bases habituales de la tienda ni de SaaS.
- Laboratorio: arranque real de backend + APP VENTA, health, Flyway V249, login,
  desbloqueo del grupo Fiscal y APIs del simulador. Se actualizó el script para
  seguir la reautenticación de gestión (no omitirla) y respetar
  `-EvidenceDirectory`. El cambio REJECTED → ACCEPTED y un dispatch de cola
  vacía **no son una venta E2E ni una prueba de remisión AEAT**.
  El ensayo final terminó correctamente y guardó `sandbox-api-proof.json` en
  `target/verifactu-dev-proof/cierre/sandbox/`. Cuatro pruebas Pester adicionales
  cubren la carpeta de evidencia y el orden de desbloqueo: **34 pruebas Pester
  únicas** contando despliegue/ACL/restore/laboratorio.
- Volumen: 57 planes SQL, 1.000.000 registros sintéticos, 51 filas por página.
  Medianas de primera/página profunda: 0,311/0,305 ms; filtro antiguo por prefijo:
  278,196 ms. Son tiempos SQL con caché, no latencia de extremo a extremo.

Los logs locales permanecen en `backend/target/verifactu-closure-*.log`; la
evidencia de volumen está en `target/verifactu-dev-proof/cierre/`. No publicar
logs íntegros sin revisar/redactar su contenido. El entorno instalado de Electron
es 43.4.1 y el lock declara 44.2.0: el empaquetado exige sincronizarlos, sin
bloquear el launcher DEV ni descargar dependencias en esta revisión.

Tras la comprobación se retiró únicamente el contenedor efímero creado para
esta tarea y sus bases sintéticas. Los procesos del laboratorio y sus secretos
temporales fueron eliminados por el launcher. Las evidencias permanecen; los
datos de volumen pueden regenerarse con los scripts documentados. No se han
detenido los servicios habituales de la tienda o SaaS.

## Condiciones de salida a producción

1. Integrar los cambios revisados y construir desde checkout limpio; mantener
   hash/versión/esquema y secuencias de release coherentes.
2. Incorporar declaración real de la versión, datos del productor y revisión
   fiscal final. No sustituirla por el PDF de fixtures.
3. Ejecutar AEAT TEST con certificado válido y autorización, conservar ACK y
   evidencia redactada vinculada al artefacto candidato.
4. Generar los instaladores con runtime bloqueado por lock y firma Authenticode
   autorizada; verificar instalación/actualización en Windows limpio.
5. Probar corte de red, reinicio, cola pendiente, impresión/reimpresión física,
   rotación de certificado y recuperación desde copia en otro equipo.
6. Ensayar licencia/activación obligatoria y sincronización SaaS, aprobar el
   piloto y habilitar producción mediante el procedimiento controlado existente.

La publicación del código mediante commit/push no activa producción ni autoriza
remisiones reales.
Los puntos externos anteriores siguen abiertos; esta entrega no acredita por sí
sola cumplimiento normativo ni preparación completa para producción.

## Fuentes oficiales

- [Orden HAC/1177/2024, artículos 15–17 y 20–21](https://www.boe.es/buscar/act.php?id=BOE-A-2024-22138).
- [AEAT: modalidades VERI*FACTU, continuidad ante incidencias y cambios de modalidad](https://sede.agenciatributaria.gob.es/Sede/iva/sistemas-informaticos-facturacion-verifactu/preguntas-frecuentes/sistemas-verifactu.html).
- [AEAT: declaración responsable por versión](https://sede.agenciatributaria.gob.es/Sede/iva/sistemas-informaticos-facturacion-verifactu/cuestiones-generales/certificacion-sistemas-informaticos_.html).

Authenticode/NSIS son controles de distribución del producto; no constituyen por
sí mismos una exigencia fiscal ni una homologación de AEAT.
