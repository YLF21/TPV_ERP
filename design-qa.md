# Design QA — Configuración APP VENTA

source visual truth path: `C:\Users\YLF\Documents\TPV ERP\output\design\app-venta-configuracion-v2-2026-08-14\00-venta-y-cobro-responsable.png`

implementation screenshot path: `C:\Users\YLF\.codex\visualizations\2026\08\13\019ffc34-3710-76a3-bca4-7b925aafd9aa\settings-implementation.png`

comparison image path: `C:\Users\YLF\.codex\visualizations\2026\08\13\019ffc34-3710-76a3-bca4-7b925aafd9aa\settings-comparison.png`

all implemented screens contact sheet: `C:\Users\YLF\.codex\visualizations\2026\08\13\019ffc34-3710-76a3-bca4-7b925aafd9aa\settings-all-screens.png`

viewport: 1536 × 1024 CSS px, Electron Chromium, deviceScaleFactor 1.

source pixels: 1488 × 1058. Implementation pixels: 1536 × 1024. The side-by-side comparison normalizes the source to 768 × 546 and the implementation to 768 × 512; all fidelity decisions were also checked against both original-resolution captures.

state: Spanish locale, responsible/ADMIN session with `CONFIGURACION_TERMINAL`, terminal `SERVIDOR`, destination `Venta y cobro`. The QA backend supplied deterministic operational data only for rendering; the production UI and routes are the implementation under test.

## Findings

- No actionable P0, P1 or P2 differences remain.
- The implemented shell preserves the reference hierarchy: APP VENTA and Configuración header, grouped personal/workstation/support navigation, protected terminal scope, white operational workspace, navy selected state and persistent context footer.
- The implementation intentionally contains more vertical content than the visual target because it retains the complete real datafono and cash-operation functionality instead of replacing it with the simplified mock fields. The workspace scroll is visible and keeps the shell/navigation fixed; this is an expected product constraint, not design drift.

## Required fidelity surfaces

- Fonts and typography: the implementation uses the existing APP VENTA business typography and weights. Heading, group-label, form-label and body hierarchy match the source intent; no clipping or broken wrapping remains at the target viewport.
- Spacing and layout rhythm: the 320 px navigation rail, 64 px header, full-height workspace and fixed footer reproduce the main proportions. Dividers, section gaps and control grouping remain dense and scan-friendly.
- Colors and visual tokens: navy, white, muted blue-gray, green terminal context and blue selected/scope states map to the visual source with sufficient contrast.
- Image quality and asset fidelity: the screen does not require photographic or decorative raster assets. Navigation uses the Phosphor icon library rather than text glyphs, emoji, CSS art or handcrafted SVG substitutes.
- Copy and content: visible Spanish copy reflects the real contracts. Password remains numeric 4–12 digits, printing keeps four document routes, and terminal-scoped copy is explicit.
- Accessibility and interaction states: navigation uses semantic buttons and `aria-current`; selected language uses `aria-pressed`; forms retain labels, disabled states and focus-visible treatment. Protected destinations are absent without permission rather than merely disabled.

## Full-view comparison evidence

The combined comparison image contains source and implementation in the same visual input. The final implementation matches the reference information architecture and overall density. Dynamic values differ only where expected (`Tienda Principal · Caja 01` versus the QA terminal context).

## Focused region comparison evidence

A separate crop was not required after the final pass because the original 1488/1536 px captures clearly expose the header, navigation icons, selected state, scope badge, form controls and footer. Those regions were inspected at original resolution before reviewing the combined comparison.

## Primary interactions tested

- Login and compatibility gate.
- Open Configuración from APP VENTA.
- Navigate to Mi cuenta, Seguridad, Informes, Venta y cobro, Dispositivos, Impresión y etiquetas and Diagnóstico.
- Confirm the language selector is part of Mi cuenta and no duplicate navigation destination remains.
- Confirm Caja y turno is the first operational section in Venta y cobro.
- Confirm printer detection and test printing call the Electron hardware bridge in desktop mode and are visibly unavailable in browser preview mode.
- Confirm each destination renders its real component and maintains the shared shell.
- Verify the production tests cover protected routing and the real product-label bridge callback.

## Console errors checked

No renderer errors were captured. The development run emitted only Vite connection messages, the React DevTools informational message and Electron's expected development-only CSP warning. The warning is not present in the packaged application.

