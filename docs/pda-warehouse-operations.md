# PDA de almacén

## Alcance entregado

La aplicación `frontend/apps/app-pda` es un cliente web táctil para Android PDA. Reutiliza la autenticación, los permisos, los documentos de entrada, la comprobación de mercancía y el stock del backend local.

Fases disponibles:

1. Alta del PDA como terminal pendiente y aprobación desde APP GESTIÓN.
2. Comprobación de albaranes y facturas de entrada mediante código o código de barras.
3. Resumen y filtros de líneas escaneadas, faltantes y sobrantes, con confirmación antes de cerrar con diferencias.
4. Consulta de precio de venta y stock total/desglosado por almacén.

La comprobación no vuelve a introducir stock: compara la mercancía física contra un documento de entrada ya confirmado. Esta separación evita duplicar movimientos de existencias.

## Puesta en marcha para piloto

El backend puede continuar escuchando únicamente en `127.0.0.1`. El servidor Vite del PDA se expone a la red local y reenvía `/api/v1` al backend por loopback.

```powershell
cd frontend
$env:VITE_TPV_BACKEND_URL = "http://127.0.0.1:8080"
npm.cmd run dev:pda
```

Desde el PDA se abre:

```text
http://IP_DEL_SERVIDOR:5176
```

Para comprobar el artefacto compilado:

```powershell
cd frontend
npm.cmd run build --workspace @tpverp/app-pda
$env:VITE_TPV_BACKEND_URL = "http://127.0.0.1:8080"
npm.cmd run preview:pda
```

La previsualización queda en el puerto `4176`. Para producción debe usarse un servidor estático/reverse proxy con TLS; `vite dev` y `vite preview` son herramientas de desarrollo y validación.

## Registro del dispositivo

1. Abrir APP PDA y asignar un nombre único al dispositivo.
2. La aplicación guarda localmente el identificador y la credencial generada. El terminal todavía no puede iniciar sesión.
3. Entrar en APP GESTIÓN con `ADMIN` o `TERMINALS_MANAGE`.
4. Abrir **Seguridad → Terminales y PDA**.
5. Seleccionar la solicitud pendiente y pulsar **Aprobar PDA**.
6. Volver al PDA e iniciar sesión con un usuario que tenga `APP_GESTION_ACCESS` y `GESTION_ALMACEN`.

Al desactivar un PDA desde APP GESTIÓN se revocan sus sesiones abiertas. El botón **Registrar otro dispositivo** elimina la identidad guardada en ese navegador, pero no desactiva el registro del backend.

## Flujo de comprobación

1. Seleccionar un albarán o una factura de entrada confirmada.
2. Pulsar **Iniciar comprobación**.
3. Escanear el código. Los lectores configurados como teclado pueden enviar `Enter` y registrar directamente.
4. La cantidad predeterminada es `1`; se permiten valores positivos y negativos para corregir el acumulado sin bajar de cero.
5. Usar los filtros **Todas**, **Con diferencias** y **Escaneadas**.
6. Pulsar **Finalizar comprobación**. Si quedan faltantes o sobrantes se solicita confirmación explícita.

## Consulta de producto

La pestaña **Consultar producto** acepta código interno o código de barras. Muestra únicamente datos comerciales públicos para el rol de almacén:

- precio de venta activo;
- stock total;
- stock por almacén.

No devuelve el precio de compra. La consulta de coste y la edición del artículo continúan sujetas a permisos de gestión de producto.

## Seguridad de red

- Utilizar una red Wi-Fi de confianza y aislada para el piloto.
- Usar HTTPS antes de desplegar en una red compartida o no controlada.
- No incluir credenciales de terminal en variables `VITE_*`, URLs, QR ni archivos versionados.
- Desactivar inmediatamente un dispositivo perdido.
- Mantener la aprobación manual: una solicitud pública no obtiene acceso hasta que un administrador la aprueba.

## Fase pendiente: etiquetas

La impresión se conectará cuando se conozcan el modelo de impresora, el ancho de etiqueta, el protocolo (`ZPL`, `TSPL`, `ESC/POS` u otro) y la conexión (red, USB o Bluetooth). La integración deberá usar un adaptador de impresión y no enviar comandos específicos del fabricante desde la lógica de comprobación.
