# Revisión de Resumen

Desde `frontend`: `npx vite e2e/dashboard-review --config e2e/dashboard-review/vite.config.ts`.
Abrir `http://127.0.0.1:5183/`. Se monta la pantalla real con datos ficticios y todas las peticiones interceptadas; no hay proxy al backend de tienda.

Variantes: `?locale=en`, `?locale=zh`, `?empty`, `?negative`, `?restricted`, `?load-error`, `?user=OTRO`. Las preferencias ficticias se guardan en localStorage separado por usuario. Los botones del encabezado permiten comprobar fallo de lectura y guardado, recuperación y navegación.

La persistencia PostgreSQL y los datos agregados reales se verifican en las pruebas backend; este escenario comprueba composición e interacción.
