# Design QA — cobro en efectivo de deuda

## Source visual

- Reference: `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-4ce271b4-9da8-4391-b3ac-9e1f3c5109c1.png`
- Target state: successful cash collection with ticket print failure.
- Reference canvas: 725 × 525 px.

## Rendered implementation

- Screenshot: `C:/Users/xy656/.codex/visualizations/2026/07/19/019f7c0d-2885-7dc1-a54c-9fc95d9b0872/payment-completed-implementation.png`
- Browser viewport: 1280 × 720 CSS px at DPR 1.5.
- State: `Pago completado`, total 5,14, received 5,14, change 0,00, print failure and retry action.
- Capture used a temporary local render of the production component and was removed after capture; no receivable or payment was mutated.

## Comparison

- Layout hierarchy matches: success header, ticket, three amount rows, print failure alert and full-width finish action.
- Typography, colors, borders and status treatments follow the reference and the application's compact ERP scale.
- The reference is a tight crop; the implementation capture includes the complete application viewport, so the dialog appears smaller in the full-frame image while retaining its intended production dimensions.
- Keyboard focus lands on `Finalizar`, and the modal exposes a labelled dialog plus alert semantics.

## Iteration history

1. Reused the existing payment-result visual language and connected it to successful receivable cash payments.
2. Restyled the payment-method chooser as a clear three-option action group.
3. Verified the chooser and cash calculator in the running app, then rendered the success state without writing financial data.

## Final result

Passed.

---

# Design QA — APP VENTA: carga, comprobación y búsqueda

## Fuente visual y alcance

- Pantalla de carga original: `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-a275af95-14cf-47bd-8c07-a18be05d270a.png`
- Comprobación de pedido original: `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-e5be2e24-6f7c-40a5-9993-d00842138ff0.png`
- Búsqueda de productos original: `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-fb017efb-3b33-446c-8f49-aef8c20dc68f.png`
- Estado verificado: carga de APP VENTA, comprobación de pedido sin documentos y búsqueda con dos resultados.
- Viewport de implementación: 1280 × 720. Las capturas originales tenían otra relación de aspecto; la comparación valida jerarquía, composición y estados, no coincidencia píxel a píxel.

## Evidencia de implementación

- Carga: `C:/Users/xy656/.codex/visualizations/2026/07/10/019f4b3e-5b24-7cd3-8d99-5a626e02540c/app-venta-loading-after.png`
- Comprobación de pedido: `C:/Users/xy656/.codex/visualizations/2026/07/10/019f4b3e-5b24-7cd3-8d99-5a626e02540c/goods-check-after.png`
- Comparación carga: `C:/Users/xy656/.codex/visualizations/2026/07/10/019f4b3e-5b24-7cd3-8d99-5a626e02540c/app-venta-loading-comparison.png`
- Comparación comprobación: `C:/Users/xy656/.codex/visualizations/2026/07/10/019f4b3e-5b24-7cd3-8d99-5a626e02540c/goods-check-comparison.png`

## Comparación y hallazgos

1. **Carga (P1 resuelto):** el texto aislado en la esquina se sustituyó por un estado centrado con marca, jerarquía clara, progreso nativo, texto localizado y pie de producto.
2. **Comprobación de pedido (P2 resuelto):** la tabla comprimida y el vacío poco legible se reorganizaron en dos paneles equilibrados, con contador, estados vacíos y espacios consistentes.
3. **Búsqueda de productos (P1 resuelto):** el primer resultado continúa seleccionado por defecto; `ArrowUp` y `ArrowDown` cambian la opción activa y `Enter` confirma la activa.
4. **Accesibilidad (verificado):** el cargador expone `role=status`, `aria-live`, `aria-busy` y un `progressbar` con nombre; la selección de producto actualiza `aria-selected`.
5. **Consola del navegador:** sin errores durante la comprobación final. El mensaje de operación que puede mostrar la pantalla de almacén procede de la respuesta del backend y no del nuevo esquema visual.

## Historial de iteración

