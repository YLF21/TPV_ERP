import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { readSources } from "../test-support/source-helpers.mjs";

const apiSourceUrl = new URL("../src/lib/api.ts", import.meta.url);
const typesSourceUrl = new URL("../src/lib/types.ts", import.meta.url);
const nginxConfigUrl = new URL("../nginx.conf", import.meta.url);
const devComposeUrl = new URL("../../backend-saas/docker-compose.dev.yml", import.meta.url);
const startSaasDevUrl = new URL("../../tools/start-saas-dev.ps1", import.meta.url);

test("license UI models and renders the effective expired status", async () => {
  const [source, types] = await Promise.all([
    readSources("features/companies/CompaniesView.tsx", "features/companies/CompanyDetail.tsx", "shared/ui.tsx", "shared/license-tables.tsx", "features/users/UsersView.tsx", "i18n/es.ts", "i18n/en.ts", "i18n/zh.ts"),
    readFile(typesSourceUrl, "utf8")
  ]);

  assert.match(types, /"VALIDA" \| "BLOQUEADA_MANUAL" \| "CADUCADA"/);
  assert.match(await readSources("shared/lib.tsx"), /status === "CADUCADA"/);
  assert.equal((source.match(/expiredStatus:/g) ?? []).length, 3);
});

test("license actions and company forms use their individual permissions", async () => {
  const source = await readSources("features/companies/CompaniesView.tsx", "features/companies/CompanyDetail.tsx", "shared/ui.tsx", "shared/license-tables.tsx", "features/users/UsersView.tsx", "i18n/es.ts", "i18n/en.ts", "i18n/zh.ts");

  assert.match(source, /permissions\.has\("EDIT_COMPANY_DATA"\)/);
  const workspace = await readSources("features/licenses/LicenseWorkspace.tsx", "features/licenses/LicenseConfiguration.tsx");
  assert.match(workspace, /permissions\.has\("RENEW_LICENSE"\)/);
  assert.match(workspace, /permissions\.has\("BLOCK_LICENSE"\)\s*&&\s*detail\.status !== "BLOQUEADA_MANUAL"/);
  assert.match(workspace, /permissions\.has\("UNBLOCK_LICENSE"\)\s*&&\s*detail\.status === "BLOQUEADA_MANUAL"/);
  assert.match(workspace, /action\("block"\)/);
  assert.match(workspace, /action\("unblock"\)/);
  assert.match(source, /canEditCompany=\{canEditCompany\}/);
  assert.doesNotMatch(source, /canRenewLicense=\{canRenewLicense\}/);
  assert.match(source, /identityLocked/);
  assert.match(source, /value=\{company\.taxpayerType\}[\s\S]*?disabled \/>/);
  assert.match(source, /value=\{company\.taxId\}[\s\S]*?disabled \/>/);
  assert.doesNotMatch(source, /value=\{company\.commercialProfile\}/);
});

test("company editing uses the company registry and preserves corporate fiscal identity", async () => {
  const [source, apiSource, types] = await Promise.all([
    readSources("features/companies/CompaniesView.tsx", "features/companies/CompanyDetail.tsx", "shared/ui.tsx", "shared/license-tables.tsx", "features/users/UsersView.tsx", "i18n/es.ts", "i18n/en.ts", "i18n/zh.ts"),
    readFile(apiSourceUrl, "utf8"),
    readFile(typesSourceUrl, "utf8")
  ]);

  assert.match(types, /taxpayerType: TaxpayerType;/);
  assert.match(types, /taxRegime: TaxRegime;/);
  assert.match(types, /commercialProfile: CommercialProfile \| null;/);
  assert.match(await readSources("lib/workspace-api.ts"), /commercialProfile: CommercialProfile;/);
  const companySource = await readSources("features/companies/CompaniesView.tsx", "features/companies/CompanyDetail.tsx");
  assert.match(companySource, /api\.companies\(credentials\)/);
  assert.match(companySource, /api\.saveCompanyProfile\(credentials, companyId, form\)/);
  assert.match(companySource, /api\.companyProfile\(credentials, company\.companyId\)/);
  assert.doesNotMatch(companySource, /commercialProfile|api\.editCompany|api\.updateCompanyOperations/);
  assert.doesNotMatch(companySource, /uniqueCompanies|setTaxRegime|license\.companyId|renewSelectedLicense|setPairingCode/);
  assert.doesNotMatch(companySource, /companyForm\.(?:storeCode|storeName|storeAddress|validUntil|maxWindows|maxPda|impuestos)/);
  assert.match(apiSource, /saveCompanyProfile[\s\S]*?\/profile/);
});

