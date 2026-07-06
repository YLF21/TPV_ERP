# APP VENTA Hardware Local Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first local hardware layer for APP VENTA: keyboard barcode scanner flow, Windows printer discovery, test ticket printing, and prepared cash drawer/ESC-POS configuration.

**Architecture:** Electron owns local hardware access in `desktop/main.cjs`, exposes a narrow API through `desktop/preload.cjs`, and React consumes a typed facade in `app-common`. Browser/dev mode returns controlled "hardware unavailable" results.

**Tech Stack:** Electron 41, React, TypeScript, Vitest, Vite.

---

### Task 1: Hardware Types And Browser Fallback

**Files:**
- Create: `frontend/packages/app-common/src/hardware/hardware.ts`
- Create: `frontend/packages/app-common/src/hardware/hardware.test.ts`
- Modify: `frontend/packages/app-common/src/desktop.d.ts`
- Modify: `frontend/packages/app-common/src/index.ts`

- [ ] Write tests for default config, browser fallback, and test ticket payload.
- [ ] Run `npm test -- hardware`.
- [ ] Implement the typed hardware facade with safe fallbacks.
- [ ] Re-run `npm test -- hardware`.

### Task 2: Electron IPC

**Files:**
- Modify: `frontend/desktop/main.cjs`
- Modify: `frontend/desktop/preload.cjs`

- [ ] Add IPC channels for printer list, test ticket print, hardware config get/save, and cash drawer test.
- [ ] Keep all APIs behind `contextBridge`.
- [ ] Ensure failures return structured results instead of throwing into React.

### Task 3: Hardware Configuration UI

**Files:**
- Create: `frontend/packages/app-common/src/components/HardwareSettingsScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SessionHomeScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/AppFrame.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesZh.ts`

- [ ] Add a configuration route/screen.
- [ ] Add controls for printer mode, Windows printer selection, printer refresh, test ticket, cash drawer test, and scanner test.
- [ ] Follow the existing APP VENTA visual language.

### Task 4: Verification

**Files:**
- Existing frontend workspace.

- [ ] Run `npm test`.
- [ ] Run `npm run build`.
- [ ] Report manual hardware notes and remaining ESC/POS limitations.

