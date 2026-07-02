# Member Loyalty Design

## Objetivo

Redisenar la parte de miembros para separar datos personales de fidelizacion, permitir categorias por empresa, calcular puntos y saldo miembro por compra, soportar caducidad de saldo por lote, enviar tarjeta virtual de alta y sincronizar todos los movimientos con el SaaS central.

El cambio es local-first: APP VENTA y APP GESTION operan contra la base local de tienda, y el SaaS central consolida el estado oficial para el resto de tiendas.

## Alcance

Incluye:

- Separar `cliente` y `miembro`.
- Anadir `birthday`, `gender` y consentimiento comercial a `cliente`.
- Crear categorias de miembro configurables por empresa.
- Crear configuracion de beneficios de miembro por empresa.
- Calcular descuento por categoria, puntos y saldo generado.
- Controlar caducidad del saldo por lotes.
- Permitir gasto de saldo con control de sincronizacion reciente.
- Enviar tarjeta virtual al activar miembro.
- Emitir movimientos sincronizables hacia SaaS.

No incluye en esta fase:

- Campanas comerciales masivas.
- Motor avanzado de reglas por producto/categoria de producto.
- Interfaz final de APP GESTION.
- Implementacion del servicio real de email/WhatsApp.

## Modelo Cliente y Miembro

`cliente` queda como entidad de identidad, datos fiscales, datos personales y contacto.

Nuevos campos en `cliente`:

- `birthday date null`
- `gender varchar(16) null`: `MASCULINO`, `FEMENINO`, `OTRO`
- `commercial_consent boolean not null default false`
- `preferred_commercial_channel_id uuid null`

Los campos actuales de miembro salen de `cliente` y pasan a una tabla propia `miembro`.

`miembro` es una relacion 1:1 opcional con `cliente`:

- Un cliente puede no ser miembro.
- Un cliente puede tener como maximo una fila `miembro`.
- Si deja de ser miembro, la fila se conserva con `active = false`.

Campos base de `miembro`:

- `id`
- `empresa_id`
- `cliente_id`
- `member_id`
- `num_member`
- `member_since`
- `member_balance`
- `member_points`
- `member_category_id`
- `auto_category_locked`
- `active`
- `official_member_balance`
- `official_member_points`
- `official_category_id`
- `official_synced_at`
- `version`

Reglas:

- `member_id` es inmutable.
- `member_since` es inmutable desde el primer alta.
- Desactivar miembro no borra historico, saldo, puntos ni categoria.
- Un miembro desactivado no acumula nuevos puntos/saldo y no puede gastar saldo.

## Categorias

`member_category` define niveles por empresa, por ejemplo Plata, Oro y Platino.

Campos:

- `id`
- `empresa_id`
- `name`
- `min_points`
- `discount_percent`
- `discount_enabled`
- `active`
- `sort_order`
- `version`

Reglas:

- Las categorias son por empresa.
- Al activar un miembro se asigna la categoria activa con menor `min_points`.
- Si no hay categoria activa, el miembro queda sin categoria.
- Los puntos son acumulados para siempre y no caducan.
- El descuento por categoria se puede combinar con gasto de saldo miembro.
- Si el admin desactiva una categoria con miembros activos, estos se mueven a la categoria inferior activa disponible.
- Si no existe categoria inferior activa, no se permite desactivar esa categoria.

`member_category_history` registra los cambios:

- `id`
- `miembro_id`
- `previous_category_id`
- `new_category_id`
- `reason`
- `manual`
- `auto_category_locked`
- `usuario_id`
- `created_at`

Recategorizacion:

- Si `auto_category_locked = false`, el sistema recalcula categoria al confirmar ventas que sumen puntos.
- Si `auto_category_locked = true`, los puntos siguen acumulando pero no cambian la categoria automaticamente.
- Una recategorizacion manual puede activar o no el bloqueo automatico.

## Configuracion Por Empresa

`member_settings` tiene una fila por empresa.

Campos:

- `empresa_id`
- `balance_accrual_percent`
- `balance_expiration_policy`: `NO_CADUCA`, `1_MES`, `3_MESES`, `6_MESES`, `1_ANO`
- `points_per_euro`
- `category_auto_enabled`
- `member_welcome_enabled`
- `member_card_code_format`: `QR`, `BARCODE`
- `welcome_subject_template`
- `welcome_body_template`
- `version`

Reglas:

- `balance_accrual_percent` se aplica sobre el importe elegible pagado realmente.
- `points_per_euro` permite valores como `1.00`, `2.00` o `0.50`.
- Los puntos generados son enteros y se calculan con `floor`.
- El saldo generado se trunca a 2 decimales, no se redondea.
- La categoria automatica se puede desactivar globalmente por empresa con `category_auto_enabled = false`.

