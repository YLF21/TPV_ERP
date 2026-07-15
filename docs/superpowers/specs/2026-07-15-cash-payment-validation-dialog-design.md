# Diseño: avisos de validación del cobro en efectivo

## Objetivo

Reemplazar el aviso de importe insuficiente que aparece mientras se edita el cobro por una ventana de aviso compacta, coherente con el estilo ERP de la aplicación. La validación solo se mostrará cuando el usuario intente confirmar el cobro mediante el botón o la tecla Enter.

## Comportamiento

- Mientras el usuario escribe o utiliza el teclado táctil, no se mostrará ningún mensaje de validación.
- `Confirmar cobro` permanecerá disponible mientras no haya un registro en curso.
- Al pulsar `Confirmar cobro` o Enter con `0,00`, se abrirá una ventana con el texto `Debe indicar el importe recibido.`.
- Al pulsar `Confirmar cobro` o Enter con un importe mayor que cero pero inferior al total, se abrirá una ventana con el texto `El importe recibido no cubre el total.`.
- Con un importe igual o superior al total, se conservará el flujo de confirmación actual.
- Los errores externos recibidos mediante la propiedad `error` conservarán su presentación actual y quedan fuera de este cambio.

## Ventana de aviso

La validación se mostrará sobre el diálogo de cobro en una ventana modal rectangular y compacta, siguiendo los estilos ERP existentes. Tendrá un título de aviso, el mensaje correspondiente y una única acción `Aceptar`.

La ventana deberá:

- mantener bloqueada la interacción con el diálogo de cobro mientras esté abierta;
- poder cerrarse con `Aceptar`, Enter o Escape;
- conservar el importe introducido;
- devolver el foco al campo `Dinero recibido` al cerrarse;
- respetar el atrapamiento de foco y los atributos accesibles de una ventana modal.

## Gestión del teclado

Cuando el aviso esté abierto, sus teclas Enter y Escape solo lo cerrarán y no confirmarán ni cancelarán el cobro subyacente. Cuando esté cerrado, Enter ejecutará la misma validación que el botón `Confirmar cobro`.

## Pruebas

Las pruebas cubrirán:

- ausencia del aviso mientras se introduce un importe insuficiente;
- mensaje formal para un importe vacío o de cero;
- mensaje de importe insuficiente para un valor positivo inferior al total;
- activación equivalente mediante botón y Enter;
- conservación del importe y recuperación del foco al cerrar;
- cierre del aviso con `Aceptar`, Enter y Escape;
- confirmación normal cuando el importe cubre el total;
- bloqueo de acciones durante el registro.

