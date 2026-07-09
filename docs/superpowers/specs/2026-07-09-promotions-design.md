# Diseño de Promociones

## Resumen

Añadir un módulo de promociones configurable desde APP GESTION y aplicable en
APP VENTA y documentos de venta. El sistema debe soportar descuentos directos,
promociones por producto/familia/subfamilia/listado y cupones promocionales
generados tras confirmar documentos.

El backend será la fuente final de cálculo al emitir o confirmar documentos. El
frontend podrá previsualizar las promociones para mostrar al usuario qué se está
aplicando, pero el resultado persistido será siempre el recalculado por backend.

## Alcance

Las promociones aplican a:

- tickets;
- facturas de venta;
- albaranes de venta.

No aplican a documentos de compra.

Las promociones se configuran a nivel de empresa y aplican a todas sus tiendas.
Los cupones generados podrán canjearse en cualquier tienda de la misma empresa.
Se registrará la tienda que genera y la tienda que canjea.

## Tipos De Promoción

La primera versión soportará:

- `PURCHASE_THRESHOLD_COUPON`: compra superior a un importe y genera cupón para
  próxima compra.
- `PURCHASE_THRESHOLD_DISCOUNT`: compra superior a un importe y aplica descuento
  directo en la misma venta.
- `BUY_X_PAY_Y`: promociones tipo 3x2 o 2x1.
- `SECOND_UNIT_PERCENT`: segunda unidad con porcentaje de descuento.
- `FIXED_PACK_PRICE`: precio fijo por lote, por ejemplo 3 unidades por 10 EUR.
- `QUANTITY_DISCOUNT`: descuento por cantidad, por ejemplo desde 6 unidades un
  5 % y desde 12 unidades un 10 %.

Se deja fuera de esta versión la promoción por método de pago.

## Ámbito De Aplicación

Una promoción podrá aplicarse sobre:

- toda la venta, solo para reglas de compra superior a un importe;
- productos concretos mediante una lista propia de productos;
- familia;
- subfamilia.

La lista propia de productos es obligatoria como opción independiente. Permite
crear, por ejemplo, un 3x2 sobre productos concretos sin cambiar familias ni
subfamilias.

Los productos con `DiscountType.NONE` quedan excluidos de promociones,
cupones promocionales y cualquier descuento automático generado por promoción.

Las líneas con descuento manual quedan fuera de promociones automáticas.

## Segmentación De Clientes

Cada promoción podrá configurarse para:

- `ALL`: todos los clientes, incluidas ventas anónimas;
- `IDENTIFIED_CUSTOMERS`: solo ventas con cliente identificado;
- `MEMBERS_ONLY`: solo clientes member;
- `MEMBER_CATEGORY`: solo una categoría member concreta.

Si una promoción exige cliente o member, no se aplica ni genera cupón en ventas
anónimas.

## Vigencia Y Estado

Una promoción tendrá:

- nombre;
- descripción;
- fecha de inicio;
- fecha de fin opcional;
- estado.

Estados:

- `DRAFT`: borrador editable, puede estar incompleto.
- `ACTIVE`: participa en el cálculo.
- `INACTIVE`: no participa en el cálculo.

No habrá horarios ni días de semana en primera versión.

Una promoción `DRAFT` o `INACTIVE` se podrá eliminar definitivamente si nunca fue
usada. Una promoción usada no se elimina.

Una promoción usada es una promoción aplicada en al menos un documento confirmado
o que haya generado/canjeado un cupón relacionado.

Si una promoción usada necesita cambios, se duplicará como nueva versión. La
nueva versión nace en `DRAFT`. Al activar la nueva versión, la anterior se
desactiva automáticamente.

## Acumulación Y Conflictos

Las promociones pueden acumularse si no tienen conflicto.

Existe conflicto cuando dos promociones intentan aplicar beneficio sobre las
mismas líneas de producto. En ese caso se aplica automáticamente la opción que
más beneficia al cliente.

Ejemplos:

- Agua con 3x2 y Agua con segunda unidad al 50 %: conflicto, gana la mejor.
- Agua con 3x2 y Leche con segunda unidad al 50 %: no hay conflicto, se aplican
  ambas.