## Productos Excluidos

Existe un plan separado para marcar productos sin beneficios de miembro. Este diseno asume un flag de producto equivalente a:

- `excluded_from_member_benefits boolean`

Para esos productos:

- No aplica descuento automatico por categoria.
- No acumula puntos.
- No acumula saldo nuevo.
- No aplica precio/descuento especial de miembro.
- Si se permite gastar saldo miembro ya acumulado para pagarlos.

## Calculo De Venta

El descuento por categoria se aplica antes de calcular puntos/saldo, pero la acumulacion solo se calcula sobre el importe pagado realmente.

Flujo por ticket:

1. Separar lineas elegibles y excluidas.
2. En lineas elegibles, aplicar descuento automatico de categoria si esta activo.
3. Calcular neto elegible y neto total del ticket.
4. Aplicar saldo miembro como forma de pago final.
5. Repartir el saldo usado proporcionalmente entre lineas elegibles y excluidas.
6. Calcular `base_acumulacion = neto_elegible - saldo_imputado_a_elegibles`.
7. Generar puntos y saldo sobre `base_acumulacion`.

Formula:

```text
puntos = floor(base_acumulacion * points_per_euro)
saldo = trunc_2(base_acumulacion * balance_accrual_percent / 100)
```

Ejemplo:

```text
neto total: 100
neto elegible: 70
neto excluido: 30
saldo usado: 20

saldo imputado a elegible: 14
base acumulacion: 56
```

La categoria nueva por puntos se aplica a compras posteriores, no al ticket que acaba de confirmar.

## Lotes De Saldo

El saldo se controla por lotes para poder caducar sobrantes segun la compra que los genero.

`member_balance_lot`:

- `id`
- `miembro_id`
- `documento_id`
- `source_movement_id`
- `amount_original`
- `amount_remaining`
- `created_at`
- `expires_at`
- `expired_at`
- `version`

Reglas:

- Cada acumulacion de saldo crea un lote.
- `expires_at = null` si la politica es `NO_CADUCA`.
- El gasto de saldo consume primero los lotes con caducidad mas proxima.
- Si el miembro gasta parte de un lote, el restante conserva la fecha de caducidad original.
- La caducidad genera un movimiento de tipo `CADUCIDAD_SALDO`.

`member_balance_lot_consumption` registra que movimiento de uso consumio que lotes:

- `movement_id`
- `lot_id`
- `amount`

## Movimientos

Se reemplaza/enriquece el historial actual `member_balance_movement` con una tabla general de movimientos de miembro.

`member_movement`:

- `id`
- `empresa_id`
- `tienda_id`
- `miembro_id`
- `documento_id`
- `type`
- `balance_amount`
- `points_amount`
- `previous_category_id`
- `new_category_id`
- `reason`
- `created_by_user_id`
- `created_at`
- `source_event_id`
- `version`

Tipos:

- `ALTA_MIEMBRO`
- `DESACTIVACION_MIEMBRO`
- `CAMBIO_CATEGORIA`
- `ACUMULACION_PUNTOS`
- `ACUMULACION_SALDO`
- `USO_SALDO`
- `CADUCIDAD_SALDO`
- `AJUSTE_MANUAL_SALDO`
- `AJUSTE_MANUAL_PUNTOS`
- `AJUSTE_SAAS`

Reglas:

- Los movimientos son inmutables.
- Todo movimiento relevante se publica en `sync_outbox`.
- `source_event_id` permite idempotencia al recibir eventos del SaaS.
- Si llega un estado oficial diferente desde SaaS, la tienda sobrescribe el estado local y crea `AJUSTE_SAAS`.

## Sincronizacion SaaS

El SaaS es la autoridad final del saldo miembro.

Flujo:

```text
tienda local crea movimiento
-> sync_outbox
-> SaaS valida y consolida
-> SaaS publica estado oficial
-> otras tiendas reciben por sync_inbox
-> actualizan miembro local
```

Reglas:

- Acumular puntos/saldo puede hacerse offline.
- Activar/desactivar miembro puede hacerse offline.
- Cambios de categoria pueden hacerse offline y sincronizarse.
- Gastar saldo requiere sincronizacion reciente con SaaS.
- El limite de sincronizacion reciente es fijo: 5 minutos.
- Para gastar saldo, `official_synced_at` debe estar dentro de los ultimos 5 minutos.
- Si SaaS detecta duplicado o saldo insuficiente por carrera, emite estado oficial y/o ajuste compensatorio.

## Comunicaciones Comerciales

Los canales comerciales son configurables por empresa.

`commercial_contact_channel`:

- `id`
- `empresa_id`
- `code`
- `name`
- `active`
- `version`

