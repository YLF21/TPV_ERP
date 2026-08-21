# Fase 1: contrato backend de fidelización y cobro

**Estado:** plan preparado, pendiente de implementación

**Spec de referencia:** `docs/superpowers/specs/2026-08-17-member-loyalty-management-and-checkout-design.md`

## Hallazgos del código actual

- Ya existe cotización autoritativa para APP VENTA en `AuthoritativeSaleQuoteController` y `PosCashService`.
- `DocumentService` ya centraliza creación de tickets, pagos, deudas, fiscalidad y fidelización.
- Ya existe `MemberDocumentLoyaltySettlement` con campos para importe elegible, importe cobrado, puntos aplicados a deuda, saldo generado, saldo usado y reversiones.
- Ya existe `member_document_loyalty_line` para conservar qué líneas son elegibles.
- Ya existe migración `V127__liquidacion_historica_fidelizacion.sql`; no se debe crear otra tabla equivalente sin demostrar una carencia real.
- Ya existe consumo FIFO de lotes de saldo y validación de sincronización en `MemberLoyaltyService`.
- El contrato actual conserva `SALDO_MIEMBRO` como método de pago en `DocumentService`. Esto no coincide con el spec cerrado, donde el saldo socio es una resta que reduce el total y la base fiscal.
- El flujo actual ya contempla `checkoutDiscountAmount`, que debe conservarse como descuento monetario F11 y no mezclarse con el saldo socio.

## Objetivo técnico

Hacer que la cotización y la confirmación del ticket trabajen con dos reducciones monetarias independientes:

- `memberBalanceAmount`: saldo socio consumido antes de impuestos.
- `checkoutDiscountAmount`: descuento F11 antes de impuestos.

El backend debe resolver el descuento socio porcentual desde el socio y sus categorías, excluir líneas bloqueadas, aplicar ambas restas, calcular impuestos sobre el resultado y generar fidelización únicamente sobre importes efectivamente cobrados.

## Contrato propuesto

### Cotización

Extender el request autoritativo de venta para aceptar el importe solicitado de saldo socio, manteniendo el descuento F11 como campo independiente.

El backend deberá devolver un resumen autoritativo con:

- Subtotal.
- Descuento socio.
- Saldo socio solicitado y aceptado.
- Descuento F11.
- Total a pagar.
- Líneas elegibles y no elegibles.
- Bases y cuotas por tipo de IVA.
- Saldo socio restante.
- Hash de cotización y caducidad.

El navegador nunca enviará precios, impuestos, porcentajes de categoría ni importes finales como valores autoritativos.

### Confirmación

La confirmación recibirá el mismo saldo socio y descuento F11 que fueron cotizados, además de los pagos reales y el importe pendiente cuando corresponda.

El backend volverá a calcular y validará:

- Hash de request.
- Socio activo.
- Saldo vigente a la hora de confirmación.
- Caducidad a las `23:59` de la zona horaria de la tienda.
- FIFO de lotes.
- Líneas elegibles.
- Total final e impuestos.
- Suma de pagos reales más pendiente.
- Idempotencia del checkout.

El saldo socio no se persistirá como `DocumentPayment` ni se incluirá en la suma de medios de pago. Se registrará en la liquidación de fidelización del documento como `member_balance_used`.

## Archivos previstos

### Backend

- `backend/src/main/java/com/tpverp/backend/document/PosCashController.java`
  - Extender request y response de cotización y confirmación.
  - Exponer errores localizables para saldo caducado, saldo insuficiente y socio no válido.

- `backend/src/main/java/com/tpverp/backend/document/PosCashService.java`
  - Incorporar saldo socio a la construcción de la cotización.
  - Mantener separado el descuento F11.
  - Incluir ambos valores en hash, reserva y replay del checkout.

- `backend/src/main/java/com/tpverp/backend/document/AuthoritativeSaleQuoteController.java`
  - Mantener la ruta existente y sus permisos.
  - No introducir un endpoint paralelo para la misma cotización.

