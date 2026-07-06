# APP VENTA A4 Document Printers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add A4 printer configuration and document-type print routing to APP VENTA hardware settings.

**Architecture:** Extend the existing local hardware config with A4 printer fields and per-document print routes. Electron prints A4 through hidden HTML windows using Windows printers.

**Tech Stack:** Electron 41, React, TypeScript, Vitest, Vite.

---

### Task 1: Types And Defaults

**Files:**
- Modify: `frontend/packages/app-common/src/hardware/hardware.ts`
- Modify: `frontend/packages/app-common/src/hardware/hardware.test.ts`

- [ ] Add tests for default document routes and A4 test document.
- [ ] Implement document route types, defaults, and A4 test document helper.
- [ ] Run `npm test -- hardware`.

### Task 2: Electron A4 Printing

**Files:**
- Modify: `frontend/desktop/main.cjs`
- Modify: `frontend/desktop/preload.cjs`

- [ ] Add IPC for printing an A4 test document.
- [ ] Print A4 through hidden BrowserWindow and Windows printer.

### Task 3: UI And Translations

**Files:**
- Modify: `frontend/packages/app-common/src/components/HardwareSettingsScreen.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesZh.ts`

- [ ] Add A4 printer selector, A4 test button, and document route controls.
- [ ] Add translations.
- [ ] Run `npm test` and `npm run build`.

