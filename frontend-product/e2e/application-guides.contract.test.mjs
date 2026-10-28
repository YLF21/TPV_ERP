import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import { getApplicationGuides, appGuideOrder } from "../src/applicationGuides.ts";
import { getConnectionContent } from "../src/connectionContent.ts";

const languages = ["es", "en", "zh"];
const sections = ["overview", "workflow", "setup", "questions"];
function strings(value) {
  if (typeof value === "string") return [value];
  return Object.values(value).flatMap(strings);
}
function shape(value) {
  if (typeof value === "string") return "string";
  if (Array.isArray(value)) return value.map(shape);
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, shape(item)]));
}

test("each language explains all four applications with matching content structure", () => {
  assert.deepEqual(appGuideOrder, ["venta", "gestion", "pda", "saas"]);
  const reference = shape(getApplicationGuides("es"));
  for (const language of languages) {
    const copy = getApplicationGuides(language);
    const connection = getConnectionContent(language);
    assert.deepEqual(shape(copy), reference, `${language} must not omit a translated field`);
    assert.deepEqual(Object.keys(copy.sections), sections);
    assert.deepEqual(Object.keys(copy.apps), appGuideOrder);
    assert.equal(new Set(Object.values(copy.sections)).size, 4);
    assert.equal(copy.gettingStarted.steps.length, 3);
    for (const app of appGuideOrder) {
      const guide = copy.apps[app];
      assert.equal(guide.tasks.length, 3, `${language}/${app} concrete tasks`);
      assert.equal(guide.workflow.length, 3, `${language}/${app} illustrative steps`);
      assert.equal(guide.requirements.length, 3, `${language}/${app} setup conditions`);
      assert.equal(guide.questions.length, 2, `${language}/${app} specific questions`);
      // Chinese expresses the same constraints in fewer characters; semantics are checked below.
      assert.ok(connection.apps[app].length >= (language === "zh" ? 20 : 30), `${language}/${app} visible canonical connection constraints`);
      assert.equal(Object.hasOwn(guide, "connection"), false, "Do not ship a second, unused connection paragraph");
      assert.equal(new Set(guide.tasks.map(item => item.title)).size, 3);
      assert.equal(new Set(guide.questions.map(item => item.question)).size, 2);
    }
    for (const text of [...strings(copy), ...strings(connection)]) {
      assert.ok(text.trim(), `${language} must not contain blank placeholders`);
      assert.doesNotMatch(text, /\bTPV[\s_-]*ERP\b|\b(?:esPos|EsPos|Espos|ESPOS)\b/);
      assert.doesNotMatch(text, /\bTODO\b/);
      assert.doesNotMatch(text, /https?:\/\/example\.|Lorem ipsum|24\/7|100%|certificado por la AEAT/i);
    }
  }
});

test("the expanded guides stay distinct rather than duplicating generic pitches", () => {
  for (const language of languages) {
    const guides = Object.values(getApplicationGuides(language).apps);
    for (const field of ["outcome", "audience", "worksWith"]) {
      assert.equal(new Set(guides.map(guide => guide[field])).size, 4, `${language}/${field}`);
    }
    assert.equal(new Set(Object.values(getConnectionContent(language).apps)).size, 4, `${language}/canonical connection`);
    assert.equal(new Set(guides.flatMap(guide => guide.questions.map(item => item.question))).size, 8);
  }
});

test("the integration keeps application-specific demo selection and the existing tour", async () => {
  const page = await readFile(new URL("../src/MarketingPage.tsx", import.meta.url), "utf8");
  assert.match(page, /<ApplicationExplorer language=\{language\}/);
  assert.match(page, /onRequestDemo=\{\(app\) => requestApplications\(\[app\]\)\}/);
  assert.match(page, /marketingContactSearch\(apps\)/);
  assert.match(page, /<MarketingDialogLoader kind="tour"/);
  assert.match(page, /initialApps=\{preferredApps\}/);
});

test("the visible explorer uses the single canonical connection source", async () => {
  const explorer = await readFile(new URL("../src/ApplicationExplorer.tsx", import.meta.url), "utf8");
  assert.match(explorer, /const connectionCopy = getConnectionContent\(language\)/);
  assert.match(explorer, /\{connectionCopy\.appLabel\}/);
  assert.match(explorer, /\{connectionCopy\.apps\[application\.id\]\}/);
  assert.doesNotMatch(explorer, /guide\.connection|copy\.connectionLabel/);
  const boundaries = {
    es: { service: /servicio de tienda/, blocked: /no puede vender/, network: /red/, device: /autorizado/, received: /datos recibidos/, stale: /desconectada/, noEditing: /No ofrece edición remota completa/ },
    en: { service: /store service/, blocked: /blocked without it/, network: /network/, device: /authorised/, received: /data received/, stale: /disconnected/, noEditing: /does not offer full remote editing/i },
    zh: { service: /门店服务/, blocked: /不可用时无法销售/, network: /网络/, device: /授权/, received: /收到的数据/, stale: /断网/, noEditing: /不提供完整的远程编辑功能/ }
  };
  for (const language of languages) {
    const copy = getConnectionContent(language);
    const expected = boundaries[language];
    assert.match(copy.apps.venta, expected.service);
    assert.match(copy.apps.venta, expected.blocked);
    assert.match(copy.apps.pda, expected.network);
    assert.match(copy.apps.pda, expected.device);
    assert.match(copy.apps.saas, expected.received);
    assert.match(copy.apps.saas, expected.stale);
    assert.match(copy.apps.saas, expected.noEditing);
  }
});
