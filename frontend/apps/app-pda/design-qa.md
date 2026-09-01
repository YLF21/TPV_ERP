# Design QA — Estado de carga de módulos PDA

- Source visual truth path: `C:\Users\xy656\AppData\Local\Temp\codex-clipboard-c6c21e41-f10b-4057-9441-3da147600e21.png`.
- Implementation files: `frontend/apps/app-pda/src/main.tsx` and `frontend/apps/app-pda/src/pda.css`.
- Target viewport: móvil, aproximadamente 430 × 932 CSS px (captura física 645 × 1406 px).
- Target state: carga diferida de un módulo desde «Operaciones de almacén».
- Full-view comparison: unavailable; the in-app browser runtime could not be initialized in this session.
- Focused-region comparison: unavailable for the same reason.
- Automated verification: passed (`npm run build --workspace @tpverp/app-pda`).
- Regression tests: passed (14 files, 48 tests with `npx vitest run apps/app-pda/src`).

## Findings

- [P1] Falta evidencia renderizada del nuevo estado de carga en el viewport móvil objetivo.
- [P2] Cuando vuelva a estar disponible la captura integrada, comprobar que la tarjeta aparece centrada bajo la navegación, no altera la altura de la cabecera y no provoca salto horizontal en español, inglés o chino.

## Implementation checklist

- Estado de carga estructurado y accesible con `role="status"` y `aria-live="polite"`.
- Indicador animado y barra de progreso indeterminada.
- Título del módulo y mensaje localizados en español, inglés y chino.
- Contenedor con altura estable y adaptación específica para móvil.
- Animación desactivable mediante `prefers-reduced-motion`.

- final result: blocked
