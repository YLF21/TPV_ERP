# Manifiesto de release SaaS

Este manifiesto separa los archivos que forman el producto de los artefactos
locales. No sustituye la revisión del diff ni autoriza un despliegue.

## Estado e inventario del candidato

Estado auditado el 2026-09-08: `main` estaba sincronizada con `origin/main`, pero
los workflows `CI` y `Quality` del HEAD estaban rojos por una prueba de APP
GESTIÓN. El pipeline candidato separa ahora los gates SaaS, ejecuta E2E autónomo
y real y valida los contratos fiscal V49, recuperación V50 y retención V51/V52; aún debe ejecutarse remotamente y
terminar verde antes del merge.

El producto SaaS candidato contiene migraciones Flyway `V1` a `V52` (con los
huecos deliberados documentados por sus ubicaciones de perfil). El delta de
release debe contener de forma autocontenida las migraciones V49, V50, V51 y V52, sus
fuentes y pruebas, el contrato fail-closed de los futuros webhooks,
Compose, ambos ejemplos de entorno, CI, Dependabot y esta documentación. Los
proveedores webhook no forman parte del candidato actual. No se deben modificar fuentes de
`backend/` ni `frontend/` para cerrar este release SaaS.

Antes de preparar el commit se revisa el inventario real con
`git status --short --untracked-files=all`. Cualquier archivo nuevo exige
clasificación explícita; no se usa `git add .` a ciegas.

### No incluir

- `.env`, `.env.production`, claves, certificados, tokens o dumps.
- `.codex/`, `.codex-runtime/`, `.codex-tmp/`, `.codex-video-analysis/` y
  `.superpowers/brainstorm/`.
- `audits/`, `artifacts/` y `frontend-saas/design-qa.md`; contienen capturas,
  rutas absolutas de la máquina y evidencia temporal.
- `node_modules/`, `target/`, `dist/` y logs.

## Gate de commit/push

- [ ] V49, V50, V51, V52 y todas las migraciones aplican desde una base PostgreSQL vacía.
- [ ] V49 y V50 aplican sobre una copia con outboxes voluminosos dentro de la
      ventana aprobada. V49 reconstruye índices de entrega y V50 reemplaza
      constraints y recupera claims huérfanos; se han medido sus bloqueos y duración.
- [ ] V51 y V52 se ejecutan fuera de transacción como migraciones aisladas: contienen
      únicamente `CREATE INDEX CONCURRENTLY`, no queda un índice `INVALID` y su
      reintento/limpieza ante interrupción está ensayado.
- [ ] Las mismas migraciones aplican sobre una restauración anonimizada reciente.
- [ ] `git status --short --untracked-files=all` no muestra archivos sin clasificar.
- [ ] `git diff --check` no informa errores.
- [ ] La revisión staged confirma que no hay secretos ni artefactos locales.
- [ ] CI completa está verde: Java, JavaScript, Compose DEV/producción, imágenes,
      E2E autónomo fiscal/outbox, E2E de readiness/autenticación con backend real,
      AdminApi y permisos outbox con PostgreSQL, auditoría de dependencias, SBOM
      y cobertura.
- [ ] El artefacto `saas-release-evidence` corresponde al SHA candidato, contiene
      hashes e inventario de imágenes y no marca TLS, restore o RPO/RTO como
      validados cuando no se aportó infraestructura/evidencia real.
- [ ] Antes de crear un tag `saas-v*` están definidas las referencias reales
      `TPV_SAAS_PUBLIC_URL`, `TPV_SAAS_RESTORE_EVIDENCE`,
      `TPV_SAAS_ROLLBACK_EVIDENCE`, `TPV_SAAS_APPROVED_RPO`,
      `TPV_SAAS_APPROVED_RTO` y `TPV_SAAS_RPO_RTO_EVIDENCE`; el tag falla si falta alguna.
- [ ] El commit se crea en una rama de release/revisión, no directamente sobre
      `main`, y el push no se realiza hasta revisar el diff final.

## Gate de producción

- [ ] `.env.production` procede del gestor de secretos y pasa el guard de
      credenciales, bootstrap inicial, clave AES-256 y CORS HTTPS.
- [ ] Existe un proveedor externo expresamente autorizado para cada canal
      requerido; las variables webhook reservadas por sí solas no cumplen este gate.
- [ ] Los digests de imágenes del release quedan registrados y disponibles para
      rollback.
- [ ] Existe backup previo con SHA-256, copia cifrada externa y restore ensayado.
- [ ] La retención de payloads cifrados está aprobada; la purga conserva solo el
      tombstone no sensible y nunca modifica filas PENDING, PROCESSING o FAILED.
- [ ] RPO y RTO están aprobados y medidos.
- [ ] El proxy exterior supera certificado, redirección HTTPS, HSTS, cabeceras y
      rate limiting.
- [ ] `/actuator/health` y `/actuator/saasSecurity` responden 2xx dentro de la red
      del backend.
- [ ] El smoke ADMIN y tenant pasa sin utilizar credenciales locales en `prod`.
- [ ] Hay observación de logs, disco, CPU, memoria, errores de outbox y capacidad
      de rollback durante la ventana posterior al despliegue.

## Comandos de verificación

```powershell
git status --short --untracked-files=all
git diff --check
docker compose --env-file backend-saas/.env.example -f backend-saas/docker-compose.yml -f backend-saas/docker-compose.dev.yml config --quiet
docker compose --env-file backend-saas/.env.production -f backend-saas/docker-compose.yml config --quiet
docker compose --env-file backend-saas/.env.production -f backend-saas/docker-compose.yml build --pull
Push-Location backend-saas; .\mvnw.cmd verify; Pop-Location
Push-Location frontend-saas; npm ci; npm test; npm run test:e2e; npm run build; Pop-Location
```

V51 y V52 no se deben ejecutar manualmente dentro de `BEGIN/COMMIT`: PostgreSQL prohíbe
`CREATE INDEX CONCURRENTLY` en una transacción y Flyway la detecta como no
transaccional. Durante la ventana se monitorizan `pg_stat_progress_create_index`
y `pg_index.indisvalid`; una interrupción puede dejar un índice inválido que debe
eliminarse con `DROP INDEX CONCURRENTLY IF EXISTS` antes de reintentar Flyway.
