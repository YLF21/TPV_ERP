import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import test from "node:test";
import {
  companyDateRange, companyProvinceFilterValue, emptyCompanyFilters, matchesCompanyFilters,
} from "../src/features/companies/company-filters.mjs";

const company = {
  companyName: "Comercial Álvarez", taxId: "B12345674", taxpayerType: "SOCIEDAD",
  createdAt: new Date(2026, 8, 20, 15, 30).toISOString(),
  contactName: "María José", contactPhone: "+34 (928) 12-34-56", contactEmail: "Ventas@Ejemplo.es",
  companyAddress: { provincia: "Palmas, Las", ciudad: "Agüimes", codigoPostal: "35260" },
  owners: [{ name: "Primer Dueño", taxId: "12345678Z" }, { name: "Ana Muñoz", taxId: "X1234567L" }],
};
const matches = (filters, row = company) => matchesCompanyFilters(row, { ...emptyCompanyFilters(), ...filters });

test("empty filters are independent and absent company data only fails an active filter", () => {
  const first = emptyCompanyFilters();
  first.query = "changed";
  assert.equal(emptyCompanyFilters().query, "");
  assert.equal(Object.keys(emptyCompanyFilters()).length, 11);
  assert.equal(matches({}, {}), true);
  for (const filters of [
    { query: "empresa" }, { province: "35" }, { taxpayerType: "SOCIEDAD" }, { contactName: "Ana" },
    { contactPhone: "928" }, { contactEmail: "ventas" }, { owner: "Ana" }, { city: "Agüimes" },
    { postalCode: "35260" }, { dateFrom: "2026-09-20" }, { dateTo: "2026-09-20" },
  ]) assert.equal(matches(filters, { companyAddress: null, owners: null, createdAt: "invalid" }), false);
});

test("Empresa/NIF searches only the company identity without accents or case", () => {
  for (const query of ["alvarez", " COMERCIAL ÁLVAREZ ", "b123456", "  "]) assert.equal(matches({ query }), true);
  for (const query of ["María", "928", "Ventas@Ejemplo", "Muñoz", "X1234567L", "Agüimes", "Palmas"])
    assert.equal(matches({ query }), false, query);
});

test("all independent fields combine with AND and taxpayer type remains exact", () => {
  const filters = {
    query: "alvarez", province: "35", taxpayerType: "SOCIEDAD", dateFrom: "2026-09-20", dateTo: "2026-09-20",
    contactName: "maria jose", contactPhone: "928123456", contactEmail: "VENTAS@EJEMPLO", owner: "munoz",
    city: "AGUIMES", postalCode: "526",
  };
  assert.equal(matches(filters), true);
  for (const field of Object.keys(filters)) {
    const value = field === "dateFrom" ? "2026-09-21" : field === "dateTo" ? "2026-09-19" : "does-not-match";
    assert.equal(matches({ ...filters, [field]: value }), false, field);
  }
  assert.equal(matches({ taxpayerType: "sociedad" }), false);
  assert.equal(matches({ taxpayerType: "AUTONOMO" }), false);
});

test("owner filtering includes every owner name and DNI/NIE but not their contact fields", () => {
  for (const owner of ["primer dueno", "12345678z", "ana munoz", "x1234567l", "1234567"])
    assert.equal(matches({ owner }), true, owner);
  assert.equal(matches({ owner: "Ana" }, { ...company, owners: [company.owners[0]] }), false);
  assert.equal(matches({ owner: "nobody" }), false);
  assert.equal(matches({ owner: "owner-only@example.es" }, {
    ...company, owners: [{ name: "Someone", taxId: "12345678Z", email: "owner-only@example.es" }],
  }), false);
});

test("telephone matching ignores spaces, hyphens and parentheses on either side", () => {
  for (const contactPhone of ["928123456", "(928) 12 34 56", "928-123-456", "+34 928–123–456"])
    assert.equal(matches({ contactPhone }), true, contactPhone);
  assert.equal(matches({ contactPhone: "928-999" }), false);
  assert.equal(matches({ contactPhone: "(928) 123-456" }, { ...company, contactPhone: "928123456" }), true);
});

