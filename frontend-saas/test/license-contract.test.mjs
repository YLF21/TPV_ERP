import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const appSourceUrl = new URL("../src/App.tsx", import.meta.url);
const apiSourceUrl = new URL("../src/lib/api.ts", import.meta.url);
const typesSourceUrl = new URL("../src/lib/types.ts", import.meta.url);
const nginxConfigUrl = new URL("../nginx.conf", import.meta.url);
const devComposeUrl = new URL("../../backend-saas/docker-compose.dev.yml", import.meta.url);
const startSaasDevUrl = new URL("../../tools/start-saas-dev.ps1", import.meta.url);

test("license UI models and renders the effective expired status", async () => {
  const [source, types] = await Promise.all([
    readFile(appSourceUrl, "utf8"),
    readFile(typesSourceUrl, "utf8")
  ]);

  assert.match(types, /"VALIDA" \| "BLOQUEADA_MANUAL" \| "CADUCADA"/);
  assert.match(source, /status === "CADUCADA"/);
  assert.equal((source.match(/expiredStatus:/g) ?? []).length, 3);
});

test("license actions and company forms use their individual permissions", async () => {
  const source = await readFile(appSourceUrl, "utf8");

  assert.match(source, /permissions\.has\("EDIT_COMPANY_DATA"\)/);
  assert.match(source, /permissions\.has\("RENEW_LICENSE"\)/);
  assert.match(source, /permissions\.has\("BLOCK_LICENSE"\)/);
  assert.match(source, /permissions\.has\("UNBLOCK_LICENSE"\)/);
  assert.match(source, /showBlockAction=\{canBlockLicense\}/);
  assert.match(source, /showUnblockAction=\{canUnblockLicense\}/);
  assert.match(source, /canEditCompany=\{canEditCompany\}/);
  assert.match(source, /canRenewLicense=\{canRenewLicense\}/);
  assert.match(source, /fiscalIdentityLocked/);
  assert.match(source, /value=\{taxpayerType\}[\s\S]*?disabled \/>/);
  assert.match(source, /value=\{taxRegime\}[\s\S]*?disabled \/>/);
});

test("company editing preserves the fiscal classification returned by the license API", async () => {
  const [source, apiSource, types] = await Promise.all([
    readFile(appSourceUrl, "utf8"),
    readFile(apiSourceUrl, "utf8"),
    readFile(typesSourceUrl, "utf8")
  ]);

  assert.match(types, /taxpayerType: TaxpayerType;/);
  assert.match(types, /taxRegime: TaxRegime;/);
  assert.match(types, /commercialProfile: CommercialProfile;/);
  assert.match(source, /setTaxpayerType\(license\.taxpayerType\)/);
  assert.match(source, /setTaxRegime\(license\.taxRegime\)/);
  assert.match(source, /setCommercialProfile\(license\.commercialProfile\)/);
  assert.match(source, /license\?\.companyName,[\s\S]*?license\?\.commercialProfile,[\s\S]*?license\?\.validUntil,[\s\S]*?license\?\.maxPda,/);
  assert.match(source, /commercialProfile,[\s\S]*?\}\);/);
  assert.match(apiSource, /commercialProfile: CommercialProfile;/);
});

test("company detail discards stale operations and fiscal provisioning responses", async () => {
  const source = await readFile(appSourceUrl, "utf8");

  assert.match(source, /const companyDetailRequestId = useRef\(0\);/);
  assert.match(source, /selectedCompanyIdRef\.current = license\?\.companyId \?\? null;/);
  assert.match(source, /requestId === companyDetailRequestId\.current[\s\S]*?selectedCompanyIdRef\.current === companyId/);
  assert.match(source, /loaded\.companyId !== companyId/);
  assert.match(source, /fiscalProvisioning\.companyId !== companyId/);
  assert.match(source, /operations\.companyId !== companyId/);
  assert.match(source, /saved\.companyId !== companyId/);
});

test("tenant user management keeps responses and mutations scoped to the selected company", async () => {
  const source = await readFile(appSourceUrl, "utf8");
  const usersView = source.slice(source.indexOf("function UsersView("), source.indexOf("function AuditView("));

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
