# Acciones de linea y cliente en Venta

## Objetivo

Hacer funcionales los botones Cantidad, Descuento, Cliente y Anular linea del ticket local de `SaleScreen`.

## Seleccion de linea

Cada producto incorporado queda seleccionado. Pulsar una linea existente cambia la seleccion. La linea seleccionada se distingue visualmente. Cantidad, Descuento y Anular linea permanecen deshabilitados cuando no existe seleccion.

## Cantidad

Abre un dialogo integrado con la cantidad actual. Solo acepta numeros enteros entre 1 y 9999. Guardar reemplaza la cantidad de la linea y recalcula subtotal y total.

## Descuento

Abre un dialogo integrado con el descuento actual. Acepta porcentajes entre 0 y 100, con hasta dos decimales. El subtotal se calcula como `cantidad * precio * (1 - descuento / 100)` y se redondea visualmente a dos decimales.

## Cliente

Carga los clientes mediante `GET /api/v1/customers` al abrir el selector. Permite buscar sin distinguir mayusculas por nombre fiscal, numero de documento o codigo de cliente. Seleccionar un cliente lo asigna al ticket y muestra su nombre en la pantalla. Tambien se puede dejar el ticket sin cliente.

## Anular linea

Abre una confirmacion integrada con el producto seleccionado. Confirmar elimina la linea. La siguiente linea disponible queda seleccionada; si el ticket queda vacio, se elimina la seleccion.

## Alcance tecnico

El ticket, las cantidades, descuentos y cliente permanecen en estado local. No se crea ni confirma un documento en backend. Se reutilizan `SaleScreen.tsx`, sus pruebas y los estilos compartidos.

## Pruebas

- Reemplazo y validacion de cantidad.
- Aplicacion y validacion de descuento.
- Subtotal y total con descuento.
- Eliminacion y seleccion siguiente.
- Filtrado de clientes por nombre, documento y codigo.
- Estados y controles accesibles de los dialogos.
- Prueba funcional en navegador de los cuatro botones.

## Criterios de aceptacion

1. Los cuatro botones responden al pulsarlos.
2. Cantidad y descuento actualizan subtotal y total.
3. Cliente permite buscar, asignar y quitar cliente.
4. Anular linea elimina solamente la linea seleccionada tras confirmar.
5. Las pruebas especificas y el build pasan.
