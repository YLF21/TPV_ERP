# APP VENTA Sales And Stock Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add first-pass Venta and Stock screens connected from APP VENTA home.

**Architecture:** Create two focused React screen components in `app-common`, each reusing `SessionTopControls` and `ScreenContextFooter`. Route them from `frontend/apps/app-venta/src/main.tsx` and expose stock navigation through `SessionHomeScreen`.

**Tech Stack:** React, TypeScript, Vitest static markup tests, existing `tpv.css`.

---

### Task 1: Tests

**Files:**
- Create: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Create: `frontend/packages/app-common/src/components/StockScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SessionHomeScreen.test.tsx`

- [ ] Add failing tests that expect the new screens to render shared controls, context footer, and their main workspace labels.
- [ ] Add a home test proving the stock button invokes `onOpenStock`.
- [ ] Run `npm test -- SaleScreen.test.tsx StockScreen.test.tsx SessionHomeScreen.test.tsx` and confirm failure because screens/callback are missing.

### Task 2: Screens And Routing

**Files:**
- Create: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Create: `frontend/packages/app-common/src/components/StockScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SessionHomeScreen.tsx`
- Modify: `frontend/packages/app-common/src/index.ts`
- Modify: `frontend/apps/app-venta/src/main.tsx`

- [ ] Implement the two screens with current shared top controls and footer.
- [ ] Wire home sale and stock buttons to `sale` and `stock` route states.
- [ ] Export both screens from `app-common`.
- [ ] Run the targeted tests and confirm they pass.

### Task 3: Styling And Verification

**Files:**
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

- [ ] Add `work-screen`, `sale-screen`, and `stock-screen` styles using the formal report/settings visual language.
- [ ] Run `npm test -- --run`.
- [ ] Run `npm run build`.
- [ ] Run `git diff --check`.
