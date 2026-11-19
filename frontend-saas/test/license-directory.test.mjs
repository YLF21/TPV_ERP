import assert from "node:assert/strict";
import test from "node:test";
import { licenseExpiryShortcut, readLicenseDirectoryFilters } from "../src/features/licenses/license-directory.mjs";

test("license directory defaults to active but honors explicit all-status company and store links", () => {
  assert.equal(readLicenseDirectoryFilters("#/licenses").status, "VALIDA");
  const company = readLicenseDirectoryFilters("#/licenses?companyId=company-a&status=");
  assert.deepEqual([company.status, company.companyId, company.storeId], ["", "company-a", ""]);
  const store = readLicenseDirectoryFilters("#/licenses?storeId=store-a&companyId=company-a&status=");
  assert.deepEqual([store.status, store.companyId, store.storeId], ["", "company-a", "store-a"]);
  assert.equal(readLicenseDirectoryFilters("#/licenses/detail?licenseId=license-a").status, "VALIDA");
  assert.equal(readLicenseDirectoryFilters("#/licenses?status=CADUCADA").status, "CADUCADA");
  assert.equal(readLicenseDirectoryFilters("#/licenses?status=BLOQUEADA_MANUAL").status, "BLOQUEADA_MANUAL");
});

test("expiry shortcuts keep the UTC time across the daylight-saving transition", () => {
  const now = new Date("2026-10-23T23:59:45.123Z");
  assert.deepEqual(licenseExpiryShortcut(7, now), { days: 7, before: "2026-10-30T23:59:45.123Z" });
  assert.deepEqual(licenseExpiryShortcut(30, now), { days: 30, before: "2026-11-22T23:59:45.123Z" });
});
