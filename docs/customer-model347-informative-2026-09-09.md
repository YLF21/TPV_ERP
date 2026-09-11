# Modelo 347: resumen informativo del cliente

## Alcance confirmado

- Disponible en Facturas del historial del cliente, tanto APP VENTA como APP GESTIÓN.
- Ventana pequeña para elegir ejercicio; generar PDF A4 mediante Jasper, nunca HTML.
- Identidad de empresa emisora y cliente (código, nombre, NIF y domicilio), cuatro
  trimestres con períodos e importes y total anual. Trimestres vacíos muestran cero.
- Todas las tiendas de la misma empresa emisora presentes en la BD del backend;
  no solo la tienda abierta. No introduce sincronización ni consulta a sistemas SaaS.
- Para el cliente seleccionado y el ejercicio completo: FACTURA_VENTA y
  RECTIFICATIVA_VENTA en CONFIRMADO/PENDIENTE/PARCIAL/PAGADO. Excluye borradores,
  anulados, tickets y albaranes. Conserva el signo de cada total persistido.
- Se agrupa por `documento.fecha`, no por fecha de pago/confirmación. No aplica los
  filtros, estado ni límite de carga del historial. No exige importe mínimo.
- No se presenta ninguna declaración a la AEAT. El PDF identifica expresamente
  que es informativo y describe el criterio acordado de cálculo.

## Contraste normativo y límites

Se han consultado las instrucciones oficiales de la AEAT el 09/09/2026:

- [Importe de las operaciones](https://sede.agenciatributaria.gob.es/Sede/todas-gestiones/impuestos-tasas/declaraciones-informativas/modelo-347-decla_____racion-anual-operaciones-personas_/importe-operaciones.html): impuestos y ajustes de operaciones.
- [Imputación temporal](https://sede.agenciatributaria.gob.es/Sede/todas-gestiones/impuestos-tasas/declaraciones-informativas/modelo-347-decla_____racion-anual-operaciones-personas_/imputacion-temporal.html): el modelo oficial utiliza el período de anotación registral y contempla reglas particulares.

El criterio de fecha del documento y alcance de facturas fue confirmado para este
resumen informativo. No certifica obligación de declarar ni determina exclusiones,
criterio de caja, anticipos, operaciones especiales o datos de tiendas que no estén
disponibles en esta BD. No agrupa fichas de clientes diferentes por coincidencia de NIF.
Tampoco sustituye los datos fiscales históricos ni reemite facturas: las identidades
del resumen proceden de las fichas actuales, los importes de los documentos guardados.

## Implementación

- GET `/api/v1/customer-document-reports/{customerId}/model-347.pdf?year=2026&locale=es`.
  UUID y año/idioma validados; año 1..9998 para formar el límite exclusivo del año
  siguiente, idiomas ES/EN/ZH. Respuesta PDF con `Cache-Control: no-store`.
- `CustomerModel347Controller` y `CustomerModel347Service`: permisos existentes de
  consulta de facturas (INVOICES_READ, VENTA, GESTION_VENTAS o ADMIN) comprobados
  en backend. Empresa obtenida del contexto autenticado, cliente validado en empresa.
- `CustomerModel347Repository`: única consulta SQL agregada por trimestre, enlazando
  tienda y cliente con la empresa. SUM de `documento.total` numérico, sin joins a
  pagos/líneas, sin paginación ni carga de documentos completos. Se reutiliza el índice
  `ix_documento_cliente`; no se añade ninguna migración.
- Servicio en transacción de solo lectura, REPEATABLE_READ. Completa cuatro
  trimestres y suma con BigDecimal. No actualiza cobros, precios ni documentos.
- `CustomerModel347JasperRenderer` y `MODELO_347_RESUMEN_A4.jrxml`, empaquetada como
  recurso propio. No cambia las plantillas de facturas ni el catálogo configurado.
- `CustomerModel347Dialog`: foco/teclado, validación de año, cancelación de solicitud,
  bloqueo de doble pulsación, mensajes ES/EN/ZH y guardado directo de bytes PDF.
  Disponible aunque el historial esté vacío o tenga filtros pendientes.
- Nombre: `código-nombre-Modelo 347-año.pdf`; caracteres de archivo saneados.

## Validación

- Backend: 44 casos verdes en ejecuciones focalizadas (20 de contrato HTTP,
  13 de servicio/permisos, 6 con PostgreSQL 17.6 aislado y 5 de Jasper). Después
  se repitió únicamente el caso de domicilios largos para generar su vista previa.
- PostgreSQL: tiendas de la misma empresa, aislamiento por empresa/cliente,
  tipos/estados, límites de trimestre y año, bisiestos, signos y ceros, ausencia de
  multiplicación por pagos/líneas y más de 500 documentos. Contenedor temporal
  detenido al terminar; no se escribieron datos de negocio para estas pruebas.
- Frontend: 66 casos verdes (diálogos, integración con directorio y guard de idiomas).
  Compilaciones VENTA y GESTIÓN y presupuestos de tamaño correctos. VENTA está
  próxima al límite JS: 793.874 / 800.000 bytes; no se ha ampliado el presupuesto.
- Navegador con cliente y respuestas sintéticos: abrir desde Facturas vacías,
  selección de año, Enter, generación/descarga, nombre del PDF, Escape y revisión
  a 1392×844 y 1024×768 en ES/ZH. El SHA-256 de la descarga coincide con el PDF
  real generado por Jasper usado como respuesta de prueba. No es una prueba
  autenticada de extremo a extremo con facturas reales.
- PDFs ES/ZH y con nombres/domicilios largos renderizados e inspeccionados:
  A4, cuatro trimestres y total, sin recortes ni solapes. Mezcla de chino, tildes,
  eñes y datos con caracteres de marcado comprobada. Los textos se escapan y
  usan formato styledText de Jasper; no existe una ruta HTML.
- La fuente PDF CJK estándar STSong no está incrustada. Los casos anteriores
  se han comprobado con el renderizador disponible, no en una impresora física.
- `git diff --check` correcto. Sin dependencias ni migraciones nuevas. Cambio
  ajeno de `backend-saas/docker-compose.dev.yml` preservado.
- Backend reiniciado con `dev,fiscal-dev,saas-dev`: PID 73368, salud UP y endpoint
  nuevo registrado. Flyway validó 237 migraciones, esquema V241 sin cambios.
  Registros de arranque: `%LOCALAPPDATA%/Temp/tpv-backend-model347-20260909-192848`.
- No se han realizado commit, push ni presentación a la AEAT.