## Comparison history

1. Initial comparison: P1 layout failure. Global legacy `.settings-nav` and `.settings-workspace` rules forced both regions into grid row 1, leaving the operational content clipped. Fix: the dedicated settings stylesheet now owns shell height and grid rows/columns with scoped higher-specificity rules. Post-fix evidence: navigation, workspace and footer occupy the intended three-row layout at 1536 × 1024.
2. Second comparison: P2 header and surface drift. The Configuración title was hidden by a legacy absolute title rule, unselected navigation items inherited boxed borders and the workspace inherited a gray background. Fix: scoped title positioning, white surfaces, borderless navigation and navy selected-state overrides. Post-fix evidence: `settings-implementation.png` and `settings-comparison.png` show the corrected header, rail and workspace.
3. Browser annotation pass: the report/password actions now use compact navy primary buttons with real icons; language is consolidated inside Mi cuenta; Caja y turno precedes the other Venta y cobro settings; and `ErpSelect` positions its popover against the viewport so a scrolling ancestor cannot crop it. Hardware-only printer actions no longer appear operational in a browser, while desktop tests verify the real bridge calls.
4. Cash-opening annotation pass: the opening form now has consistent vertical rhythm and a dedicated action footer. A 12 px separation and subtle divider keep Preparar fondo and Abrir caja visually detached from the comment input without changing their behavior or keyboard order.

## Open Questions

- No implementation blocker. A physical Windows printer remains necessary to validate the final paper output and installed-driver behavior.

## Implementation Checklist

- [x] Shared shell and grouped navigation.
- [x] Personal destinations available without terminal-configuration permission.
- [x] Protected destinations hidden and route-guarded.
- [x] Devices, printing and diagnostics use the same UI.
- [x] All primary destinations visually captured in the original comparison pass.
- [x] Browser annotations implemented without changing Home.
- [x] Shared dropdown constrained to the viewport instead of the scrolling workspace.
- [x] Desktop-only printer actions expose honest browser states and retain real Electron behavior.
- [x] Home component and Home styles unchanged.
- [x] Production tests, builds and bundle budgets validated.

## Follow-up Polish

- P3: if the real payment-terminal contract is simplified later, the first viewport could more closely match the mock's single-row datafono summary. No current functionality should be removed solely for visual compactness.

previous result: implementation and automated QA passed; physical printer validation remains external.

## Menu parity follow-up — 2026-08-14

source visual truth path: `C:\Users\YLF\AppData\Local\Temp\codex-clipboard-52ab84bc-b659-45fc-b0aa-2d98864229fd.png`

scope: make the APP VENTA Warehouse, Reports and Settings side menus follow the compact Product menu language without changing routes, permissions or behavior.

### Implemented checks

- Warehouse already renders the shared `stock-nav` used by Product; no duplicate component or CSS path was introduced.
- Reports keeps its existing `report-nav` contract while matching Product's spacing, typography, neutral buttons and selected state; its original report icons are visible again.
- Settings keeps its permission-aware grouped navigation while matching Product's fixed 190 px rail, compact neutral buttons, blue selected state and amber Back action.
- Focused component coverage passes for Product, Warehouse, Reports and Settings: 4 files, 108 tests.
- APP VENTA production build and bundle-budget checks passed before the final structural test assertions; the focused suite was rerun after those assertions.

### Blocker

A fresh authenticated implementation capture was not available after the current CSS pass. Therefore visual comparison against the supplied narrow-viewport reference remains pending and pixel-level fidelity is not claimed.

previous result: blocked

## Shared module navigation follow-up — 2026-08-14

source visual truth paths:

- `C:\Users\YLF\AppData\Local\Temp\codex-clipboard-c356065e-e737-474f-8bc6-d38ddf640962.png` — Product, 515 × 1079 px.
- `C:\Users\YLF\AppData\Local\Temp\codex-clipboard-e8d16dce-fe3f-4e9c-a10e-9915117bdb5f.png` — Warehouse, 503 × 1079 px.
- `C:\Users\YLF\AppData\Local\Temp\codex-clipboard-d2b8ccdf-94e5-4818-a0dc-e6ad3e9d2d86.png` — Reports, 431 × 1036 px.
- `C:\Users\YLF\AppData\Local\Temp\codex-clipboard-4cd14940-46c4-4ac7-bb65-fdae18ebe798.png` — Settings, 428 × 1079 px.

