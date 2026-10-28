import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { demoRequestPagePath, demoRequestStatuses } from "../src/features/marketing/demo-request-query.mjs";

test("demo inbox builds paginated filters with an encoded, trimmed query", () => {
  const path = demoRequestPagePath({ page: 2, size: 20, status: "NEW", query: "  Ana & Co+test@example.com  " });
  const url = new URL(path, "https://example.invalid");
  assert.equal(url.pathname, "/api/v1/admin/demo-requests/page");
  assert.equal(url.searchParams.get("page"), "2");
  assert.equal(url.searchParams.get("size"), "20");
  assert.equal(url.searchParams.get("status"), "NEW");
  assert.equal(url.searchParams.get("query"), "Ana & Co+test@example.com");
  assert.equal([...url.searchParams.keys()].length, 4);
});

test("all-status search omits optional filters and blank queries", () => {
  assert.equal(demoRequestPagePath(), "/api/v1/admin/demo-requests/page?page=0&size=20");
  assert.equal(demoRequestPagePath({ query: "   " }), demoRequestPagePath());
  assert.deepEqual(demoRequestStatuses, ["NEW", "CONTACTED", "CLOSED"]);
});

test("demo inbox rejects invalid query and pagination limits before requesting", () => {
  for (const filters of [{ page: -1 }, { page: 1.5 }, { size: 0 }, { size: 101 }, { status: "DELETED" }, { query: "x".repeat(161) }]) {
    assert.throws(() => demoRequestPagePath(filters), RangeError);
  }
  assert.doesNotThrow(() => demoRequestPagePath({ size: 100, query: "x".repeat(160) }));
});

test("demo inbox follows admin permission and non-optimistic update contracts", async () => {
  const read = path => readFile(new URL(path, import.meta.url), "utf8");
  const [view, api, nav, types] = await Promise.all([
    read("../src/features/marketing/DemoRequestsView.tsx"), read("../src/lib/api.ts"), read("../src/app/navigation.ts"), read("../src/shared/types.tsx")
  ]);
  assert.match(nav, /view: "demo-requests"[^\n]+permission: "VIEW_ADMIN_DATA"/);
  assert.match(view, /canManage = permissions\.has\("MANAGE_OPERATIONS"\)/);
  assert.match(view, /if \(!canRead\) return/);
  assert.ok(view.indexOf("const updated = await api.updateDemoRequestStatus") < view.indexOf("setSelected(updated)"));
  assert.match(view, /activeToken\.current === token/);
  assert.match(view, /WorkspaceDialog/);
  assert.match(api, /demo-requests\/\$\{encodeURIComponent\(id\)\}\/status/);
  assert.match(api, /body: \{ status, expectedStatus \}/);
  assert.match(view, /nextStatus, selected\.status\)/);
  assert.match(view, /error\.status === 409[\s\S]*setNeedsRefresh\(true\)/);
  assert.match(view, /disabled=\{busy \|\| needsRefresh\}/);
  assert.match(view, /api\.demoRequestFollowUp\(credentials, id\)/, "Refresh the exact record and its notes instead of a filtered inbox page");
  assert.match(view, /demoRefreshDetail/);
  assert.doesNotMatch(view, /APP Gestión|UTM source \/ medium \/ campaign/);
  assert.match(types, /"demo-requests"/);
});

test("demo inbox validates the session before optional dashboard data and renders independently", async () => {
  const app = await readFile(new URL("../src/app/App.tsx", import.meta.url), "utf8");
  const refresh = app.slice(app.indexOf("async function refresh("), app.indexOf("function acceptInternalSession("));
  assert.ok(refresh.indexOf("await api.session(activeCredentials)") < refresh.indexOf("api.dashboard(activeCredentials)"));
  assert.ok(refresh.indexOf("setSession(nextSession)") < refresh.indexOf("api.dashboard(activeCredentials)"));
  assert.match(refresh, /if \(!isCurrent\(\)\) return false;\s*setSession/);
  assert.match(app, /activeView === "demo-requests" \? \(\s*session \? <DemoRequestsView[^]*?: !visibleData \?/);
  assert.match(app, /setUnauthorizedHandler/);
});
