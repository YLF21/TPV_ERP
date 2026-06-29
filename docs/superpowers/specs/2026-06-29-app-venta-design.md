# Diseno De APP VENTA

## Objetivo

Crear la primera fase del frontend de escritorio para `TPV ERP`: **APP VENTA**.
La aplicacion estara orientada a venta rapida en mostrador, con flujo de
ticket por defecto, ventana secundaria para factura/albaran, operativa fuerte
por teclado y conexion con el backend existente en `/api/v1`.

**APP GESTION** queda fuera de esta fase. Se mantiene prevista como segunda
aplicacion independiente sobre la misma base tecnica, pero no se implementara
en este primer alcance.

## Arquitectura

El frontend vivira en `frontend/` dentro del mismo repositorio y tendra Maven
Wrapper propio. Sera un proyecto Maven multi-modulo con JavaFX:

- `app-common`: cliente API, sesion, permisos, i18n, validaciones y componentes
  comunes.
- `app-venta`: ejecutable, login y pantallas de **APP VENTA**.
- `app-gestion`: modulo previsto para la segunda fase; no forma parte del MVP
  de implementacion de **APP VENTA**.

**APP VENTA** y **APP GESTION** seran aplicaciones independientes para el
usuario, con accesos directos, login y navegacion propios. Compartiran codigo
interno mediante `app-common`.

La empresa, tienda y terminal vendran fijados por instalacion/asignacion del
servidor. El usuario no seleccionara estos datos durante el uso normal.

## Permisos

Para entrar en **APP VENTA**, el usuario debe tener al menos uno de estos
permisos o rol equivalente:

- `ADMIN`
- `VENTA`
- `GESTION_VENTAS`
- `TICKETS_CREATE`
- `INVOICES_WRITE`
- `DELIVERY_NOTES_WRITE`

`GESTION_PRODUCTO` permite crear/modificar articulos y realizar entrada/salida
de almacen, pero no concede acceso a **APP VENTA** por si solo.

Permisos especiales:

- `APLICAR_DESCUENTO`: permite descuento por linea y descuento global.
- `CAMBIAR_PRECIO`: permite cambio puntual de precio de una linea.
- `GESTION_PRODUCTO`: permite crear/modificar articulos y hacer entradas o
  salidas de almacen desde **APP VENTA**.

Si el usuario activo no tiene `APLICAR_DESCUENTO` o `CAMBIAR_PRECIO`, la
aplicacion pedira identificacion de un usuario autorizado mediante usuario y
contrasena. La venta seguira atribuida al vendedor original. Se registrara que
hubo autorizacion por otro usuario, sin destacar en el documento la accion
concreta autorizada.

## Arranque Y Caja

Al arrancar, **APP VENTA** comprobara instalacion, licencia, empresa, tienda,
terminal y sesion de usuario contra el backend.

Sin caja abierta solo se permitira:

- consultar articulos;
- consultar stock de articulos.

Con caja abierta se habilitara la operativa de venta y las acciones permitidas
por permisos.

## Pantalla Principal De Ticket

La pantalla principal sera siempre el ticket rapido. El documento por defecto
es ticket.

Distribucion:

- Zona central: tabla de lineas de venta.
- Lateral derecho:
  - total grande arriba;
  - campo rapido debajo.
- Parte inferior:
  - recuentos de tabla;
  - totales;
  - cliente seleccionado si aplica;
  - acciones necesarias de cobro y accesos secundarios.

La busqueda rapida por producto se hara por codigo o codigo de barras. No se
buscara por nombre en el campo rapido. La busqueda por nombre se resolvera en
listados auxiliares.

## Campo Rapido

El campo rapido sera una entrada operativa siempre enfocada. El valor escrito no
tendra significado por si solo; la tecla pulsada decide como interpretarlo.

Ejemplos:

- `20` + `Enter`: anade producto con codigo o codigo de barras `20`.
- `20` + `Pausa`: cambia la cantidad de la linea seleccionada a `20`.
- `20` + `/`: aplica `20%` de descuento a la linea seleccionada.
- `20` + `Ctrl + /`: aplica `20%` de descuento al ticket completo y a lineas
  futuras antes de cobrar.
- `2` + `Ctrl + *`: convierte 2 paquetes a unidades segun el articulo.
- `20` + `Re Pag`: cambia el precio de la linea seleccionada a `20` si hay
  permiso.

Despues de ejecutar una accion, el campo rapido se limpiara. Si una accion
requiere linea seleccionada y no existe, la app mostrara un aviso breve. Si la
accion requiere permiso, pedira autorizacion.

## Lineas De Venta

Columnas de la tabla:

- `Codigo`
- `Nombre`
- `Cantidad`
- `Paquetes`
- `Precio`
- `% Dto.`
- `Total`

La columna `Paquetes` podra mostrarse u ocultarse por configuracion. El flujo
de paquetes no se desactiva aunque la columna este oculta. Si un producto tiene
12 unidades por paquete, `2` + `Ctrl + *` dejara la cantidad en `24`. Si la
columna esta visible, tambien mostrara `Paquetes = 2`.

Las lineas seran navegables libremente. Las acciones de linea siempre se
aplicaran a la linea seleccionada, no necesariamente a la ultima:

- cambiar cantidad;
- subir cantidad;
- descuento de linea;
- cambiar precio;
- cambiar nombre para esta venta;
- cambiar a paquete.

La linea seleccionada debe ser inequivoca: fondo oscuro, texto blanco, negrita
y un punto mas grande. Las lineas no seleccionadas tendran fondo claro y texto
negro.

## Totales Y Recuentos

La tabla funcionara visualmente como una hoja de calculo. Al final se mostraran
recuentos:

- numero de lineas;
- suma de cantidades;
- suma de paquetes si aplica;
- total sin descuento;
- total con descuentos aplicados.

El total grande lateral mostrara siempre el total final a cobrar, con impuestos
incluidos y descuentos aplicados.

## Stock

**APP VENTA** permite vender con stock negativo sin avisos de falta de stock.
Al confirmar ticket, factura o albaran se descontara stock. Si el resultado es
negativo, se guarda tal cual.

Todos los usuarios autorizados pueden consultar stock. Solo `ADMIN` o
`GESTION_PRODUCTO` pueden hacer entrada o salida de almacen desde **APP VENTA**.
La modificacion directa de stock queda reservada para **APP GESTION**.

Crear un articulo no pedira stock inicial. El stock se ajustara mediante
entrada, salida o modificacion de stock segun la aplicacion y permisos.

## Articulos Y Clientes

**APP VENTA** permitira consultar articulos y clientes. La creacion o
modificacion de articulos requiere `GESTION_PRODUCTO` o `ADMIN`.

La ficha de articulo sera compartida conceptualmente con **APP GESTION**, pero
en **APP VENTA** no incluira modificacion directa de stock. La entrada/salida
de almacen sera una accion especifica con permiso.

## Cobro

`Av Pag` abrira la pantalla de cobro.

Atajos de metodo de pago:

- `*`: efectivo.
- `+`: tarjeta.
- `F8`: transferencia.
- `F9`: vale.
- `F10`: otros.

La pantalla de cobro mostrara:

- total de la venta;
- cantidad que falta por cobrar;
- cuadro para cantidad recibida o aplicada;
- cuadro de diferencia, cambio o restante.

El ticket siempre debe quedar pagado completo al emitirse. Facturas y albaranes
pueden quedar `PENDIENTE`, `PARCIAL` o `PAGADO` segun los cobros registrados.

La referencia en tarjeta o transferencia sera opcional o requerida segun
configuracion de administracion/backend. Si se configura como requerida, la app
no permitira confirmar el pago sin referencia.

