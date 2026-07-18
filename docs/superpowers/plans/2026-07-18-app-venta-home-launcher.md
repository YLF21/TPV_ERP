# APP VENTA Home Launcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the APP VENTA home launcher as one large Venta tile plus vertical Stock, Informe, Ajustes, and Deudas clientes cards with F1-F5 shortcuts.

**Architecture:** Keep `SessionHomeScreen` as the permission boundary and reuse its existing callbacks and image assets. Add scoped markup/classes and a window-level keyboard effect inside the component; CSS remains under `.home-screen` so APP VENTA behavior changes without altering backend or navigation state.

**Tech Stack:** React 19, TypeScript 5.7, Vitest 4, Testing Library, CSS.

## Global Constraints

- Preserve all current permission checks, callbacks, header controls, and footer context.
- Render only actions the user is allowed to open; never reserve empty cards.
- Reuse existing image assets and add no dependency.
- F1-F5 must ignore repeated or already prevented events and must not invoke unavailable actions.
- Preserve the existing Warehouse action when provided, without assigning it one of the approved APP VENTA shortcuts.
- Do not modify backend or database files.

---

### Task 1: Home launcher markup and keyboard contract

**Files:**
- Modify: `frontend/packages/app-common/src/components/SessionHomeScreen.tsx`
- Test: `frontend/packages/app-common/src/components/SessionHomeScreen.test.tsx`

**Interfaces:**
- Consumes: existing props `onOpenSales`, `onOpenStock`, `onOpenSalesReport`, `onOpenSettings`, `onOpenCustomerReceivables`, `onOpenWarehouse`.
- Produces: buttons with `data-home-action` values `sale`, `stock`, `report`, `settings`, `receivables`, optional `warehouse`; visible `<kbd className="home-action-shortcut">` labels; window shortcuts F1-F5.

- [ ] **Step 1: Convert the component test to jsdom rendering and add failing launcher structure assertions**

Add the jsdom directive and Testing Library imports, render a fully permitted APP VENTA home, then assert the exact action/shortcut mapping:

```tsx
// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

afterEach(cleanup);

it("renders the approved APP VENTA launcher and shortcut labels", () => {
  render(<SessionHomeScreen
    app="venta"
    locale="es"
    session={{ ...session, permissions: ["ADMIN", "CUSTOMER_RECEIVABLES_READ"] }}
    terminalContext={terminalContext}
    canOpenSalesReport
    onLocaleChange={vi.fn()}
    onOpenSales={vi.fn()}
    onOpenStock={vi.fn()}
    onOpenSalesReport={vi.fn()}
    onOpenSettings={vi.fn()}
    onOpenCustomerReceivables={vi.fn()}
  />);

  expect(within(screen.getByRole("button", { name: /venta/i })).getByText("F1")).toBeInTheDocument();
  expect(within(screen.getByRole("button", { name: /producto|stock/i })).getByText("F2")).toBeInTheDocument();
  expect(within(screen.getByRole("button", { name: /informe/i })).getByText("F3")).toBeInTheDocument();
  expect(within(screen.getByRole("button", { name: /configuraci|ajustes/i })).getByText("F4")).toBeInTheDocument();
  expect(within(screen.getByRole("button", { name: /deudas clientes/i })).getByText("F5")).toBeInTheDocument();
});
```

Replace direct `SessionHomeScreen({...})` calls in existing tests with `render(...)` and role-based queries, because the component will use React hooks.

- [ ] **Step 2: Add failing shortcut behavior tests**

