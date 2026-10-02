import assert from "node:assert/strict";
const companyId = "10000000-0000-4000-8000-000000000001";
const ticketId = "60000000-0000-4000-8000-000000000001";
const assignees = [{ id: "80000000-0000-4000-8000-000000000001", username: "REPAIRS" }, { id: "80000000-0000-4000-8000-000000000002", username: "Ana García" }];
const now = "2026-09-22T10:00:00Z";
const sync = { id: "LOCAL_SYNC:40000000-0000-4000-8000-000000000001", source: "LOCAL_SYNC", sourceId: "40000000-0000-4000-8000-000000000001", companyId,
  companyName: "Empresa sin licencia", storeId: "20000000-0000-4000-8000-000000000001", storeName: "Tienda prueba", internalCode: "3500001",
  installationId: "30000000-0000-4000-8000-000000000001", installationReference: "INST-DEMO", status: "OPEN", severity: "DANGER", code: "SYNC_DELIVERY_FAILED",
  detail: "Synthetic failure", firstSeenAt: now, lastSeenAt: now, receivedAt: now, occurrences: 1, central: false, storeActive: true };
const app = { ...sync, id: "LOCAL_APPLICATION:40000000-0000-4000-8000-000000000002", source: "LOCAL_APPLICATION", code: "APPLICATION_ERROR" };
const central = { ...sync, id: "CENTRAL_SECURITY:40000000-0000-4000-8000-000000000003", source: "CENTRAL_SECURITY", code: "SECURITY_DELIVERY_FAILED", companyId: null, companyName: null, storeId: null, storeName: null, installationId: null };
const allPermissions = ["VIEW_ADMIN_DATA", "MANAGE_OPERATIONAL_INCIDENTS", "MANAGE_SUPPORT_TICKETS"];
export async function setup(browser, errors, base, permissions = allPermissions, includeLicenses = false) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on("pageerror", error => errors.push(error.message));
  const state = { commands: [], manualTicketIds: new Map(), repairPosts: [], manualPosts: [], reads: 0, failNext: false, loseResponse: false, delayPost: null, delayPostResponse: null,
    companies: [{ companyId, companyName: sync.companyName, taxId: "B00000001" }], ticket: null, comments: [], commentPosts: [], loseCommentResponse: false, failRepairReads: false, failTicketReads: false, delayRepairGet: null, ticketMutations: [], interventionPosts: [], intervention: null, failInterventionReads: false, loseInterventionResponse: false, failInterventionPost: false, invalidInterventionResponse: false, conflictIntervention: false, delayIntervention: null, ticketReads: 0, interventionReads: 0, ticketDelayMs: 0, delayInterventionResponse: null, delayComment: null, delayCommentResponse: null, invalidCommentResponse: null, rejectComment: false, invalidCommentRead: false };
  await page.route("**/api/**", async route => {
    const req = route.request(); const path = decodeURIComponent(new URL(req.url()).pathname); const method = req.method();
    const body = req.postData() ? JSON.parse(req.postData()) : null;
    const json = value => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/v1/auth/admin/login") return json({ username: "REPAIRS", accessToken: "synthetic-token", mode: "admin", expiresAt: "2099-01-01T00:00:00Z", passwordChangeRequired: false });
    if (path === "/api/v1/auth/logout") return route.fulfill({ status: 204 });
    if (path === "/api/v1/admin/me") return json({ username: "REPAIRS", permissions });
    if (path === "/api/v1/admin/companies") return json(state.companies);
    if (path === "/api/v1/admin/licenses") return json(includeLicenses ? [{ licenseReference: "LIC-SUPPORT", companyId, companyName: sync.companyName, taxId: "B00000001", taxpayerType: "SOCIEDAD", taxRegime: "IVA", commercialProfile: "MINORISTA", status: "VALIDA", validUntil: "2099-01-01T00:00:00Z", maxWindows: 1, maxPda: 0 }] : []);
    if (path === "/api/v1/admin/sync/sales-summary") return json({ documentCount: 0, total: "0.00" });
    if (path === "/api/v1/admin/reports/advanced") return json({ companies: 1, invoices: 0, invoicedTotal: "0.00", paidTotal: "0.00", salesDocuments: 0, salesTotal: "0.00", inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
    if (path === "/api/v1/admin/stores") return json({ items: [], total: 0, totalPages: 0 });
    if (path === "/api/v1/admin/supervision/failures") return json({ items: [sync, app, central], hasMore: false, nextCursor: null });
    if (path.endsWith("/repairs")) {
      const current = path.includes(sync.id) ? sync : path.includes(app.id) ? app : central;
      if (method === "GET") { state.reads++;
        if (state.delayRepairGet) await state.delayRepairGet;
        if (state.failRepairReads) return route.fulfill({ status: 503, body: "Synthetic repair read failure" });
        return json({ remoteEligible: current === sync && current.status === "OPEN" && current.severity === "DANGER" && current.storeActive && !state.commands.some(command => ["QUEUED", "RUNNING", "SUCCEEDED"].includes(command.status)), ineligibleReason: current === sync ? "ACTIVE_COMMAND_OR_INELIGIBLE" : "UNSUPPORTED_SOURCE", commands: current === sync ? state.commands : [], manualTicketId: state.manualTicketIds.get(current.id) ?? null }); }
      state.repairPosts.push(body);
      if (state.failNext) { state.failNext = false; return route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Synthetic uncertain delivery" }) }); }
      if (state.delayPost) await state.delayPost;
      const command = state.commands.find(command => command.requestId === body.requestId) ?? { commandId: `70000000-0000-4000-8000-${String(state.commands.length + 1).padStart(12, "0")}`, requestId: body.requestId, action: "RETRY_SYNC_OUTBOX", eventId: sync.sourceId,
        expectedVersion: 2, status: "QUEUED", resultCode: null, requestedBy: "REPAIRS", reason: body.reason, createdAt: now, updatedAt: now, expiresAt: "2026-09-22T11:00:00Z" };
      state.commands = [command, ...state.commands.filter(item => item.commandId !== command.commandId)];
      if (state.delayPostResponse) await state.delayPostResponse;
      if (state.loseResponse) { state.loseResponse = false; return route.fulfill({ status: 503, body: "Synthetic lost response after commit" }); }
      return json(command);
    }
    if (path.endsWith("/manual")) {
      state.manualPosts.push(body); state.manualTicketIds.set(path.includes(sync.id) ? sync.id : app.id, ticketId);
      state.ticket ??= { id: ticketId, companyId, companyName: sync.companyName, title: "Intervención manual del fallo", description: body.reason, priority: "NORMAL", status: "ABIERTO", interventionVersion: 0, createdBy: "REPAIRS", createdAt: now, updatedAt: now };
      return json({ ticketId });
    }
    if (path === `/api/v1/admin/companies/${companyId}/tickets`) {
      if (method === "POST") { state.ticket = { id: ticketId, companyId, companyName: sync.companyName, ...body, status: "ABIERTO", interventionVersion: 0, createdBy: "REPAIRS", createdAt: now, updatedAt: now }; return json(state.ticket); }
      state.ticketReads++; if (state.ticketDelayMs) await new Promise(resolve => setTimeout(resolve, state.ticketDelayMs)); return state.failTicketReads ? route.fulfill({ status: 503, body: "Synthetic ticket read failure" }) : json(state.ticket ? [state.ticket] : []); }
    if (path === `/api/v1/admin/tickets/${ticketId}/interventions`) {
      state.intervention ??= { ticketId, companyId, status: "REMOTE_PENDING", version: 0, ticketStatus: state.ticket.status, teamViewerId: null, events: [], assignees, assigneeUserId: null, assignee: null, nextReviewAt: null, resumeStatus: null, failure: state.manualTicketIds.size ? (() => { const failure = state.manualTicketIds.has(sync.id) ? sync : app; return { key: failure.id, status: failure.status, code: failure.code, storeName: failure.storeName, receivedAt: now }; })() : null };
      if (method === "GET") { state.interventionReads++; return state.failInterventionReads ? route.fulfill({ status: 503, body: "Synthetic read failure" }) : json(state.intervention); }
      state.interventionPosts.push(body);
      if (state.delayIntervention) await state.delayIntervention;
      if (state.failInterventionPost) { state.failInterventionPost = false; return route.fulfill({ status: 503, body: "Synthetic precommit failure" }); }
      if (!state.intervention.events.some(event => event.requestId === body.requestId)) {
        if (state.conflictIntervention || body.expectedVersion !== state.intervention.version || body.expectedTicketStatus !== state.ticket.status) {
          state.conflictIntervention = false; return route.fulfill({ status: 409, body: "Synthetic concurrent update" });
        }
        const next = { START_SAAS: "SAAS_IN_PROGRESS", WAIT_CUSTOMER: "WAITING_CUSTOMER", WAIT_MATERIAL: "WAITING_MATERIAL", RESUME: state.intervention.resumeStatus, START_REMOTE: "REMOTE_IN_PROGRESS", REQUIRE_ONSITE: "ONSITE_REQUIRED", START_ONSITE: "ONSITE_IN_PROGRESS", RESOLVE: "RESOLVED", REOPEN: "REMOTE_PENDING", REQUEST_VERIFICATION: "AWAITING_VERIFICATION", VERIFICATION_FAILED: "REMOTE_PENDING", SAVE_DETAILS: state.intervention.status }[body.action];
        const allowed = { REMOTE_PENDING: ["START_SAAS", "START_REMOTE", "REQUIRE_ONSITE", "WAIT_CUSTOMER", "WAIT_MATERIAL"], SAAS_IN_PROGRESS: ["START_REMOTE", "REQUIRE_ONSITE", "REQUEST_VERIFICATION", "WAIT_CUSTOMER", "WAIT_MATERIAL"], REMOTE_IN_PROGRESS: ["REQUIRE_ONSITE", "REQUEST_VERIFICATION", "WAIT_CUSTOMER", "WAIT_MATERIAL"], ONSITE_REQUIRED: ["START_ONSITE", "WAIT_CUSTOMER", "WAIT_MATERIAL"], ONSITE_IN_PROGRESS: ["REQUEST_VERIFICATION", "WAIT_CUSTOMER", "WAIT_MATERIAL"], AWAITING_VERIFICATION: ["RESOLVE", "VERIFICATION_FAILED", "REQUIRE_ONSITE", "WAIT_CUSTOMER", "WAIT_MATERIAL"], WAITING_CUSTOMER: ["RESUME", "WAIT_MATERIAL"], WAITING_MATERIAL: ["RESUME", "WAIT_CUSTOMER"], RESOLVED: ["REOPEN"] };
        assert.ok(body.action === "SAVE_DETAILS" && state.intervention.status !== "RESOLVED" || allowed[state.intervention.status].includes(body.action));
        if (body.action === "START_ONSITE") assert.ok(state.intervention.visitAt && state.intervention.assigneeUserId);
        if (["WAIT_CUSTOMER", "WAIT_MATERIAL"].includes(body.action)) assert.ok(body.nextReviewAt && new Date(body.nextReviewAt) > new Date());
        if (body.action === "REQUEST_VERIFICATION") assert.ok(body.resolutionSummary.length >= 5);
        if (body.action === "RESOLVE") assert.ok(body.verificationNotes.length >= 5 && body.confirmedBy.length >= 2);
        assert.ok(Array.from(body.note.trim()).length >= 5 && Array.from(body.note.trim()).length <= 2000);
        assert.ok(body.teamViewerId == null || body.action === "START_REMOTE" && /^\d{6,15}$/.test(body.teamViewerId));
        state.ticket.status = body.action === "SAVE_DETAILS" ? state.ticket.status : body.action === "RESOLVE" ? "RESUELTO" : ["REOPEN", "VERIFICATION_FAILED"].includes(body.action) ? "ABIERTO" : "EN_CURSO";
        const fields = body.action === "SAVE_DETAILS" ? { assigneeUserId: body.assigneeUserId, assignee: assignees.find(user => user.id === body.assigneeUserId)?.username ?? null, visitAt: body.visitAt } : ["WAIT_CUSTOMER", "WAIT_MATERIAL"].includes(body.action) ? { nextReviewAt: body.nextReviewAt, resumeStatus: state.intervention.resumeStatus ?? state.intervention.status } : body.action === "RESUME" ? { nextReviewAt: null, resumeStatus: null } : ["START_SAAS", "START_REMOTE", "START_ONSITE"].includes(body.action) && !state.intervention.assigneeUserId ? { assigneeUserId: assignees[0].id, assignee: assignees[0].username } : body.action === "REQUEST_VERIFICATION" ? { resolutionSummary: body.resolutionSummary } : body.action === "RESOLVE" ? { verificationNotes: body.verificationNotes, confirmedBy: body.confirmedBy } : ["REOPEN", "VERIFICATION_FAILED"].includes(body.action) ? { resolutionSummary: null, verificationNotes: null, confirmedBy: null, visitAt: null } : {};
        state.intervention = { ...state.intervention, ...fields, version: state.intervention.version + 1, status: next, ticketStatus: state.ticket.status,
          teamViewerId: body.action === "START_REMOTE" ? body.teamViewerId : state.intervention.teamViewerId,
          events: [...state.intervention.events, { requestId: body.requestId, version: state.intervention.version + 1, action: body.action, status: next, note: body.note, teamViewerId: body.teamViewerId, actor: "REPAIRS", createdAt: now, ...fields }] };
      }
      Object.assign(state.ticket, { interventionVersion: state.intervention.version, interventionStatus: state.intervention.status, assigneeUserId: state.intervention.assigneeUserId, assignee: state.intervention.assignee, visitAt: state.intervention.visitAt, nextReviewAt: state.intervention.nextReviewAt, failureKey: state.intervention.failure?.key, failureStatus: state.intervention.failure?.status, failureReceivedAt: state.intervention.failure?.receivedAt });
      const interventionResponse = structuredClone(state.intervention);
      if (state.delayInterventionResponse) await state.delayInterventionResponse;
      if (state.loseInterventionResponse) { state.loseInterventionResponse = false; return route.fulfill({ status: 503, body: "Synthetic committed response lost" }); }
      if (state.invalidInterventionResponse) { state.invalidInterventionResponse = false; return route.fulfill({ status: 204 }); }
      return json(interventionResponse);
    }
    if (path === `/api/v1/admin/tickets/${ticketId}/comments`) {
      if (method === "POST") {
        state.commentPosts.push(body);
        if (state.delayComment) await state.delayComment;
        if (state.rejectComment) { state.rejectComment = false; return route.fulfill({ status: 409, body: "Synthetic rejected comment" }); }
        const existing = body.requestId && state.comments.find(comment => comment.requestId === body.requestId);
        if (existing && existing.message !== body.message) return route.fulfill({ status: 409, body: "Idempotency conflict" });
        const comment = existing || { id: `comment-${state.comments.length + 1}`, ticketId, requestId: body.requestId ?? null, author: "REPAIRS", message: body.message, createdAt: now };
        if (!existing) state.comments.push(comment);
        if (state.delayCommentResponse) await state.delayCommentResponse;
        if (state.invalidCommentResponse) { const kind = state.invalidCommentResponse; state.invalidCommentResponse = null;
          return kind === "empty" ? route.fulfill({ status: 204 }) : json(kind === "object" ? {} : { ...comment, ticketId: "another-ticket" }); }
        if (state.loseCommentResponse) { state.loseCommentResponse = false; return route.fulfill({ status: 503, body: "Synthetic committed comment lost response" }); }
        return json(comment);
      }
      return json(state.invalidCommentRead ? [null] : state.comments);
    }
    if (path === `/api/v1/admin/tickets/${ticketId}` && method === "PUT") {
      state.ticketMutations.push(body);
      const previousStatus = state.ticket.status;
      if (body.status != null && (body.expectedInterventionVersion !== state.ticket.interventionVersion || body.expectedTicketStatus !== previousStatus)) return route.fulfill({ status: 409, body: "Stale ticket status" });
      const changed = body.status != null && body.status !== previousStatus;
      if (changed) return route.fulfill({ status: 409, body: "Use verified intervention workflow" });
      state.ticket = { ...state.ticket, ...(body.priority != null ? { priority: body.priority } : {}), status: body.status ?? previousStatus,
        interventionVersion: state.ticket.interventionVersion + (changed ? 1 : 0) };
      if (changed && state.intervention) state.intervention = { ...state.intervention, ticketStatus: body.status, version: state.ticket.interventionVersion,
        status: body.status === "RESUELTO" ? "RESOLVED" : previousStatus === "RESUELTO" ? "REMOTE_PENDING" : state.intervention.status };
      return json(state.ticket);
    }
    if (path === `/api/v1/admin/supervision/failures/${sync.id}`) return json(sync);
    if (path === `/api/v1/admin/supervision/failures/${app.id}`) return json(app);
    if (path === `/api/v1/admin/supervision/failures/${central.id}`) return json(central);
    assert.equal(method, "GET", `Unexpected write: ${path}`); return json([]);
  });
  await page.goto(`${base}#/login`);
  await page.locator('input[autocomplete="username"]').fill("REPAIRS");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  await page.locator(".saas-dashboard").waitFor();
  const supervision = page.locator(".top-nav-list").getByRole("button", { name: "Supervisión", exact: true });
  if (await supervision.getAttribute("aria-expanded") !== "true") await supervision.click();
  await page.locator(".top-nav-list").getByRole("button", { name: "Fallos de tiendas", exact: true }).click();
  await page.getByRole("cell", { name: /SYNC_DELIVERY_FAILED/ }).waitFor();
  await page.clock.install();
  const panel = page.getByRole("region", { name: "Resolución del fallo", exact: true });
  async function open(code = "SYNC_DELIVERY_FAILED") {
    await page.getByRole("row").filter({ hasText: code }).getByRole("button", { name: "Detalle", exact: true }).click();
    await panel.getByRole("button", { name: "Consultar estado", exact: true }).waitFor();
    await page.waitForFunction(() => !document.querySelector('section[aria-label="Resolución del fallo"]')?.textContent.includes("Consultando reparaciones"));
  }
  return { page, state, panel, open };
}

export { companyId, ticketId, sync, app, assignees };