- Agua con 3x2 y promoción de compra superior a 50 EUR que genera cupón: no hay
  conflicto, se aplican ambas si se cumple el mínimo.

## Orden De Cálculo

El orden de cálculo será:

1. líneas normales del documento;
2. descuentos manuales;
3. promociones automáticas de producto/familia/subfamilia/listado;
4. cupón promocional usado;
5. total final a pagar;
6. promociones globales que generan cupón después de confirmar, si el total
   final cumple el mínimo.

Las promociones de compra superior a un importe se calculan sobre el total que
el cliente debe pagar después de descuentos manuales, promociones directas y
cupón usado. El método de pago no importa: efectivo, tarjeta, transferencia,
vale, saldo member o mixto.

Una venta que usa un cupón puede generar otro cupón si después de aplicar el
cupón sigue superando el mínimo configurado.

## Líneas De Promoción

Los descuentos promocionales se mostrarán como líneas negativas especiales:

- tipo de línea `PROMOTION`;
- sin producto asociado;
- no afectan stock;
- cantidad 1;
- importe negativo;
- referencia a promoción y versión;
- nombre mostrado;
- líneas o productos afectados;
- snapshot fiscal.

Las líneas `PROMOTION` aparecerán al final del bloque de artículos, antes de
totales.

Si una promoción afecta productos con distintos impuestos, se crearán varias
líneas `PROMOTION`, una por cada tipo fiscal afectado. Esto mantiene correctas
las bases e impuestos. La impresión podrá agruparlas visualmente si no rompe la
claridad fiscal.

## Cupones Promocionales

Un cupón promocional se genera después de confirmar el ticket, factura de venta
o albarán de venta. No se usa en el mismo documento que lo genera.

El cupón genera un comprobante separado con sus datos y código. APP VENTA debe
imprimirlo automáticamente siempre que se genere. También podrá reimprimirse
desde consulta administrativa.

El cupón podrá tener:

- importe fijo;
- porcentaje;
- porcentaje con límite máximo opcional;
- ámbito por productos, familias, subfamilias o listado concreto;
- compra mínima para usarlo;
- periodo de validez propio.

El periodo de validez puede configurarse como:

- inmediato;
- día siguiente;
- después de X días;
- fecha fija desde;
- fecha fija hasta;
- X días desde el inicio de validez.

Valor recomendado por defecto: válido inmediatamente y caduca en 30 días.

El cupón se canjea como línea negativa `CUPÓN PROMOCIONAL`, no como método de
pago. Puede usarse en tickets, facturas de venta y albaranes de venta. No puede
dejar el documento en negativo; sí puede dejarlo en 0 EUR.

El cupón es de un solo uso. Si el importe del cupón supera el importe aplicable,
se limita el descuento al importe aplicable, se consume completo y se pierde el
sobrante.

Si hay cliente/member en el documento que lo genera, el cupón queda asociado a
ese cliente/member. Si no hay cliente y la promoción permite ventas anónimas, el
cupón será usable por código.

Si un cupón está asociado a cliente/member, solo podrá usarlo ese cliente/member.

## Código Seguro De Cupón

El código del cupón será aleatorio y no secuencial:

- formato recomendado: `PROMO-XXXXXXXXXXXX`;
- generado con `SecureRandom`;
- alfabeto sin caracteres confusos: `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`;
- longitud mínima: 12 caracteres aleatorios.

El backend guardará el hash del código y los últimos 4 caracteres visibles para
búsqueda administrativa. El código completo solo se muestra al generarlo e
imprimirlo.

## Estados De Cupón

Estados:

- `ACTIVE`: disponible para usar.
- `USED`: consumido.
- `EXPIRED`: caducado.
- `CANCELLED`: anulado manualmente.

Un cupón `USED` no se reactiva. Un cupón `CANCELLED` puede reactivarse si no
está caducado.

`ADMIN` y `GESTION_VENTAS` podrán anular/reactivar cupones, con motivo
obligatorio y registro de usuario, fecha/hora, estado anterior y estado nuevo.

