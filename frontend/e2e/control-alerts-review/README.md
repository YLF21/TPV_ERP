# Revisión visual aislada de Alertas

Desde `frontend`: `npx vite e2e/control-alerts-review --config e2e/control-alerts-review/vite.config.ts`.

Abrir `http://127.0.0.1:5182`. Monta la pantalla real y su shell; **todos** los fetch se interceptan antes del montaje, sin proxy ni conexión a la BD. Fechas relativas al día actual y datos ficticios. Solo las preferencias del escenario se conservan en localStorage, separadas por usuario ficticio.

Variantes: `?locale=en`, `?locale=zh`, `?empty`, `?reader`, `?user=OTRO`. La barra superior es exclusiva del escenario y permite simular un fallo de API y comprobar errores de ejecución/rutas sin simular. Las pruebas PostgreSQL verifican por separado la persistencia real y el aislamiento.
