import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import test from "node:test";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));

// Controlled source contracts and pure localisation only: no browser, HTTP,
// actual Back/Forward, DOM values or submitted customer request is exercised.
test("manual contact choices reach the existing page-level preference without persisting form data", async () => {
  const page = await readFile(new URL("../src/MarketingPage.tsx", import.meta.url), "utf8");
  assert.match(page, /initialApps=\{preferredApps\} onSelectionChange=\{setPreferredApps\}/);
  const selection = page.slice(page.indexOf("function selectApplications("), page.indexOf("function toggleApplication("));
  assert.match(selection, /setSelectedApps\(apps\)/);
  assert.match(selection, /onSelectionChange\(apps\)/);
  assert.match(selection, /clearSubmissionFeedback\(\)/);
  const contact = page.slice(page.indexOf("function ContactPage("));
  const initialEffect = contact.slice(contact.indexOf("useEffect("), contact.indexOf("async function refreshAvailability("));
  assert.match(initialEffect, /setSelectedApps\(\[\.\.\.initialApps\]\)/);
  assert.doesNotMatch(initialEffect, /onSelectionChange\(/, "Synchronising a URL preference must not create a callback loop");
  assert.doesNotMatch(contact, /localStorage|sessionStorage/);
  assert.match(contact, /fullPackageSelected[\s\S]*appMeta\.map\(\(\{ id \}\) => id\)/);
  assert.match(contact, /applications: requestedApplications/, "A receipt must keep the exact applications actually sent");
});

test("existing field errors translate from stable codes in the currently selected language", async () => {
  const server = await createServer({ configFile: false, root, plugins: [react()], server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: "custom", optimizeDeps: { noDiscovery: true, include: [] } });
  try {
    assert.equal(server.httpServer, null);
    const [{ getContactFieldError }, { getMarketingContent }, { demoFieldLimits }] = await Promise.all([
      server.ssrLoadModule("/src/MarketingPage.tsx"), server.ssrLoadModule("/src/marketingI18n.ts"), server.ssrLoadModule("/src/demoRequests.ts")
    ]);
    const errors = { name: "REQUIRED", privacyAccepted: "MUST_ACCEPT", message: "TOO_LONG", email: "INVALID", company: "INVALID" };
    for (const language of ["es", "en", "zh"]) {
      const copy = getMarketingContent(language);
      assert.equal(getContactFieldError("name", errors.name, copy), copy.contact.delivery.fieldRequired);
      assert.equal(getContactFieldError("privacyAccepted", errors.privacyAccepted, copy), copy.contact.delivery.fieldRequired);
      assert.equal(getContactFieldError("message", errors.message, copy), copy.contact.delivery.tooLong.replace("{max}", String(demoFieldLimits.message)));
      assert.equal(getContactFieldError("email", errors.email, copy), copy.contact.delivery.emailInvalid);
      assert.equal(getContactFieldError("company", errors.company, copy), copy.contact.delivery.validation);
    }
    assert.deepEqual(errors, { name: "REQUIRED", privacyAccepted: "MUST_ACCEPT", message: "TOO_LONG", email: "INVALID", company: "INVALID" });
    const page = await readFile(new URL("../src/MarketingPage.tsx", import.meta.url), "utf8");
    assert.match(page, /getContactFieldError\(field, fieldErrors\[field\], copy\)/);
    assert.match(page, /setFieldErrors\(requestError\.fields\)/);
    assert.doesNotMatch(page, /errors\[field\] = copy\./);
    assert.doesNotMatch(page, /key=\{language\}|\[language\][\s\S]{0,30}form\.reset\(/,
      "Changing language must not remount/reset uncontrolled contact fields");
  } finally { await server.close(); }
});

test("unpublished download actions describe the demo they actually request", async () => {
  const dialogs = await readFile(new URL("../src/MarketingDialogs.tsx", import.meta.url), "utf8");
  const copy = await readFile(new URL("../src/marketingI18n.ts", import.meta.url), "utf8");
  const download = dialogs.slice(dialogs.indexOf("function DownloadCenterModal("), dialogs.indexOf("function ProductTourModal("));
  assert.match(download, /onClick=\{\(\) => onRequestSetup\(id\)\}\>\{copy\.common\.demo\}/);
  assert.doesNotMatch(copy, /requestSetup:/);
  assert.match(download, /url \? <div className="mk-download-actions"/);
  assert.match(download, /href=\{url\}/, "Published installers retain their configured download link");
  assert.match(download, /copy\.downloads\.downloadNow/);
  const page = await readFile(new URL("../src/MarketingPage.tsx", import.meta.url), "utf8");
  assert.match(page, /onRequestSetup=\{\(app\) => \{ setDownloadsOpen\(false\); requestApplications\(\[app\]\); \}\}/);
});