```tsx
it("opens visible home actions with F1-F5 and ignores repeats", () => {
  const callbacks = {
    sale: vi.fn(), stock: vi.fn(), report: vi.fn(), settings: vi.fn(), receivables: vi.fn(),
  };
  render(<SessionHomeScreen app="venta" locale="es"
    session={{ ...session, permissions: ["ADMIN", "CUSTOMER_RECEIVABLES_READ"] }}
    terminalContext={terminalContext} canOpenSalesReport onLocaleChange={vi.fn()}
    onOpenSales={callbacks.sale} onOpenStock={callbacks.stock}
    onOpenSalesReport={callbacks.report} onOpenSettings={callbacks.settings}
    onOpenCustomerReceivables={callbacks.receivables} />);

  ["F1", "F2", "F3", "F4", "F5"].forEach((key) => fireEvent.keyDown(window, { key }));
  expect(callbacks.sale).toHaveBeenCalledOnce();
  expect(callbacks.stock).toHaveBeenCalledOnce();
  expect(callbacks.report).toHaveBeenCalledOnce();
  expect(callbacks.settings).toHaveBeenCalledOnce();
  expect(callbacks.receivables).toHaveBeenCalledOnce();
  fireEvent.keyDown(window, { key: "F1", repeat: true });
  expect(callbacks.sale).toHaveBeenCalledOnce();
});

it("does not run a shortcut for an action hidden by permissions", () => {
  const receivables = vi.fn();
  render(<SessionHomeScreen app="venta" locale="es" session={{ ...session, permissions: [] }}
    terminalContext={terminalContext} onLocaleChange={vi.fn()}
    onOpenCustomerReceivables={receivables} />);
  fireEvent.keyDown(window, { key: "F5" });
  expect(receivables).not.toHaveBeenCalled();
});
```

- [ ] **Step 3: Run the focused tests and verify the new expectations fail**

Run from `frontend`:

```powershell
npm.cmd test -- SessionHomeScreen.test.tsx
```

Expected: FAIL because shortcut markers and the window key handler do not exist.

- [ ] **Step 4: Implement scoped action markup and keyboard handling**

Import `useEffect` and create the available shortcut map after permission flags:

```tsx
import { useEffect } from "react";

useEffect(() => {
  const shortcuts: Record<string, (() => void) | undefined> = app === "venta" ? {
    F1: canOpenSale ? onOpenSales : undefined,
    F2: canOpenStock ? onOpenStock : undefined,
    F3: canOpenReport ? onOpenSalesReport : undefined,
    F4: canOpenSettings ? onOpenSettings : undefined,
    F5: canOpenReceivables ? onOpenCustomerReceivables : undefined,
  } : {};
  const handleKeyDown = (event: KeyboardEvent) => {
    if (event.defaultPrevented || event.repeat) return;
    const action = shortcuts[event.key];
    if (!action) return;
    event.preventDefault();
    action();
  };
  window.addEventListener("keydown", handleKeyDown);
  return () => window.removeEventListener("keydown", handleKeyDown);
}, [app, canOpenSale, canOpenStock, canOpenReport, canOpenSettings, canOpenReceivables,
  onOpenSales, onOpenStock, onOpenSalesReport, onOpenSettings, onOpenCustomerReceivables]);
```

Give each permitted button a scoped action class and label, for example:

```tsx
<button type="button" className="home-action home-action-sale" data-home-action="sale" onClick={onOpenSales}>
  <span className="home-action-icon-panel"><img className="home-action-icon" alt="" src={saleIcon} /></span>
  <span className="home-action-label">{t("home.sale")}</span>
  {app === "venta" && <kbd className="home-action-shortcut">F1</kbd>}
</button>
```

Use the equivalent data values and keys for Stock F2, Report F3, Settings F4, and Receivables F5. Render Warehouse with `data-home-action="warehouse"` and no conflicting F-key marker.

- [ ] **Step 5: Run the focused tests and verify they pass**

Run:

```powershell
npm.cmd test -- SessionHomeScreen.test.tsx
```

Expected: all `SessionHomeScreen` tests PASS with no invalid-hook warnings.

- [ ] **Step 6: Commit the behavior slice**

```powershell
git add frontend/packages/app-common/src/components/SessionHomeScreen.tsx frontend/packages/app-common/src/components/SessionHomeScreen.test.tsx
git commit -m "feat: add APP VENTA home shortcuts"
```

### Task 2: Responsive launcher styling

**Files:**
- Modify: `frontend/packages/app-common/src/styles/tpv.css`
- Test: `frontend/packages/app-common/src/components/SessionHomeScreen.test.tsx`

**Interfaces:**
- Consumes: `.home-actions`, `.home-action-side`, `[data-home-action]`, `.home-action-icon-panel`, `.home-action-label`, `.home-action-shortcut` from Task 1.
- Produces: scoped two-column desktop layout and single-column responsive layout.

- [ ] **Step 1: Add a failing class contract assertion**