- Iteración 1: reproducción de los tres estados y localización de los puntos de fallo.
- Iteración 2: nueva composición de carga y almacén; navegación de resultados con teclado.
- Iteración 3: prueba visual en navegador, comprobación de `ArrowDown`/`ArrowUp`/`Enter`, compilación y suite focalizada.

## Resultado final

**APROBADO** para el alcance solicitado. No quedan defectos P0, P1 o P2 conocidos en estos tres cambios.

---

# Design QA - boton Volver en Deudas de clientes

## Fuente visual y alcance

- Captura de referencia: `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-a0b45d78-aa49-4e7f-8dd3-6031c7ea9571.png`.
- Dimensiones de referencia: 3840 x 1907 px.
- Alcance: mejorar la jerarquia, el area tactil y los estados interactivos del boton `Volver` sin cambiar su posicion ni la navegacion.

## Evidencia de implementacion

- Captura del navegador: `E:/workspace/gitwork/TPV_ERP/.codex/back-button-implementation.png`.
- Dimensiones de implementacion: 1265 x 712 px.
- Comparacion conjunta: `E:/workspace/gitwork/TPV_ERP/.codex/back-button-comparison.png`.
- Estado verificado: listado de deudas abiertas con el boton visible en la cabecera y retorno correcto a la pantalla de venta.

## Comparacion y hallazgos

1. **Jerarquia (P2 resuelto):** la accion deja de usar el estilo HTML basico y adopta el azul principal de APP VENTA.
2. **Usabilidad (P2 resuelto):** el objetivo de interaccion crece hasta un minimo de 7.5 rem por 2.75 rem, adecuado para raton y pantalla tactil.
3. **Estados (P2 resuelto):** se incorporan estados `hover`, `active` y `focus-visible` claramente diferenciados.
4. **Composicion:** conserva la alineacion superior derecha y no reduce el espacio disponible para filtros ni para la tabla.
5. **Comportamiento y consola:** el clic vuelve a Venta y no aparecen errores de consola durante la comprobacion.

## Historial de iteracion

1. Identificacion del boton nativo pequeno y aislado.
2. Aplicacion de la accion secundaria de APP VENTA con tamano tactil, sombra contenida y foco accesible.
3. Prueba focalizada, compilacion, captura conjunta y validacion de la navegacion en el navegador local.

## Final result

passed

---

# Design QA - campo numerico de configuracion de stock

## Fuente visual y alcance

- Referencias: `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-29021d34-b8a4-45f2-a5ca-ca196b9e2d46.png` y `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-368e8d65-92d5-42bc-828b-c6b7796db9d4.png`.
- Alcance: unificar los campos `Minimo general` y `Cantidad minima` con el selector de almacen y con el resto de controles del dialogo.

## Evidencia de implementacion

- Se eliminaron los controles numericos nativos del navegador que alteraban el aspecto del campo.
- Ambos campos comparten 38 px de altura, borde recto y el mismo color de linea que el selector de almacen.
- El foco utiliza ahora un unico borde azul compacto, sin el halo exterior duplicado.
- La misma regla se aplica al minimo general y al minimo especifico para mantener consistencia interna.
- `StockSettingsDialog.test.tsx`: 8 pruebas superadas.
- Compilacion de produccion de `@tpverp/app-venta`: correcta.

## Final result

passed

---

# Design QA - selector coherente en Configuracion stock

## Fuente visual y alcance

- Referencia: `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-8acf7f9f-8bee-48b3-826d-c02e673ef943.png`.
- Alcance: igualar el selector de almacen predeterminado con los desplegables de APP VENTA y eliminar la franja azul recortada que aparecia al abrirlo.

## Evidencia de implementacion

- El contenedor del selector deja de dibujar un segundo borde y conserva como unico marco visible el control interactivo.
- El panel de opciones ocupa exactamente el ancho del campo y mantiene una separacion uniforme de 2 px respecto al disparador.
- El foco de teclado se representa mediante la fila activa; no genera un `outline` recortado en el lateral del panel.
- La opcion activa no seleccionada usa el mismo azul suave que los demas desplegables, mientras que la opcion seleccionada conserva el azul principal.
- Las 12 pruebas de `ErpSelect` y `StockSettingsDialog` pasan.
- La compilacion de produccion de `@tpverp/app-venta` finaliza correctamente.