test("company detail remounts per company and guards delayed mutations", async () => {
  const companies = await readSources("features/companies/CompaniesView.tsx");
  const source = await readSources("features/companies/CompanyDetail.tsx");
  const remote = await readSources("app/RefreshContext.tsx");
  assert.match(companies, /<CompanyDetail\s+key=\{selectedCompany\.companyId\}/);
  assert.match(source, /mounted\.current = false;\s*mutation\.current\+\+/);
  assert.match(source, /mounted\.current && id === mutation\.current/);
  assert.match(source, /companyId === activeContext\.current\.companyId && token === activeContext\.current\.token/);
  assert.match(source, /profile\.data\?\.companyId !== company\.companyId/);
  assert.match(source, /saved\.companyId !== companyId/);
  assert.match(remote, /if \(current\) setResult\(/);
  assert.match(remote, /current = false;/);
});

test("company profile failures are retryable and never create writable defaults", async () => {
  const source = await readSources("features/companies/CompaniesView.tsx", "features/companies/CompanyDetail.tsx", "shared/ui.tsx", "shared/license-tables.tsx", "features/users/UsersView.tsx", "i18n/es.ts", "i18n/en.ts", "i18n/zh.ts");
  const detail = await readSources("features/companies/CompanyDetail.tsx");

  assert.match(detail, /<LoadState \{\.\.\.profile\} \/>/);
  assert.match(detail, /profile\.loading \|\| profile\.error/);
  assert.match(detail, /!dirty\.current && profile\.data\?\.companyId === company\.companyId/);
  assert.doesNotMatch(detail, /defaultCompanyOperations/);
});

test("tenant user management keeps responses and mutations scoped to the selected company", async () => {
  const source = await readSources("features/companies/CompaniesView.tsx", "features/companies/CompanyDetail.tsx", "shared/ui.tsx", "shared/license-tables.tsx", "features/users/UsersView.tsx", "i18n/es.ts", "i18n/en.ts", "i18n/zh.ts");
  const usersView = await readSources("features/users/UsersView.tsx");

  assert.match(usersView, /const tenantUsersRequestId = useRef\(0\);/);
  assert.match(usersView, /const tenantUsersMutationId = useRef\(0\);/);
  assert.match(usersView, /selectedTenantCompanyIdRef\.current !== companyId/);
  assert.match(usersView, /tenantUsersCompanyId === companyId/);
  assert.match(usersView, /user\.companyId === companyId && user\.username === username/);
  assert.match(usersView, /isCurrentTenantMutation\(companyId, mutationId\)/);
  assert.match(usersView, /visibleTenantUsers\.map/);
  assert.doesNotMatch(usersView, /<option value="OWNER">OWNER<\/option>/);
  assert.match(usersView, /<option value="MANAGER">MANAGER<\/option>/);
  assert.match(usersView, /<option value="VIEWER">VIEWER<\/option>/);
  assert.match(usersView, /<option value="BILLING">BILLING<\/option>/);
});

test("the SaaS edge discards caller supplied forwarded addresses", async () => {
  const nginx = await readFile(nginxConfigUrl, "utf8");

  assert.match(nginx, /proxy_set_header X-Forwarded-For \$remote_addr;/);
  assert.doesNotMatch(nginx, /\$proxy_add_x_forwarded_for/);
});

test("the SaaS DEV edge preserves the browser origin accepted by CORS", async () => {
  const [nginx, compose, launcher] = await Promise.all([
    readFile(nginxConfigUrl, "utf8"),
    readFile(devComposeUrl, "utf8"),
    readFile(startSaasDevUrl, "utf8")
  ]);

  assert.match(nginx, /proxy_set_header Host \$http_host;/);
  assert.match(nginx, /proxy_set_header X-Forwarded-Host \$http_host;/);
  assert.match(nginx, /proxy_set_header X-Forwarded-Port \$server_port;/);
  assert.match(compose, /http:\/\/127\.0\.0\.1:8088,http:\/\/localhost:8088/);
  assert.match(launcher, /http:\/\/127\.0\.0\.1:\$WebPort,http:\/\/localhost:\$WebPort/);
  assert.match(launcher, /TPV_SAAS_CORS_ALLOWED_ORIGINS/);
});