```tsx
const sale = screen.getByRole("button", { name: /venta/i });
expect(sale).toHaveAttribute("data-home-action", "sale");
expect(sale.querySelector(".home-action-icon-panel")).not.toBeNull();
expect(sale.querySelector(".home-action-label")).not.toBeNull();
expect(sale.querySelector(".home-action-shortcut")).not.toBeNull();
```

- [ ] **Step 2: Run the focused test and confirm it fails before the final markup contract is complete**

Run: `npm.cmd test -- SessionHomeScreen.test.tsx`

Expected: FAIL on any missing class or data attribute.

- [ ] **Step 3: Add final scoped CSS after the existing home overrides**

Add a final `.home-screen` block so it deterministically overrides the legacy duplicate rules:

```css
.home-screen .home-actions {
  --home-launcher-height: min(640px, calc(100vh - 210px));
  width: min(1180px, calc(100vw - 80px)) !important;
  grid-template-columns: minmax(360px, 0.86fr) minmax(470px, 1.14fr) !important;
  gap: 38px !important;
  align-items: stretch !important;
}
.home-screen .home-action-side {
  display: flex !important;
  flex-direction: column;
  height: var(--home-launcher-height) !important;
  gap: 14px !important;
}
.home-screen .home-action-side .home-action {
  position: relative;
  flex: 1 1 0;
  width: 100% !important;
  height: auto !important;
  display: grid !important;
  grid-template-columns: 168px minmax(0, 1fr) 86px !important;
  grid-template-rows: 1fr !important;
  border: 1px solid #b8c7d8 !important;
  background: #fff !important;
}
.home-screen .home-action-side .home-action-icon-panel {
  align-self: stretch;
  display: grid;
  place-items: center;
  background: #eef4fb;
}
.home-screen .home-action-label {
  align-self: center;
  justify-self: start;
  padding: 0 28px;
  font-size: clamp(22px, 2.2vw, 36px) !important;
}
.home-screen .home-action-shortcut {
  align-self: center;
  justify-self: center;
  padding: 10px 12px;
  border: 2px solid #2f7dbc;
  border-radius: 7px;
  color: #2f7dbc;
  background: #fff;
  font: inherit;
  font-weight: 900;
}
.home-screen .home-action-sale {
  position: relative;
  width: 100% !important;
  height: var(--home-launcher-height) !important;
  grid-template-rows: 34% 1fr !important;
}
.home-screen .home-action-sale .home-action-icon-panel {
  align-self: stretch;
  display: grid;
  place-items: center;
}
.home-screen .home-action-sale .home-action-label {
  justify-self: center;
  padding: 0;
  font-size: clamp(58px, 7vw, 96px) !important;
}
.home-screen .home-action-sale .home-action-shortcut {
  position: absolute;
  left: 50%;
  bottom: 34px;
  transform: translateX(-50%);
  border-color: #fff;
  color: #fff;
  background: transparent;
}
@media (max-width: 900px) {
  .home-screen .home-actions {
    width: min(620px, calc(100vw - 32px)) !important;
    grid-template-columns: 1fr !important;
  }
  .home-screen .home-action-sale { min-height: 360px !important; }
  .home-screen .home-action-side { height: auto !important; }
  .home-screen .home-action-side .home-action {
    min-height: 118px !important;
    grid-template-columns: 110px minmax(0, 1fr) 70px !important;
  }
}
```

If local inspection demonstrates clipping, reduce `--home-launcher-height` from `min(640px, calc(100vh - 210px))` to `min(600px, calc(100vh - 210px))`; do not change the two-column hierarchy or scoped selectors.

- [ ] **Step 4: Run focused tests and APP VENTA build**

Run from `frontend`:

```powershell
npm.cmd test -- SessionHomeScreen.test.tsx
npm.cmd run build --workspace @tpverp/app-venta
```

Expected: tests PASS and Vite/TypeScript build exits 0.

- [ ] **Step 5: Inspect the local launcher at desktop and narrow widths**

Start the existing local APP VENTA server and verify: no horizontal scroll; one large Venta card; right-side cards fill equal vertical space; all visible labels and F-key badges fit; focus ring is visible.

- [ ] **Step 6: Commit the visual slice**

```powershell
git add frontend/packages/app-common/src/styles/tpv.css frontend/packages/app-common/src/components/SessionHomeScreen.test.tsx
git commit -m "style: refine APP VENTA home launcher"
```