## Resultado final

APROBADO.

---

# Design QA - bloque de cupon promocional en APP VENTA

## Fuente visual y alcance

- Referencia: `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-85fef07f-f343-4b3c-a9c3-00d376564ba4.png`.
- Alcance: mejorar la separacion, jerarquia y legibilidad del bloque de cupon sin cambiar el flujo funcional.
- Estado validado: venta vacia, controles de cupon y cobro deshabilitados.
- Viewport de comparacion: 2048 x 962 px, igual al de la captura de referencia.

## Comparacion y hallazgos

1. El bloque adopta los mismos margenes laterales que Gestion y Cobro, evitando que parezca pegado a los bordes.
2. El titulo, la etiqueta y el formulario utilizan una escala tipografica compacta y consistente con APP VENTA.
3. El campo ocupa el ancho disponible y la accion conserva un ancho minimo estable, sin comprimir el texto.
4. Los estados de foco, hover, deshabilitado, exito y error tienen contraste y separacion propios.
5. En pantallas estrechas el campo y la accion pasan a una sola columna.
6. La compilacion de produccion de `@tpverp/app-venta` finaliza correctamente.

## Resultado final

APROBADO.

---

# Design QA - cabecera de comprobacion de pedido

## Fuente visual y alcance

- Referencia: `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-cd4aad5e-1883-4c43-94af-8ab5bee10bc5.png`.
- Alcance: evitar que el foco del buscador se solape visualmente con el resumen de facturas y albaranes confirmados.

## Evidencia de implementacion

- Captura del navegador: `E:/workspace/gitwork/TPV_ERP/.codex/goods-check-header-after.png`.
- Comparacion conjunta: `E:/workspace/gitwork/TPV_ERP/.codex/goods-check-header-comparison.png`.
- Viewport de validacion: 1280 x 720 px.
- Medida con el buscador enfocado: 12 px libres entre el borde inferior del campo y el inicio del resumen.
- Cabecera de busqueda: padding `14px 14px 12px`; resumen: altura minima de 46 px.

## Comparacion y hallazgos

1. El buscador, la accion de importar y el resumen forman ahora una cabecera unica de dos niveles.
2. El anillo de foco permanece dentro del primer nivel y conserva una separacion visible respecto al resumen.
3. El contador de documentos queda aislado en una tarjeta propia, con jerarquia y lectura mas claras.
4. En pantallas estrechas la accion de importar pasa debajo del buscador y ocupa todo el ancho disponible.
5. Las pruebas de componentes y la compilacion de produccion de APP VENTA finalizan correctamente.

## Final result

passed

---

# Design QA - traduccion del detalle de operaciones de pago

## Fuente visual y alcance

- Referencia: `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-60c3b5be-95e9-4721-9ad0-fb4b938ff0fd.png`.
- Alcance: eliminar los textos en espanol, ingles y codigos tecnicos sin traducir dentro de la recuperacion y gestion de pagos cuando APP VENTA utiliza chino.

## Evidencia de implementacion

- Las acciones de consulta, anulacion, reembolso y reimpresion usan el traductor activo.
- Los estados y eventos del proveedor (`PENDING`, `GATEWAY_SEND`, `TIMEOUT`, entre otros) se presentan como mensajes comprensibles en espanol, ingles o chino.
- Los diagnosticos procedentes del backend se normalizan y traducen sin modificar el estado ni los datos reales de la operacion.
- La vista de APP VENTA se comprobo en chino en el navegador local sin regresiones de distribucion.
- Las pruebas de componentes cubren los escenarios de timeout e idempotencia y rechazan la aparicion de las cadenas tecnicas originales.
- La compilacion de produccion de `@tpverp/app-venta` finaliza correctamente.

## Final result

passed

---

# Design QA - color diferenciado del boton Volver lateral

## Fuente visual y alcance

- Referencia: `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-c24445ba-e090-46cd-8f59-3230b579ff93.png`.
- Alcance: diferenciar visualmente `Volver` de las opciones activas en Ajustes, Producto, Informes y Almacen sin cambiar su comportamiento.

