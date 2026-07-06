# APP VENTA Customer Display Compact Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a compact two-line customer display window for APP VENTA, configurable from hardware settings.

**Architecture:** Keep the display local to Electron. `app-common` owns typed display state helpers and safe bridge calls; `desktop/main.cjs` opens/closes/updates a secondary BrowserWindow.

**Tech Stack:** Electron 41, React, TypeScript, Vitest, Vite.

---

### Task 1: Display State Helpers

**Files:**
- Modify: `frontend/packages/app-common/src/hardware/hardware.ts`
- Modify: `frontend/packages/app-common/src/hardware/hardware.test.ts`

- [ ] Add failing tests for idle, sale, and payment two-line display states.
- [ ] Implement helpers and default config fields.
- [ ] Run `npm test -- hardware`.

### Task 2: Electron Display Window

**Files:**
- Modify: `frontend/desktop/main.cjs`
- Modify: `frontend/desktop/preload.cjs`
- Modify: `frontend/packages/app-common/src/desktop.d.ts`

- [ ] Add IPC to list screens, open, close, and update customer display.
- [ ] Render a dark two-line fullscreen/maximized customer window.
- [ ] Keep browser fallback controlled.

### Task 3: Hardware Settings UI

**Files:**
- Modify: `frontend/packages/app-common/src/components/HardwareSettingsScreen.tsx`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesZh.ts`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

- [ ] Add customer display controls.
- [ ] Add open, close, idle test, sale test, and payment test actions.
- [ ] Run all tests and build.

