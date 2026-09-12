# Revisión aislada de la venta táctil

Renderiza el componente **real** `SaleScreen`, sus estilos y diálogos. Solo los
datos y respuestas de red son ficticios. No forma parte del paquete de APP VENTA.

Desde `frontend`:

```powershell
npx vite e2e/touch-review --config e2e/touch-review/vite.config.ts
```

Abrir `http://127.0.0.1:5181/`. Variantes: `?promotions` y `?keyboard`.

- Escanear/escribir `000101` (papel, 1,50) o `000102` (bolsa, 1,00).
- Pulsar el buscador y escribir `PAP` con el teclado táctil para filtrar.
- `?promotions` simula un 20 % sobre la primera línea para revisar el panel.
- El cliente DEMO tiene un nombre largo y deuda ficticia: permite revisar el
  espacio de los controles en 1280 × 720.
- Precio → Cambiar precio abre la autorización con teclado alfanumérico.

Todas las peticiones `fetch` se interceptan; no hay proxy ni token real.
Las rutas no previstas (incluidos cobros, impresiones y escrituras) fallan
localmente. No introducir contraseñas reales. No confundir esta revisión visual
con una validación de cobro, fiscalidad, hardware o impresión física.

El comportamiento de negocio y los permisos se comprueban con las pruebas de
`SaleScreen`, `SalePaymentCheckout` y `SaleMutationAuthorizationDialog`.