## Evidencia de implementacion

- Captura del navegador: `E:/workspace/gitwork/TPV_ERP/.codex/sidebar-back-color-after.png`.
- Viewport: 1280 x 720 px.
- Estado medido: fondo `rgb(220, 229, 238)`, texto `rgb(24, 52, 79)` y borde `rgb(159, 176, 195)`.

## Comparacion y hallazgos

1. `Volver` ya no se confunde con una opcion blanca normal ni con la seleccion azul activa.
2. El gris azulado conserva el lenguaje visual de APP VENTA y la marca lateral refuerza que es una accion de salida.
3. Los estados `hover` y `focus-visible` aumentan el contraste sin alterar el ancho ni la posicion del menu.
4. La misma regla se aplica a los cuatro menus laterales solicitados y a la configuracion de hardware.

## Resultado final

APROBADO.

---

# Design QA - alineacion estable del editor masivo

## Fuente visual y alcance

- Referencias: `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-2527b606-8a9d-4276-ba4a-015ff924ac69.png`, `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-0d47dbe5-976d-4b47-94f4-e2173851863a.png`, `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-5af0fcef-bbb4-4be7-91d0-7d03b8d9c880.png` y `C:/Users/xy656/AppData/Local/Temp/codex-clipboard-a2788d6a-8f89-413d-ab25-c8b52ae6df2b.png`.
- Alcance: mantener el encabezado, las pestanas y el area de trabajo en la misma posicion al cambiar entre pagina principal, informacion, precios e imagenes.

## Evidencia de implementacion

- Comparacion conjunta antes/despues: `C:/Users/xy656/AppData/Local/Temp/tpv-bulk-alignment-qa/bulk-alignment-comparison.png`.
- Captura de pagina principal: `C:/Users/xy656/AppData/Local/Temp/tpv-bulk-alignment-qa/main-tab-fixed.png`.
- Captura de imagenes: `C:/Users/xy656/AppData/Local/Temp/tpv-bulk-alignment-qa/image-tab-fixed.png`.
- Viewport de validacion: 1280 x 720 px.
- Medidas constantes en pagina principal, informacion, precio e imagenes: panel exterior `x=6.67`, `width=1266.67`; encabezado `x=14.67`, `width=1258.67`; pestanas `x=532.53`, `width=722.80`; `scrollX=0`.

## Comparacion y hallazgos

1. **Alineacion (P1 resuelto):** el editor masivo ya no hereda la columna 2 reservada para la lista de stock normal; su panel ocupa explicitamente la unica columna de su rejilla.
2. **Consistencia:** titulo, pestanas y barra de acciones conservan exactamente la misma posicion al cambiar de seccion.
3. **Tablas anchas:** cada tabla mantiene su desplazamiento horizontal interno sin mover el contenedor completo.
4. **Imagenes:** el panel secundario de imagenes conserva su distribucion de dos columnas dentro del mismo marco exterior.
5. **Consola:** no aparecen errores funcionales asociados a esta correccion; se observan avisos React preexistentes por claves duplicadas en filas, fuera del alcance de esta alineacion.

## Historial de iteracion

1. Reproduccion del salto entre pagina principal e imagenes y medicion de sus contenedores.
2. Identificacion de la colision entre la regla global de `stock-list` y la rejilla de una sola columna del editor masivo.
3. Fijacion explicita del area de trabajo en la columna 1, seguida de pruebas unitarias, compilacion y validacion visual en las cuatro secciones.

## Final result

passed
# Design QA - distribución de columnas del directorio de clientes

## Referencia

- Captura revisada: `codex-clipboard-4d32d46d-387c-4934-adc5-0ebd5b06b089.png`.
- Vista: `Producto > Clientes`.

## Ajuste aplicado

- `Código`, `Documento`, `Teléfono` y `Estado` conservan anchos compactos.
- `Nombre / Razón social` aprovecha parte del espacio flexible.
- `Email` y `Población / Provincia` reciben más ancho y mantienen cualquier ancho personalizado como mínimo.
- La tabla ocupa todo el panel disponible y conserva desplazamiento horizontal en ventanas estrechas.