implementation screenshot path: unavailable; the browser-rendered application remained at the ADMIN login after the documented demo login returned a connection error.

viewport: source captures use narrow desktop crops with widths between 428 and 515 px and heights between 1036 and 1079 px. The blocked browser evidence was captured at 1280 × 720 CSS px, deviceScaleFactor 1, and is not a valid same-state comparison.

state: Spanish locale, terminal SERVIDOR. Source images show authenticated Product, Warehouse, Reports and Settings screens. Browser evidence shows the unauthenticated login state, so no pixel-level comparison was attempted.

### Findings

- [P1] Fresh implementation capture unavailable. The source and browser states do not match, so visual fidelity cannot be passed.
- The implementation now uses one real `ModuleNavBackButton` in all four modules, backed by the existing Phosphor icon library and each screen's real `onBack` callback.
- The four full-height navigation layouts share the same outer white surface, 190 px rail, compact menu controls, divider rhythm and bottom-anchored Back action.
- Settings group padding was removed to match Product's section rhythm, and its workspace-to-navigation separation was reduced to the same 8 px used by the other modules.

### Required fidelity surfaces

- Fonts and typography: existing APP VENTA tokens and weights are unchanged; fresh rendered comparison remains blocked.
- Spacing and layout rhythm: shared CSS and component contracts now encode the requested dimensions and bottom anchoring; fresh rendered comparison remains blocked.
- Colors and visual tokens: all modules use the same white surface, blue selection and amber Back action tokens.
- Image quality and asset fidelity: no raster imagery is required; the Back icon comes from the existing Phosphor icon library.
- Copy and content: labels, routes, permissions and screen-specific group names remain unchanged.

### Full-view comparison evidence

Not available in the same authenticated state. The emitted browser capture only proves the authentication blocker and is not used as visual evidence of the module menus.

### Focused region comparison evidence

Not available because the implementation menus could not be opened in the browser session.

### Primary interactions tested

- The shared Back control renders its icon and calls the supplied navigation callback.
- Product, Warehouse, Reports and Settings render the shared icon contract.
- Focused suite: 5 files, 109 tests passed.
- APP VENTA production build and bundle-budget check passed.

### Console errors checked

No module-screen console pass was possible because authentication did not complete.

### Comparison history

1. Source review found inconsistent panel surfaces, Settings group spacing and Back placement.
2. The implementation restored a shared outer white panel, normalized Settings spacing and moved Back to the bottom through flex auto margin.
3. Post-fix browser capture was blocked at login; no visual pass is claimed.

### Implementation checklist

- [x] Shared functional Back component with icon.
- [x] Bottom anchoring in Product, Warehouse, Reports and Settings.
- [x] Shared large white panel and 190 px navigation rail.
- [x] Automated component, build and bundle validation.
- [ ] Authenticated narrow-viewport visual capture and same-state comparison.

### Arrow and label spacing follow-up

Source evidence:

- `C:\Users\YLF\AppData\Local\Temp\codex-clipboard-4e43beab-f76c-4d80-a803-fe34d99296f2.png`
- `C:\Users\YLF\AppData\Local\Temp\codex-clipboard-690473f5-4179-41cf-86c6-bf4e2d244545.png`
- `C:\Users\YLF\AppData\Local\Temp\codex-clipboard-99b01a19-ecf9-489b-8272-025df0063458.png`

The focused crops show that Product and Warehouse did not apply the configured 6 px gap because their Back button was not a flex container. `ModuleNavBackButton.css` now owns `display: flex` and vertical centering, so the existing shared gap separates the Phosphor arrow from the label consistently. Focused validation passed: 3 files, 54 tests; production build and bundle budget passed. A post-fix authenticated screenshot is still unavailable, so the overall visual result remains blocked.

### Navigation icons follow-up

All functional entries in Product, Warehouse, Reports and Settings now use the shared `ModuleNavItem` structure: a 22 px icon centered above a centered label inside a 52 px minimum-height button. Reports reuses its original raster icons; the other modules use the existing Phosphor dependency. Back remains a separate horizontal action anchored at the bottom of the rail. Focused validation passed: 5 files, 110 tests. The complete frontend suite passed on rerun: 166 files, 1419 tests. APP VENTA and APP GESTIÓN production builds and bundle budgets passed. Authenticated visual comparison remains pending.

final result: blocked