test("recognized province aliases share INE values without changing the address", () => {
  for (const [stored, code] of [
    ["Palmas, Las", "35"], ["  LAS PALMAS  ", "35"], ["Álava", "01"], ["Araba", "01"],
    ["La Coruña", "15"], ["A Coruña", "15"], ["Castelló", "12"], ["València", "46"],
  ]) {
    const row = { ...company, companyAddress: Object.freeze({ provincia: stored }) };
    assert.equal(companyProvinceFilterValue(stored), code);
    assert.equal(matches({ province: code }, row), true);
    assert.equal(matches({ province: "50" }, row), false);
    assert.equal(row.companyAddress.provincia, stored);
  }
});

test("unknown historical provinces use exact raw values without guessing aliases", () => {
  for (const stored of ["Tenerife", "Las Palmas de Gran Canaria", "Bayern", "  Histórica  ", "raw:original", "35"]) {
    const row = { ...company, companyAddress: { provincia: stored } };
    assert.equal(companyProvinceFilterValue(stored), `raw:${stored}`);
    assert.equal(matches({ province: `raw:${stored}` }, row), true);
    assert.equal(matches({ province: "35" }, row), false);
    assert.equal(matches({ province: "38" }, row), false);
    assert.equal(matches({ province: `raw:${stored.toUpperCase()}` }, row), stored === stored.toUpperCase());
  }
  assert.equal(companyProvinceFilterValue(""), "");
  assert.equal(companyProvinceFilterValue(null), "");
  assert.equal(matches({ province: "raw:Histórica" }, { ...company, companyAddress: { provincia: "  Histórica  " } }), false);
});

test("date intervals accept open bounds and reject inverted or impossible calendar dates", () => {
  assert.deepEqual(companyDateRange("", ""), { from: null, toExclusive: null });
  assert.equal(matches({ dateFrom: "2026-09-20" }), true);
  assert.equal(matches({ dateTo: "2026-09-20" }), true);
  for (const [from, to] of [["2026-09-21", "2026-09-20"], ["2026-02-29", ""], ["", "2026-04-31"], ["2026-2-03", ""], ["invalid", ""]]) {
    assert.equal(companyDateRange(from, to), null);
    assert.equal(matches({ dateFrom: from, dateTo: to }), false);
  }
  assert.notEqual(companyDateRange("2024-02-29", "2024-02-29"), null);
  const interval = companyDateRange("2026-09-20", "2026-09-20");
  for (const [createdAt, expected] of [
    [interval.from - 1, false], [interval.from, true], [interval.toExclusive - 1, true], [interval.toExclusive, false],
  ]) assert.equal(matches({ dateFrom: "2026-09-20", dateTo: "2026-09-20" }, { ...company, createdAt: new Date(createdAt).toISOString() }), expected);
});

test("local-day filtering remains inclusive across both DST changes and UTC date differences", () => {
  const moduleUrl = new URL("../src/features/companies/company-filters.mjs", import.meta.url).href;
  for (const zone of ["Atlantic/Canary", "Europe/Madrid", "America/New_York"]) {
    const result = spawnSync(process.execPath, ["--input-type=module", "-e", `
      import assert from "node:assert/strict";
      import { companyDateRange, emptyCompanyFilters, matchesCompanyFilters } from ${JSON.stringify(moduleUrl)};
      const days = process.env.TZ === "America/New_York" ? ["2026-03-08", "2026-11-01"] : ["2026-03-29", "2026-10-25"];
      for (const [index, day] of days.entries()) {
        const interval = companyDateRange(day, day);
        assert.equal((interval.toExclusive - interval.from) / 3600000, index === 0 ? 23 : 25);
        assert.equal(new Date(interval.from).getHours(), 0);
        assert.equal(new Date(interval.toExclusive).getHours(), 0);
        const filters = { ...emptyCompanyFilters(), dateFrom: day, dateTo: day };
        for (const [timestamp, expected] of [[interval.from - 1, false], [interval.from, true], [interval.toExclusive - 1, true], [interval.toExclusive, false]]) {
          assert.equal(matchesCompanyFilters({ createdAt: new Date(timestamp).toISOString() }, filters), expected);
        }
      }
      const filters = { ...emptyCompanyFilters(), dateFrom: "2026-09-20", dateTo: "2026-09-20" };
      for (const hour of [0, 12, 23]) {
        const local = new Date(2026, 8, 20, hour, 30).toISOString();
        assert.equal(matchesCompanyFilters({ createdAt: local }, filters), true);
      }
    `], { encoding: "utf8", env: { ...process.env, TZ: zone } });
    assert.equal(result.status, 0, `${zone}: ${result.stderr || result.stdout}`);
  }
});
