# Goods Check Design

## Objetivo

Añadir comprobación de mercancía para comparar cantidades físicas recibidas contra un `ALBARAN_COMPRA` o `FACTURA_COMPRA` confirmado, sin modificar documento, stock ni pagos.

## Reglas

- Nombre visible: comprobación de mercancía.
- Nombre técnico: `goods-check`.
- Documentos permitidos: `ALBARAN_COMPRA`, `FACTURA_COMPRA`.
- El documento debe estar confirmado.
- Solo una comprobación abierta por documento.
- Una comprobación cerrada es inmutable.
- Si hace falta repetir, se crea otra comprobación.
- Las cantidades son enteras.
- Cada registro suma cantidad positiva o negativa al total acumulado del producto.
- No se permite que el total registrado de un producto baje de `0`.
- Si se registra más de lo esperado, queda como sobra.
- El escaneo solo busca productos dentro del documento actual.
- Si el producto no está en el documento, devuelve no encontrado en este documento.
- Si no se puede escanear, el usuario puede seleccionar una línea del documento.
- Si un producto aparece en varias líneas, se agrupa por producto y se compara contra la suma esperada.

## Permisos

Pueden usar comprobación de mercancía:

- `ADMIN`
- `GESTION_PRODUCTO`
- `DELIVERY_NOTES_READ`
- `DELIVERY_NOTES_WRITE`
- `INVOICES_READ`
- `INVOICES_WRITE`

Pueden ver precio de compra y editar artículos desde el dispositivo:

- `ADMIN`
- `PRODUCTS_WRITE`
- `GESTION_PRODUCTO`

La edición de artículo usa los endpoints/permisos existentes de productos.

## Datos

Crear tabla de cabecera:

- `id`
- `documento_id`
- `tienda_id`
- `estado`: `ABIERTA`, `COMPLETA`, `CON_DIFERENCIAS`
- `creado_por`
- `creado_en`
- `cerrado_por`
- `cerrado_en`

Crear tabla de líneas acumuladas:

- `id`
- `comprobacion_id`
- `producto_id`
- `cantidad_esperada`
- `cantidad_registrada`
- `ultimo_usuario_id`
- `terminal_id`
- `actualizado_en`

## API

- `POST /api/v1/goods-checks/documents/{documentId}/start`
- `GET /api/v1/goods-checks/{id}`
- `POST /api/v1/goods-checks/{id}/scan`
- `POST /api/v1/goods-checks/{id}/close`

La vista devuelve:

- `todos`
- `faltantes`
- `registrados`

## Estados

- `ABIERTA`: editable.
- `COMPLETA`: cerrada y todas las cantidades coinciden.
- `CON_DIFERENCIAS`: cerrada con faltantes o sobrantes.

## Sincronización

La comprobación se guarda en el backend local de tienda. Se publicará por la sincronización SaaS existente como entidad `GOODS_CHECK`.

## Pruebas mínimas

- No iniciar sobre documento no confirmado.
- No iniciar sobre tipo no permitido.
- No permitir dos abiertas del mismo documento.
- Sumar cantidades positivas y negativas.
- Bloquear total registrado negativo.
- Cerrar como `COMPLETA` o `CON_DIFERENCIAS`.
- Verificar permisos del controller.