El cierre de caja contara cobros reales por metodo, no documentos. Los cobros
registrados en la sesion de caja son los que suman al cierre.

## Factura Y Albaran

`Ctrl + F` abre o enfoca una ventana secundaria independiente para factura o
albaran. Solo puede existir una ventana secundaria abierta a la vez.

La ventana principal de ticket seguira operativa aunque la ventana secundaria
este abierta. Se podran seguir cobrando tickets en la pantalla principal.

La ventana secundaria tendra su propio estado:

- lineas;
- cliente;
- tipo de documento: factura o albaran;
- cobro;
- estado pendiente, parcial o pagado.

Reglas:

- Factura: cliente obligatorio.
- Albaran: cliente opcional.
- La ventana puede cambiar entre factura y albaran.
- Puede guardar sin confirmar.
- Puede importar facturas/albaranes guardadas desde un desplegable.

Mientras una factura/albaran este guardada sin confirmar:

- no descuenta stock;
- no entra en cierre;
- no genera documento definitivo;
- no genera numeracion fiscal definitiva.

Al confirmar:

- descuenta stock;
- registra el documento definitivo;
- gestiona cobro pendiente, parcial o pagado.

## Ventas Aparcadas

Las ventas aparcadas de esta fase aplican solo al ticket de la pantalla
principal.

No habra botones visibles de aparcar o recuperar. Se usara solo `Ctrl + G`:

- con venta actual con lineas: aparca la venta actual;
- con venta actual sin lineas: abre la lista de ventas aparcadas.

En la lista de aparcadas:

- flechas para moverse;
- `Enter` recupera la venta seleccionada;
- `Esc` cierra.

Al recuperar una venta aparcada, se elimina de aparcadas.

## Atajos

- `Enter`: anadir o confirmar.
- `Ctrl + +`: subir cantidad.
- `Pausa`: cambiar cantidad usando el valor del campo rapido.
- `Supr`: buscar articulo o abrir listado.
- `Fin`: listado de clientes.
- `Insert`: usar cliente seleccionado o insertar producto seleccionado.
- `Ctrl + G`: aparcar o recuperar segun haya lineas.
- `Av Pag`: cobrar.
- `Ctrl + M`: cambiar entre precio mayorista y minorista.
- `Ctrl + *`: convertir paquetes a unidades.
- `/`: descuento a linea seleccionada.
- `Ctrl + /`: descuento global al ticket y lineas futuras.
- `Ctrl + F`: abrir/enfocar ventana de factura/albaran.
- `Re Pag`: cambiar precio de la linea seleccionada solo para esta venta.
- `Inicio`: cambiar nombre del producto de la linea seleccionada solo para esta
  venta.
- `Ctrl + F4`: borrar ticket completo en pantalla.
- `F5`: cerrar usuario.
- `Esc`: cancelar o cerrar dialogo.

## I18n

La interfaz inicial se mostrara en espanol. La estructura de internacionalizacion
se dejara preparada desde el inicio para espanol, ingles y chino:

- `messages_es.properties`
- `messages_en.properties`
- `messages_zh.properties`

Las pantallas importantes no deberan depender de textos hardcodeados.

## Fuera De Alcance En Esta Fase

- Implementacion funcional de **APP GESTION**.
- Modificacion directa de stock.
- Informes administrativos.
- Gestion completa de usuarios, roles y permisos desde frontend.
- Gestion completa de Veri*Factu desde frontend.
- Hardware externo avanzado: impresoras, cajon, escaner QR, pantalla cliente.
- Empaquetado final de instalador para produccion.

## Verificacion Esperada

La implementacion de esta fase debera incluir:

- pruebas unitarias de permisos y logica de comandos del campo rapido;
- pruebas de cliente API donde sea viable sin depender de backend real;
- verificacion manual de los atajos principales;
- ejecucion de `frontend/mvnw.cmd test`;
- comprobacion de que los cambios no tocan backend salvo que una tarea posterior
  lo requiera explicitamente.
