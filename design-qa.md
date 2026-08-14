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

final result: implementation and automated QA passed; physical printer validation remains external.
