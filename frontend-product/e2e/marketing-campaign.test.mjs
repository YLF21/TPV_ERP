import assert from "node:assert/strict";
import { test } from "node:test";
import { createCampaignAttribution, marketingContactSearch, parseRequestedApplications } from "../src/marketingCampaign.ts";

const order = ["venta", "gestion", "pda", "saas"];
const location = { pathname: "/", hash: "#/producto/apps", search: "" };

test("application choices use an allowlist, stable order and explicit full package", () => {
  assert.deepEqual(parseRequestedApplications("?apps=saas,venta,venta,unknown,pda"), ["venta", "pda", "saas"]);
  assert.deepEqual(parseRequestedApplications("?apps=all"), order);
  assert.deepEqual(parseRequestedApplications("?apps=saas,pda,gestion,venta"), order);
  assert.deepEqual(parseRequestedApplications("?utm_source=wechat&apps=gestion"), ["gestion"]);
  assert.deepEqual(parseRequestedApplications("?apps=venta%2Cgestion"), ["venta", "gestion"]);
  assert.deepEqual(parseRequestedApplications("?apps=%20pda%20"), ["pda"]);
  for (const query of ["", "?apps=", "?apps=VENTA", "?apps=__proto__,constructor", "?apps=https://elsewhere.example", "?apps=javascript:alert(1)", "?apps=venta&apps=saas", "?apps=" + "a".repeat(81), "?apps=" + Array(9).fill("venta").join(","), "?apps=venta&ignored=" + "a".repeat(4096)]) {
    assert.equal(parseRequestedApplications(query), null, query.slice(0, 80));
  }
  assert.equal(Object.prototype.polluted, undefined);
});

test("contact links carry only normalized product selection", () => {
  assert.equal(marketingContactSearch(["saas", "venta", "venta"]), "?apps=venta,saas");
  assert.equal(marketingContactSearch(order), "?apps=all");
  assert.equal(marketingContactSearch([]), "");
  assert.equal(marketingContactSearch(["unknown", "__proto__", "https://elsewhere.example"]), "");
  for (let mask = 1; mask < 16; mask += 1) {
    const apps = order.filter((_, index) => mask & (1 << index));
    assert.deepEqual(parseRequestedApplications(marketingContactSearch(apps)), apps);
  }
});

test("attribution keeps only known campaign fields and query-free landing/referrer", () => {
  const result = createCampaignAttribution({ pathname: "/", hash: "#/producto/apps?email=someone@example.test#ignored", search: "?utm_source=wechat&utm_medium=social&utm_campaign=oto%C3%B1o_2026&email=person@example.test&token=secret" }, "https://user:password@source.example/articles?email=person@example.test#private");
  assert.deepEqual(result, { landingPath: "/#/producto/apps", referrer: "https://source.example/articles", utmSource: "wechat", utmMedium: "social", utmCampaign: "otoño_2026" });
  assert.deepEqual(Object.keys(result).sort(), ["landingPath", "referrer", "utmSource", "utmMedium", "utmCampaign"].sort());
  assert.equal(createCampaignAttribution({ ...location, pathname: "/espos/?email=private@example.test", hash: "#/producto/contacto?apps=all" }, "").landingPath, "/espos/#/producto/contacto");
  assert.equal(createCampaignAttribution({ ...location, hash: "#private@example.test" }, "").landingPath, "/");
});

test("first safe landing is retained and current nonempty UTMs replace individual previous values", () => {
  const first = createCampaignAttribution({ ...location, hash: "#/producto/inicio", search: "?utm_source=newsletter&utm_medium=email&utm_campaign=launch" }, "https://first.example/read?q=one");
  const next = createCampaignAttribution({ ...location, hash: "#/producto/contacto", search: "?utm_source=wechat&utm_medium=%20%20&utm_campaign=summer" }, "https://later.example/", first);
  assert.deepEqual(next, { landingPath: "/#/producto/inicio", referrer: "https://first.example/read", utmSource: "wechat", utmMedium: "email", utmCampaign: "summer" });
  assert.deepEqual(createCampaignAttribution(location, "", next), next);
  assert.equal(createCampaignAttribution(location, "", { ...first, landingPath: "/#/producto/apps?email=private@example.test" }).landingPath, "/#/producto/apps");
  assert.equal(createCampaignAttribution(location, "", { ...first, referrer: "https://first.example/read?token=private" }).referrer, "https://first.example/read");
});