Valores iniciales:

- `EMAIL`
- `WHATSAPP`

Reglas:

- `commercial_consent = false` bloquea campanas y comunicaciones promocionales.
- `commercial_consent = true` requiere canal preferido.
- El admin puede crear mas canales.
- El consentimiento vive en `cliente`, no en `miembro`, porque aplica a la persona aunque no sea miembro.

## Tarjeta Virtual De Miembro

El envio automatico se dispara solo al activar miembro.

Reglas:

- Cliente normal creado: no envia tarjeta.
- Cliente creado como miembro: puede enviar tarjeta.
- Cliente existente activado como miembro: puede enviar tarjeta.
- La funcion depende de `member_welcome_enabled`.
- El texto de asunto y cuerpo es editable por admin.
- La tarjeta incluye `member_id`, nombre del cliente, categoria actual si existe y codigo visual.
- El codigo visual codifica `member_id`, no el UUID interno.
- `member_card_code_format` puede ser `QR` o `BARCODE`; valor por defecto `QR`.
- El email de tarjeta es transaccional, no comercial, y puede enviarse aunque `commercial_consent = false`.
- Requiere email valido.

La implementacion inicial puede registrar una tarea/envio pendiente aunque el proveedor real de email se integre despues.

## Migracion

La migracion debe:

1. Crear `miembro`.
2. Migrar desde `cliente` los campos actuales `is_member`, `member_id`, `num_member`, `member_since` y `member_balance`.
3. Conservar filas de miembro para clientes que ya fueron miembros.
4. Crear categorias, settings, movimientos, lotes y canales.
5. No crear categorias de ejemplo automaticamente; si una empresa ya tiene categorias activas, asignar a cada miembro la categoria activa con menor `min_points`.
6. Si una empresa no tiene categorias activas, dejar sus miembros sin categoria hasta que el admin configure una.
7. Mantener compatibilidad temporal de API si APP VENTA/APP GESTION aun esperan campos de miembro dentro de la vista de cliente.

## API Inicial

Endpoints nuevos o ampliados:

- `GET /api/v1/customers`
- `POST /api/v1/customers`
- `PUT /api/v1/customers/{id}`
- `POST /api/v1/customers/{id}/member/activate`
- `POST /api/v1/customers/{id}/member/deactivate`
- `GET /api/v1/members/{id}`
- `GET /api/v1/members/{id}/movements`
- `POST /api/v1/members/{id}/balance-adjustments`
- `POST /api/v1/members/{id}/points-adjustments`
- `PUT /api/v1/members/{id}/category`
- `GET /api/v1/member-categories`
- `POST /api/v1/member-categories`
- `PUT /api/v1/member-categories/{id}`
- `PATCH /api/v1/member-categories/{id}/deactivate`
- `GET /api/v1/member-settings`
- `PUT /api/v1/member-settings`
- `GET /api/v1/commercial-contact-channels`
- `POST /api/v1/commercial-contact-channels`
- `PUT /api/v1/commercial-contact-channels/{id}`

## Permisos

Reutilizar `CUSTOMERS_READ` y `CUSTOMERS_WRITE` para datos de cliente y miembro en la primera implementacion.

Si hace falta separar gestion de fidelizacion mas adelante, anadir:

- `MEMBERS_READ`
- `MEMBERS_WRITE`
- `MEMBER_SETTINGS_WRITE`

`ADMIN` mantiene acceso completo.

## Pruebas

Cobertura minima:

- Migracion `cliente` -> `miembro`.
- Alta, desactivacion y reactivacion conservando `member_id`.
- Asignacion automatica de categoria inicial.
- Cambio manual de categoria con y sin bloqueo automatico.
- Desactivacion de categoria moviendo miembros a categoria inferior.
- Calculo de descuento, puntos y saldo sobre base pagada.
- Productos excluidos: no generan beneficios, pero permiten gasto de saldo.
- Caducidad por lote y consumo FIFO por fecha de caducidad.
- Bloqueo de gasto de saldo si `official_synced_at` supera 5 minutos.
- Emision de `sync_outbox` para cada movimiento.
- Idempotencia al recibir eventos SaaS.
- Email transaccional de tarjeta solo al activar miembro y con configuracion activa.

## Plan De Implementacion Recomendado

Implementar por fases:

1. Migracion y modelo `cliente`/`miembro`.
2. Categorias, settings y canales comerciales.
3. Movimientos generales y lotes de saldo.
4. Calculo de venta: descuento, puntos, saldo y productos excluidos.
5. Sincronizacion SaaS e idempotencia.
6. Tarjeta virtual y envio transaccional.
7. Superficie de APP GESTION para configurar reglas.
