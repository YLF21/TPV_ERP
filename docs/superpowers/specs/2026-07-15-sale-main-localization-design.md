# Diseño: localización de la vista principal de Venta

## Objetivo

Hacer que el selector de idioma traduzca de forma completa la vista principal de Venta a español, inglés y chino, y recordar la selección de forma independiente para cada usuario autenticado.

## Causa actual

El selector actualiza correctamente el `locale` y algunos componentes ya utilizan el catálogo de mensajes. Sin embargo, la mayoría de los textos visibles de `SaleScreen` están escritos directamente en español, por lo que no reaccionan al cambio de idioma. Además, `app-venta` inicializa siempre el idioma en español y no guarda una preferencia por usuario.

## Alcance de la traducción

La traducción cubrirá únicamente la vista principal de Venta:

- título de la pantalla y regiones accesibles;
- cabecera y contador de líneas de venta;
- estados de venta vacía o reservada;
- etiquetas de total, producto, búsqueda y cobro;
- descripción y placeholder de la búsqueda;
- estados de carga, error, reintento y ausencia de resultados;
- textos alternativos para productos o códigos ausentes;
- acciones rápidas de cantidad, descuento, cliente y anulación;
- barra inferior de atajos;
- textos accesibles asociados a estas zonas y controles.

Los componentes ya localizados que forman parte de la vista, como los controles superiores, el pie de contexto y las acciones principales de `SalePaymentCheckout`, seguirán recibiendo el mismo `locale`.

## Fuera de alcance

- Ventanas de cantidad, descuento, selección de cliente, anulación y cobro.
- Nombres, códigos y demás datos procedentes de productos o clientes.
- Formatos numéricos, monetarios, de fecha u hora.
- Otras pantallas de APP VENTA o APP GESTIÓN.

## Catálogo de mensajes

Los textos de la vista principal usarán claves específicas bajo el prefijo `sale.main.*` en `MessagesEs`, `MessagesEn` y `MessagesZh`. No se introducirán ternarios por idioma ni una segunda biblioteca de traducción.

Los contadores tendrán variantes separadas para cero/estado vacío, un producto y varios productos, evitando construir plurales españoles dentro del componente. Los valores dinámicos, como el número de productos o el nombre del cliente, se insertarán mediante un helper de interpolación controlado por la vista.

## Preferencia por usuario

La preferencia se guardará en `localStorage` con una clave limitada a APP VENTA y al identificador estable del usuario:

```text
tpv-erp:venta:user:<userId-o-username>:locale
```

El segmento de identidad usará `userId` cuando exista; en caso contrario usará `username`. El valor se normalizará y codificará antes de formar la clave para evitar colisiones por mayúsculas, espacios o caracteres especiales.

Reglas:

- Antes de iniciar sesión, la aplicación se mostrará en español.
- Después de un login correcto, se leerá la preferencia del usuario y se aplicará inmediatamente.
- Al cambiar el idioma con una sesión activa, se actualizará la interfaz y se guardará el nuevo valor.
- Al cerrar sesión, el locale volverá a español.
- Usuarios distintos en el mismo terminal conservarán preferencias independientes.
- Solo se aceptarán `es`, `en` y `zh`; cualquier valor ausente o inválido producirá español.
- Si `localStorage` no está disponible, la aplicación seguirá funcionando en español o con el idioma elegido durante la sesión, sin bloquear el acceso.

La lectura, validación y escritura vivirán en un módulo pequeño y probado, separado del montaje de React.

## Formato y datos

El cambio solo afecta a textos. Los importes conservarán el formato actual basado en `es-ES`, incluso en inglés y chino. Los nombres y códigos del catálogo se mostrarán exactamente como llegan del servidor.

## Pruebas

Las pruebas cubrirán:

- claves `sale.main.*` presentes en los tres catálogos;
- render de la vista principal en español, inglés y chino;
- ausencia de los textos fijos españoles principales al renderizar inglés o chino;
- interpolación del contador y del cliente seleccionado;
- lectura y escritura de una preferencia válida;
- rechazo de valores guardados inválidos;
- independencia entre dos usuarios;
- español antes del login, carga tras autenticar y vuelta a español al cerrar sesión;
- mantenimiento del formato monetario actual.
