# Busqueda de productos en Venta

## Objetivo

Convertir el campo visual de busqueda de `SaleScreen` en una busqueda funcional de productos por codigo interno, codigo de barras o nombre, y permitir incorporar el producto seleccionado al ticket actual.

## Alcance

- La aplicacion carga el catalogo de la tienda mediante `GET /api/v1/products` al abrir la pantalla Venta.
- La busqueda se ejecuta en memoria sobre el catalogo cargado.
- El texto se compara sin distinguir mayusculas, minusculas ni espacios exteriores.
- Se admiten coincidencias parciales por nombre, codigo interno y codigo de barras.
- Se muestran como maximo diez resultados con codigo, nombre y precio de venta.
- Una coincidencia exacta por codigo o codigo de barras se incorpora al pulsar `Enter`.
- Si solo existe un resultado filtrado, `Enter` incorpora ese resultado.
- Pulsar un resultado tambien lo incorpora al ticket.

## Ticket local

La primera seleccion de un producto crea una linea con cantidad uno. Seleccionar de nuevo el mismo producto incrementa su cantidad. Cada linea muestra nombre, codigo, cantidad, precio unitario y subtotal. El pie del ticket muestra la suma de los subtotales con dos decimales.

Este alcance mantiene el ticket en el estado local de la pantalla. La persistencia y confirmacion de ventas quedan fuera de esta entrega.

## Estados y errores

- Mientras se carga el catalogo, el buscador indica el estado de carga y permanece deshabilitado.
- Si falla la carga, se muestra un mensaje de error y se permite reintentar.
- Si una consulta no produce coincidencias, se muestra `No se encontraron productos`.
- Tras incorporar un producto, se limpia el texto de busqueda y el foco vuelve al campo.
- Con el campo vacio no se muestra la lista completa del catalogo.

## Arquitectura

`SaleScreen` conserva el estado de catalogo, consulta, carga, error y lineas del ticket. Las funciones puras de normalizacion, filtrado, seleccion por `Enter`, acumulacion de lineas y calculo total se exportan desde el mismo modulo para poder probarlas sin navegador.

No se modifica el backend porque `GET /api/v1/products` ya devuelve los productos de la tienda y admite los permisos `ADMIN`, `PRODUCTS_READ`, `GESTION_PRODUCTO` y `VENTA`.

## Pruebas

- Filtrado parcial por nombre sin distinguir mayusculas.
- Filtrado por codigo interno y codigo de barras.
- Limite de diez resultados.
- Prioridad de coincidencia exacta al pulsar `Enter`.
- Seleccion del unico resultado.
- Alta de una linea e incremento de cantidad para un producto repetido.
- Calculo de subtotales y total.
- Renderizado de estados de carga, error, sin resultados y ticket con productos.

## Criterios de aceptacion

1. Escribir un codigo o codigo de barras valido y pulsar `Enter` incorpora el producto al ticket.
2. Escribir parte del nombre muestra coincidencias seleccionables.
3. Seleccionar un resultado incorpora o incrementa el producto en el ticket.
4. El ticket y el total reflejan correctamente las cantidades y precios.
5. Los errores de catalogo y las consultas sin resultados son visibles.
6. Las pruebas automatizadas del componente y las funciones de busqueda pasan.