## Intentos Fallidos De Cupón

Se guardará historial de intentos fallidos de uso de cupón:

- hash/parcial del código, nunca el código completo en claro;
- empresa;
- tienda;
- usuario;
- terminal;
- fecha/hora;
- motivo.

Motivos mínimos:

- inexistente;
- caducado;
- cancelado;
- ya usado;
- cliente no coincide;
- documento no elegible;
- importe mínimo no alcanzado.

## Permisos

Crear, modificar, activar y desactivar promociones:

- `ADMIN`;
- `GESTION_VENTAS`.

Consultar y reimprimir cupones:

- `ADMIN`;
- `GESTION_VENTAS`.

Anular/reactivar cupones:

- `ADMIN`;
- `GESTION_VENTAS`.

Validar o usar un cupón durante una venta:

- usuarios con permiso de venta.

## APP GESTION

APP GESTION usará asistente por pasos para crear o editar promociones:

1. Datos básicos: nombre, descripción, estado, fechas y segmentación.
2. Tipo de promoción.
3. Ámbito: productos concretos, familia, subfamilia o toda la venta.
4. Condiciones: cantidad mínima, importe mínimo, exclusiones.
5. Beneficio: descuento directo, cupón, importe, porcentaje o precio pack.
6. Cupón: validez, caducidad, compra mínima y ámbito del cupón.
7. Resumen: vista previa, simulación básica, guardar borrador o activar.

APP GESTION incluirá:

- listado de promociones;
- crear/editar;
- duplicar promoción;
- activar/desactivar;
- eliminar borradores o inactivas no usadas;
- selección de productos por listado;
- consulta de cupones;
- reimpresión de cupones;
- anulación/reactivación de cupones;
- historial de intentos fallidos.

## APP VENTA

APP VENTA previsualizará promociones automáticamente al:

- añadir producto;
- quitar producto;
- cambiar cantidad;
- cambiar cliente/member;
- introducir cupón;
- cambiar fecha/hora efectiva de venta si afecta a vigencia.

La pantalla debe mostrar:

- promoción aplicada;
- productos afectados;
- descuento por línea promocional;
- total descontado;
- cupón usado;
- cupón generado tras confirmar;
- motivo si una promoción esperada no aplica.

El backend recalculará al emitir o confirmar y devolverá el resultado definitivo.

## Fiscalidad Y Verifactu

El cupón generado no tiene efecto fiscal en el momento de generación. Solo tiene
efecto fiscal cuando se canjea.

Cuando se usa un cupón promocional o una promoción directa, debe reflejarse como
línea negativa/descuento del documento. Debe entrar en el cálculo de bases,
impuestos, total y snapshot fiscal.

Las líneas promocionales no afectan stock.

## Pruebas

Pruebas mínimas:

- creación, activación, desactivación y borrado de promociones no usadas;
- bloqueo de edición directa de promociones usadas;
- duplicación/versionado con desactivación automática de la versión anterior;
- selección por listado de productos, familia y subfamilia;
- exclusión de productos `DiscountType.NONE`;
- exclusión de líneas con descuento manual;
- resolución de conflictos aplicando la opción más beneficiosa;
- acumulación de promociones sin conflicto;
- 3x2 y segunda unidad con productos de distinto precio;
- líneas negativas separadas por impuesto;
- generación de cupón tras confirmar documento;
- canje de cupón en ticket, factura y albarán de venta;
- cupón asociado a cliente/member;
- cupón anónimo por código;
- código aleatorio con búsqueda por hash;
- consumo completo sin saldo restante;
- rechazo de cupón caducado, usado, cancelado o de otro cliente;
- historial de intentos fallidos;
- permisos de gestión, consulta, reimpresión y uso en venta.

## Fuera De Alcance

No se incluye en esta primera versión:

- promociones por método de pago;
- horarios o días de semana;
- límites de uso por promoción o por cliente;
- canje parcial con saldo restante;
- cupones como método de pago;
- edición directa de promociones usadas;
- selección de tiendas por promoción.
