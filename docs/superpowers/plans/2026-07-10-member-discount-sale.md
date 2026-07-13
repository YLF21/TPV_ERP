# Member Discount in Sale Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recalculate `MEMBER_DISCOUNT` sale lines immediately when an active member customer is selected.

**Architecture:** Extend the minimal customer sale projection with enabled category benefit data. Keep manual and member discounts separate in frontend line state and calculate the effective discount as their maximum, matching backend loyalty rules.

**Tech Stack:** Java 25, Spring Boot 4, React 19, TypeScript, Vitest.

## Global Constraints

- No commit, stage or push.
- Only `MEMBER_DISCOUNT` products receive category percentage.
- Manual discount is preserved and never added to member discount.
- Backend remains authoritative when the real ticket is created.

### Task 1: Customer sale projection

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/party/CustomerService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/party/CustomerController.java`
- Modify: `backend/src/test/java/com/tpverp/backend/party/PartyControllerContractTest.java`

- [ ] Write a failing mapping test for active BRONCE with enabled 5% and inactive/disabled members with 0%.
- [ ] Run the focused backend test and verify RED.
- [ ] Add category name and effective member discount to `CustomerView` and `SaleCustomerOption` without exposing other PII.
- [ ] Run the focused test and verify GREEN.

### Task 2: Sale line calculation

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`

- [ ] Write failing tests for BRONCE 5% on `MEMBER_DISCOUNT`, normal product unchanged, manual discount precedence, customer removal and product added after selection.
- [ ] Run `npm.cmd test -- SaleScreen.test.tsx` and verify RED.
- [ ] Add separate `manualDiscountPercent` and `memberDiscountPercent`; effective discount is `max`.
- [ ] Recalculate member component whenever customer changes and when a product is added.
- [ ] Show `Socio 5%` when the member component determines the effective discount.
- [ ] Run focused tests and verify GREEN.

### Task 3: Verification

- [ ] Run focused frontend/backend tests and frontend build.
- [ ] Restart backend if required and verify BRONCE against `DEV-CAFE` and `DEV-AGUA` in the browser.
- [ ] Run `git diff --check` and inspect local-only status.
