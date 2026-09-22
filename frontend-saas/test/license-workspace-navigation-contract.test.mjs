import assert from "node:assert/strict";
import test from "node:test";
import { readSources } from "../test-support/source-helpers.mjs";

test("company, store, active license and creation screens have independent navigation entries", async () => {
  const navigation = await readSources("app/navigation.ts");
  const app = await readSources("app/App.tsx");
  for (const route of ["companies", "stores", "licenses", "create-license"]) {
    assert.match(navigation, new RegExp(`view: "${route}"`));
    assert.match(app, new RegExp(`activeView === "${route}"`));
  }
  assert.match(navigation, /\{[^{}]*view:\s*"create-license"[^{}]*permission:\s*"ADD_COMPANY"[^{}]*\}/);
  assert.match(app, /<LicenseWorkspace/);
  assert.match(app, /<StoresView/);
});

test("workspace routes support browser history and deep links", async () => {
  const app = await readSources("app/App.tsx");
  const routing = await readSources("shared/lib.tsx");
  assert.match(app, /const nextHash = `#\/\$\{view\}`/);
  assert.match(app, /window\.history\.pushState\(\{ view \}/);
  assert.match(app, /addEventListener\("popstate"/);
  assert.match(app, /addEventListener\("hashchange"/);
  assert.match(routing, /split\("\/"\)\[0\]/);
  assert.match(routing, /VALID_VIEWS\.includes\(candidate\)/);
});

test("company list opens creation and company-scoped detail dialogs while license history remains filterable", async () => {
  const companies = await readSources("features/companies/CompaniesView.tsx");
  const licenses = await readSources("features/licenses/LicenseWorkspace.tsx");
  assert.match(companies, /api\.companies\(credentials\)/);
  assert.match(companies, /<SaasDataTable[\s\S]*?tableKey="companies"/);
  assert.match(companies, /onOpen=\{openCompany\}/);
  assert.match(companies, /setSelectedCompany\(company\)/);
  assert.match(companies, /\{creating && <WorkspaceDialog/);
  assert.match(companies, /\{selectedCompany && <WorkspaceDialog key=\{selectedCompany\.companyId\}/);
  assert.match(companies, /<CompanyDetail key=\{selectedCompany\.companyId\}/);
  assert.doesNotMatch(companies, /chooseCompany|setSelectedCompanyId/);
  assert.match(licenses, /useState\("VALIDA"\)/);
  assert.match(licenses, /<option value="">\{l\("allLicenses"\)\}/);
  assert.match(licenses, /<option value="CADUCADA">/);
  assert.match(licenses, /<option value="BLOQUEADA_MANUAL">/);
});