test("obvious personal data and credentials are not retained in campaign context", () => {
  for (const value of ["person@example.test", "person%40example.test", "person%2540example.test", "+34600123456", "600123456", "600 123 456", "+34 600 123 456", "access_token=private", "sk-proj-abcdefghijk", "campaign_sk-proj-abcdefghijk", "ghp_abcdefghijklmnop", "wxid_6e9mzgmj8e6912", "eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjEyM30.signature", "api_key", "password", "a".repeat(40) + "1", "6f9619ff-8b86-d011-b42d-00c04fc964ff", "<script>", "hello\nworld", "https://elsewhere.example/path?email=private", "javascript:alert(1)"]) {
    const search = `?utm_source=${encodeURIComponent(value)}&utm_medium=${encodeURIComponent(value)}&utm_campaign=${encodeURIComponent(value)}`;
    const result = createCampaignAttribution({ ...location, search }, "", { utmSource: value, utmMedium: value, utmCampaign: value });
    assert.equal(result.utmSource, "", value);
    assert.equal(result.utmMedium, "", value);
    assert.equal(result.utmCampaign, "", value);
  }
  assert.equal(createCampaignAttribution(location, "https://source.example/users/person%40example.test?private=1").referrer, "https://source.example/");
  assert.equal(createCampaignAttribution(location, "", { landingPath: "/users/person%40example.test" }).landingPath, "/#/producto/apps");
  for (const referrer of ["javascript:alert(1)", "file:///private/report", "data:text/plain,private", "not a URL", "https://source.example/" + "a".repeat(4096)]) {
    assert.equal(createCampaignAttribution(location, referrer).referrer, "");
  }
});

test("input sizes, duplicate campaign keys and malformed stored values are bounded safely", () => {
  const result = createCampaignAttribution({ ...location, search: `?utm_source=${"x".repeat(150)}&utm_medium=${"y".repeat(150)}&utm_campaign=${"z".repeat(200)}` }, "https://source.example/" + "x".repeat(500));
  assert.equal(result.utmSource.length, 100);
  assert.equal(result.utmMedium.length, 100);
  assert.equal(result.utmCampaign.length, 160);
  assert.ok(result.landingPath.length <= 500);
  assert.ok(result.referrer.length <= 512);
  const duplicate = createCampaignAttribution({ ...location, search: "?utm_source=one&utm_source=two" }, "", { utmSource: "previous" });
  assert.equal(duplicate.utmSource, "previous");
  const huge = createCampaignAttribution({ ...location, search: "?utm_source=wechat&ignored=" + "x".repeat(4096) }, "");
  assert.equal(huge.utmSource, "");
  for (const previous of [null, [], "private", 12, { landingPath: 123, referrer: {}, utmSource: ["private"], utmMedium: false }, Object.create({ utmSource: "inherited" })]) {
    assert.deepEqual(createCampaignAttribution(location, "", previous), { landingPath: "/#/producto/apps", referrer: "", utmSource: "", utmMedium: "", utmCampaign: "" });
  }
  const throwing = Object.defineProperty({}, "utmSource", { get() { throw new Error("untrusted getter"); } });
  assert.equal(createCampaignAttribution(location, "", throwing).utmSource, "");
  assert.equal(createCampaignAttribution({ ...location, pathname: "//elsewhere.example/", hash: "#/producto/contacto" }, "").landingPath, "/#/producto/contacto");
  assert.equal(createCampaignAttribution({ ...location, pathname: "/" + "x".repeat(500) }, "").landingPath, "/#/producto/apps");
  assert.equal(createCampaignAttribution({ ...location, hash: "#/producto/apps?ignored=" + "x".repeat(4096) }, "").landingPath, "/");
});

test("campaign helpers are pure and require no browser, storage or analytics service", () => {
  const frozen = Object.freeze({ ...location, search: "?apps=pda&utm_source=WeChat&utm_campaign=%E6%96%B0%E5%BA%97" });
  const previous = Object.freeze({ landingPath: "/#/producto/inicio", utmMedium: "email" });
  assert.deepEqual(parseRequestedApplications(frozen.search), ["pda"]);
  assert.deepEqual(createCampaignAttribution(frozen, "https://source.example/", previous), { landingPath: "/#/producto/inicio", referrer: "https://source.example/", utmSource: "WeChat", utmMedium: "email", utmCampaign: "新店" });
  assert.deepEqual(previous, { landingPath: "/#/producto/inicio", utmMedium: "email" });
});
