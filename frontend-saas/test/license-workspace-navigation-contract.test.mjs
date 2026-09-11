import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("licenses workspace is divided into company, license and VeriFactu screens", async () => {
  const app = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");

  assert.match(app, /type LicenseWorkspaceSection = "companies" \| "licenses" \| "verifactu"/);
  assert.match(app, /className="license-workspace-nav"/);
  assert.match(app, /navigateSection\("companies"\)/);
  assert.match(app, /navigateSection\("licenses"\)/);
  assert.match(app, /navigateSection\("verifactu"\)/);
  assert.match(app, /activeSection === "companies"/);
  assert.match(app, /activeSection === "licenses"/);
  assert.match(app, /activeSection === "verifactu"/);
});

test("license workspace subroutes support browser history and deep links", async () => {
  const app = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");

  assert.match(app, /const nextHash = .*licenses.*section/);
  assert.match(app, /window\.history\.pushState\(\{ view: "licenses", section \}/);
  assert.match(app, /split\("\/"\)\[0\]/);
  assert.match(app, /function readLicenseWorkspaceSection\(\)/);
});

test("selecting a company from licenses opens its management screen", async () => {
  const app = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");

  assert.match(app, /onSelectCompany=\{\(companyId\) => \{\s*setSelectedCompanyId\(companyId\);\s*navigateSection\("companies"\)/);
  assert.match(app, /className="content-section company-selector-section"/);
});