- `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`
  - Aplicar descuento socio, saldo socio y descuento F11 en la construcción autoritativa del documento.
  - Eliminar la dependencia de `SALDO_MIEMBRO` como medio de pago para esta operación.
  - Registrar el consumo en la liquidación existente.
  - Mantener los pagos reales y la deuda separados del saldo usado.

- `backend/src/main/java/com/tpverp/backend/document/DocumentCommand.java`
  - Añadir únicamente los campos necesarios para transportar la intención del cobro.
  - No aceptar desde el navegador descuentos de categoría ni datos fiscales calculados.

- `backend/src/main/java/com/tpverp/backend/document/CustomerReceivableService.java`
  - Confirmar que cada cobro posterior de deuda llama a la liquidación de fidelización con el importe realmente cobrado.
  - Evitar duplicar puntos o saldo en reintentos.

- `backend/src/main/java/com/tpverp/backend/party/MemberLoyaltyService.java`
  - Reutilizar consumo FIFO, caducidad y movimientos existentes.
  - Ajustar el método de consumo para que quede ligado a la reducción del documento y no a un pago artificial.

- `backend/src/main/java/com/tpverp/backend/party/MemberDocumentLoyaltySettlement.java`
  - Reutilizar `memberBalanceUsed`, `eligiblePaidAmount`, desglose de deuda y reversión.
  - Añadir campos solo si la liquidación no puede representar el nuevo orden de descuentos o su snapshot.

- `backend/src/main/java/com/tpverp/backend/party/MemberDocumentLoyaltyLine.java`
  - Mantener la elegibilidad por línea.
  - Asegurar que la distribución de saldo y F11 nunca afecta a líneas bloqueadas.

### Base de datos

- Revisar `V127__liquidacion_historica_fidelizacion.sql` y sus contratos antes de crear migraciones.
- Crear una nueva migración únicamente si falta guardar el snapshot necesario para distinguir descuento socio, saldo socio y F11 en documentos históricos.
- No modificar migraciones ya aplicadas.

### Pruebas backend

- `AuthoritativeSaleQuoteController` y contrato de request/response.
- `PosCashService` para descuentos, saldo, IVA y hash.
- `DocumentService` para confirmación, pagos reales y documento pendiente.
- `CustomerReceivableService` para cobro posterior y acumulación incremental.
- `MemberDocumentLoyaltySettlement` para límites, deuda y reversión.
- Contrato PostgreSQL de cualquier nueva migración, solo si resulta necesaria.

## Secuencia de implementación

1. Añadir pruebas de contrato que fallen para saldo socio como reducción fiscal y no como pago.
2. Separar en la cotización el saldo socio de `SALDO_MIEMBRO` y de `checkoutDiscountAmount`.
3. Aplicar las dos restas sobre líneas elegibles y recalcular bases/cuotas por tipo de IVA.
4. Persistir el uso de saldo en la liquidación existente y consumir lotes FIFO dentro de la misma transacción.
5. Recalcular la acumulación por cada pago real, incluyendo cobros posteriores de deuda.
6. Añadir pruebas de caducidad, saldo insuficiente, total cero, cobro mixto, replay e idempotencia.
7. Añadir pruebas de anulación y devolución para restaurar saldo y revertir puntos/saldo generado.
8. Ejecutar la validación focalizada del backend antes de iniciar las pantallas.

## Riesgos que deben quedar cubiertos

- No representar el saldo socio como pago real, porque alteraría informes de cobros y caja.
- No confiar en un importe de saldo calculado por el navegador.
- No consumir saldo antes de validar el checkout completo.
- No generar puntos sobre una parte no cobrada o pendiente.
- No aplicar descuentos a líneas bloqueadas.
- No usar un único desglose fiscal para líneas con distintos tipos de IVA.
- No duplicar movimientos al reintentar una confirmación.

## Resultado esperado de la Fase 1

Una API de cotización y confirmación capaz de representar de forma autoritativa el flujo cerrado en el spec, con saldo socio como reducción fiscal, pagos reales separados, acumulación incremental y reversión trazable. Las pantallas de APP GESTIÓN y la integración visual de `F10` se implementarán después sobre este contrato.
