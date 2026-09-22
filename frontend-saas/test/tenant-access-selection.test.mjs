import assert from "node:assert/strict";
import test from "node:test";
import { authorizedStoreSelection, canWriteTenantMasters, resolveCompanySelection } from "../src/features/tenant/access-selection.mjs";

test("multi-company access requires an explicit choice and retains an authorized selection", () => {
  const companies = [{ companyId: "one" }, { companyId: "two" }];
  assert.equal(resolveCompanySelection(companies, ""), "");
  assert.equal(resolveCompanySelection(companies, "two"), "two");
  assert.equal(resolveCompanySelection(companies, "revoked"), "");
});

test("one-company access selects that company, and removing all grants clears context", () => {
  assert.equal(resolveCompanySelection([{ companyId: "one" }], "revoked"), "one");
  assert.equal(resolveCompanySelection([], "one"), "");
});

test("store selections drop revoked grants and duplicates without silently selecting all", () => {
  const stores = [{ storeId: "active" }, { storeId: "historical", active: false }];
  assert.deepEqual(authorizedStoreSelection(stores, ["revoked", "active", "active", "historical"]), ["active", "historical"]);
  assert.deepEqual(authorizedStoreSelection(stores, []), []);
  assert.deepEqual(authorizedStoreSelection([], ["active"]), []);
});

test("company-wide writes require both privileges and a writable role", () => {
  assert.equal(canWriteTenantMasters("OWNER", ["READ_MASTERS"]), false);
  assert.equal(canWriteTenantMasters("OWNER", ["WRITE_MASTERS"]), false);
  assert.equal(canWriteTenantMasters("VIEWER", ["READ_MASTERS", "WRITE_MASTERS"]), false);
  assert.equal(canWriteTenantMasters("MANAGER", ["READ_MASTERS", "WRITE_MASTERS"]), true);
});
