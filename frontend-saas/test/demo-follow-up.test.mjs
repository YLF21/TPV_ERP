import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { isDemoActionOverdue } from "../src/features/marketing/demo-follow-up.mjs";
import { readDictionary } from "../test-support/source-helpers.mjs";

function inZone(zone, script) {
  const module = new URL("../src/features/marketing/demo-follow-up.mjs", import.meta.url).href;
  return JSON.parse(execFileSync(process.execPath, ["--input-type=module", "-e", `import { toLocalActionDate, actionDateToInstant } from ${JSON.stringify(module)}; ${script}`], { env: { ...process.env, TZ: zone }, encoding: "utf8" }));
}

test("follow-up uses the operator's civil time, including summer and winter offsets", () => {
  assert.deepEqual(inZone("Europe/Madrid", `console.log(JSON.stringify([
    toLocalActionDate('2026-01-15T10:30:19.123Z'), toLocalActionDate('2026-07-15T10:30:19.123Z'),
    actionDateToInstant('2026-01-15T11:30'), actionDateToInstant('2026-07-15T12:30'),
    toLocalActionDate(null), actionDateToInstant('')
  ]));`), ["2026-01-15T11:30", "2026-07-15T12:30", "2026-01-15T10:30:00.000Z", "2026-07-15T10:30:00.000Z", "", null]);
  assert.deepEqual(inZone("Asia/Shanghai", `console.log(JSON.stringify([toLocalActionDate('2026-10-27T16:15:00Z'), actionDateToInstant('2026-10-28T00:15')]));`), ["2026-10-28T00:15", "2026-10-27T16:15:00.000Z"]);
});

test("follow-up rejects invalid calendar dates, missing time and skipped DST times", () => {
  const badDates = ["2026-02-30T10:30", "2026-03-29T02:30", "2026-13-01T10:30", "2026-01-01", "not a date", "2026-01-01T24:30", "2026-01-01T11:30Z"];
  const result = inZone("Europe/Madrid", `console.log(JSON.stringify(${JSON.stringify(badDates)}.map(value => { try { actionDateToInstant(value); return false; } catch(error) { return error instanceof RangeError; } })));`);
  assert.ok(result.every(Boolean));
  assert.equal(inZone("Europe/Madrid", `console.log(JSON.stringify(actionDateToInstant('2028-02-29T09:00')));`), "2028-02-29T08:00:00.000Z");
});

test("overdue means a past dated action on an open request, not a closed or undated lead", () => {
  const now = Date.parse("2026-10-27T10:00:00Z");
  const request = { status: "NEW", nextAction: "Review the sample catalogue", nextActionAt: "2026-10-26T10:00:00Z" };
  assert.equal(isDemoActionOverdue(request, now), true);
  assert.equal(isDemoActionOverdue({ ...request, status: "CONTACTED" }, now), true);
  for (const change of [{ status: "CLOSED" }, { nextAction: " " }, { nextActionAt: null }, { nextActionAt: "invalid" }, { nextActionAt: "2026-10-27T10:00:00Z" }, { nextActionAt: "2026-10-28T10:00:00Z" }]) {
    assert.equal(isDemoActionOverdue({ ...request, ...change }, now), false);
  }
});

test("internal follow-up copy is complete and consistent in all three languages", async () => {
  const view = await readFile(new URL("../src/features/marketing/DemoRequestsView.tsx", import.meta.url), "utf8");
  const keys = [...new Set([...view.matchAll(/t\("(demo\w+)"\)/g)].map(match => match[1]))];
  for (const language of ["es", "en", "zh"]) {
    const dictionary = await readDictionary(language);
    for (const key of keys) assert.ok(dictionary[key]?.trim(), `${language}: missing ${key}`);
    for (const key of ["demoFollowUpSaved", "demoFollowUpConflict", "demoInternalNoteHelp", "demoLocalTime", "demoNotesLimited", "demoReadOnly"]) assert.ok(dictionary[key]);
    assert.doesNotMatch(dictionary.demoFollowUpSaved, /24\/7|100%|\bTODO\b/);
    assert.match(dictionary.demoFollowUpSaved, { es: /No se ha enviado ningún mensaje/, en: /No message has been sent/, zh: /未向客户发送任何消息/ }[language]);
    assert.match(dictionary.demoNotesLimited, /100/);
  }
});

// Source contracts complement executable date rules; these do not claim
// browser interaction, rendering geometry or a live network submission.
test("follow-up keeps server authority, session isolation and unsaved drafts", async () => {
  const view = await readFile(new URL("../src/features/marketing/DemoRequestsView.tsx", import.meta.url), "utf8");
  const api = await readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8");
  assert.match(view, /expectedVersion: selected\.followUpVersion/);
  const update = view.slice(view.indexOf("async function saveFollowUp"), view.indexOf("function statusLabel"));
  assert.ok(update.indexOf("await api.updateDemoRequestFollowUp") < update.indexOf("setSelected(updated.request)"));
  assert.ok(update.indexOf("await api.updateDemoRequestFollowUp") < update.indexOf("setDraft(draftFor(updated.request))"), "Clear the pending note only after the saved server response");
  assert.match(update, /error\.status === 409[\s\S]*setNeedsRefresh\(true\)/);
  assert.match(view, /operation\.current === generation && selectedId\.current === requestId/);
  assert.match(view, /activeToken\.current === token/);
  assert.match(view, /activePermissions\.current\.has\("MANAGE_OPERATIONS"\)/);
  assert.match(view, /preserveDraftOnReload\.current = dirty/);
  assert.match(view, /if \(dirty\).*demoFollowUpDirty/);
  assert.match(view, /draft\.nextActionAt === baseline\.nextActionAt \? selected\.nextActionAt/,
    "Preserve server seconds and repeated-hour offset when only changing the assignee or note");
  assert.match(view, /canManage && detailReady/);
  assert.match(view, /<details className="demo-follow-up-notes"/);
  assert.match(view, /maxLength=\{500\}/);
  assert.match(view, /maxLength=\{2000\}/);
  assert.match(view, /value=\{draft\.note\} disabled=\{busy\} readOnly=\{needsRefresh\}/, "Pending notes remain keyboard-selectable for copying while a conflict blocks edits");
  assert.doesNotMatch(view, /dangerouslySetInnerHTML|localStorage|sessionStorage/);
  assert.match(api, /demoRequestFollowUp\(credentials: Credentials, id: string\)/);
  assert.match(api, /demo-requests\/\$\{encodeURIComponent\(id\)\}\/follow-up/);
});
