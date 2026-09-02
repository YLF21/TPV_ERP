# Manifiesto de release SaaS

Este manifiesto separa los archivos que forman el producto de los artefactos
locales. No sustituye la revisión del diff ni autoriza un despliegue.

## Inventario pendiente de versionar

La fotografía usada para preparar este manifiesto contenía 64 archivos sin
seguimiento. `frontend-saas/design-qa.md` es el único artefacto local de ese
grupo y queda ignorado. Los otros 63 son parte del cambio funcional. También se
deben incluir este manifiesto y `.github/dependabot.yml`, creados durante el
endurecimiento de release.

### Incluir en el commit

- `.github/dependabot.yml` y `backend-saas/RELEASE_CHECKLIST.md`.
- `backend-saas/OPERATIONS_PHASE2.md`.
- Los 29 archivos sin seguimiento bajo
  `backend-saas/src/main/java/com/tpverp/saas/`: cabeceras de seguridad, estado
  persistente, ciclo de password, canales/outbox, conciliación, CSV y límites
  de plan. Son dependencias de fuentes ya modificadas y no son opcionales.
- `backend-saas/src/main/resources/application-local.yml`.
- `backend-saas/src/main/resources/db/local/R__saas_local_admin_credentials.sql`.
- Las migraciones `V43` a `V47` bajo
  `backend-saas/src/main/resources/db/migration/`.
- Los 18 archivos sin seguimiento bajo `backend-saas/src/test/`, incluido
  `src/test/resources/db/test/R__test_admin_password_change_bypass.sql`.
- `frontend-saas/e2e/saas-smoke.mjs`.
- `frontend-saas/src/lib/frontend-runtime.d.mts` y
  `frontend-saas/src/lib/frontend-runtime.mjs`.
- Los cinco archivos sin seguimiento bajo `frontend-saas/test/`.
- Todos los archivos ya seguidos que aparecen modificados en `git status`.

Antes de preparar el commit se vuelve a contar el inventario. Cualquier archivo
nuevo exige clasificación explícita; no se usa `git add .` a ciegas.

### No incluir

- `.env`, `.env.production`, claves, certificados, tokens o dumps.
- `.codex/`, `.codex-runtime/`, `.codex-tmp/`, `.codex-video-analysis/` y
  `.superpowers/brainstorm/`.
- `audits/`, `artifacts/` y `frontend-saas/design-qa.md`; contienen capturas,
  rutas absolutas de la máquina y evidencia temporal.
- `node_modules/`, `target/`, `dist/` y logs.

## Gate de commit/push

- [ ] V47 y todas las migraciones aplican desde una base PostgreSQL vacía.
- [ ] Las mismas migraciones aplican sobre una restauración anonimizada reciente.
- [ ] `git status --short --untracked-files=all` no muestra archivos sin clasificar.
- [ ] `git diff --check` no informa errores.
- [ ] La revisión staged confirma que no hay secretos ni artefactos locales.
- [ ] CI completa está verde: Java, JavaScript, Compose, imágenes, E2E,
      auditoría de dependencias, SBOM y cobertura.
- [ ] El commit se crea en una rama de release/revisión, no directamente sobre
      `main`, y el push no se realiza hasta revisar el diff final.

## Gate de producción

- [ ] `.env.production` procede del gestor de secretos y pasa el guard de
      credenciales, clave AES-256 y CORS HTTPS.
- [ ] Los digests de imágenes del release quedan registrados y disponibles para
      rollback.
- [ ] Existe backup previo con SHA-256, copia cifrada externa y restore ensayado.
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
docker compose --env-file backend-saas/.env.production -f backend-saas/docker-compose.yml config --quiet
docker compose --env-file backend-saas/.env.production -f backend-saas/docker-compose.yml build --pull
Push-Location backend-saas; .\mvnw.cmd verify; Pop-Location
Push-Location frontend-saas; npm ci; npm test; npm run build; Pop-Location
```
