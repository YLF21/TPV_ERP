# VERI*FACTU Document Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect confirmed tickets, sales invoices, ticket cancellation and ticket-to-invoice conversion with the fiscal record core.

**Architecture:** Keep `DocumentService` as the transactional orchestrator and add a small fiscal integration component that classifies documents and calls `FiscalRecordService`. Number formatting moves to store-aware formats using `tienda.codigoTienda`.

**Tech Stack:** Java 25, Spring Boot 4, JPA, Flyway, PostgreSQL, JUnit 5, Mockito.

---

### Task 1: Store-Aware Numbering

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentNumbering.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/ContadorDocumento.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`
- Test: `backend/src/test/java/com/tpverp/backend/document/DocumentNumberingTest.java`

- [ ] Add failing tests for `001-260608-00001`, `FV-001-26-000001`, `FRV-001-26-000001`.
- [ ] Run `.\mvnw.cmd -Dtest=DocumentNumberingTest test` and verify failure.
- [ ] Add `format(type, date, sequence, codigoTienda)` and route counters through the current store code.
- [ ] Run the same test and verify pass.

### Task 2: `num_ticket` And Conversion Relation

**Files:**
- Add: `backend/src/main/resources/db/migration/V8__integracion_documental_verifactu.sql`
- Modify: `backend/src/main/java/com/tpverp/backend/document/Documento.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentView.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentoRepository.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentoRelacionRepository.java`
- Test: `backend/src/test/java/com/tpverp/backend/document/DocumentServiceTest.java`

- [ ] Add failing tests for second conversion blocked and cancellation of invoiced ticket blocked.
- [ ] Add nullable `documento.num_ticket`.
- [ ] Add repository query for `FACTURA_DE`.
- [ ] Add `convertTicketToInvoice(ticketId, customerId, authentication)` copying lines and setting `num_ticket`.

### Task 3: Fiscal Integration

**Files:**
- Add: `backend/src/main/java/com/tpverp/backend/document/DocumentFiscalIntegration.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`
- Test: `backend/src/test/java/com/tpverp/backend/document/DocumentFiscalIntegrationTest.java`

- [ ] Add failing classification tests for `F2`, `R5`, `F1`, `F3`, `R1`.
- [ ] Implement classification and inactive VERI*FACTU tolerance.
- [ ] Call fiscal integration after document state is persisted inside the same transaction.
- [ ] Add cancellation fiscal record for ticket cancellation.

### Task 4: Critical Verification

**Files:**
- Test: existing focused tests only.

- [ ] Run `.\mvnw.cmd -Dtest=DocumentNumberingTest,DocumentFiscalIntegrationTest,DocumentServiceTest test`.
- [ ] Run PostgreSQL migration test only if V8 compiles cleanly.
- [ ] Commit only touched implementation and test files.
