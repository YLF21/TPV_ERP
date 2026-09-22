import assert from "node:assert/strict";
import test from "node:test";
import { readDictionary, readFrontendSources, readSources } from "../test-support/source-helpers.mjs";

const dictionaries = Promise.all(["es", "en", "zh"].map(readDictionary));

test("independent Spanish, English and Chinese dictionaries have identical keys", async () => {
  const [es, en, zh] = await dictionaries;
  const keys = (dictionary) => Object.keys(dictionary).sort();
  assert.ok(keys(es).length >= 500, "translation extraction must not silently read an empty module");
  assert.deepEqual(keys(en), keys(es));
  assert.deepEqual(keys(zh), keys(es));
  for (const dictionary of [es, en, zh]) {
    assert.ok(Object.values(dictionary).every((value) => value.trim().length > 0));
  }
});

test("every statically referenced translation exists in all three languages", async () => {
  const source = await readFrontendSources();
  const used = [...new Set([...source.matchAll(/\bt\("([\w.-]+)"\)/g)].map((match) => match[1]))];
  for (const dictionary of await dictionaries) {
    assert.deepEqual(used.filter((key) => !dictionary[key]), []);
  }
});

test("translations retain interpolation placeholders and Chinese is translated", async () => {
  const [es, en, zh] = await dictionaries;
  const placeholders = (value) => (value.match(/\{\w+\}/g) ?? []).sort();
  for (const key of Object.keys(es)) {
    assert.deepEqual(placeholders(en[key]), placeholders(es[key]), `English: ${key}`);
    assert.deepEqual(placeholders(zh[key]), placeholders(es[key]), `Chinese: ${key}`);
  }
  for (const key of ["passwordChangeHelp", "csvImported", "billing", "clientPortal", "erpMasters", "outboxRecovery", "serviceUnavailable"]) {
    assert.match(zh[key], /\p{Script=Han}/u, key);
    assert.notEqual(zh[key], en[key]);
  }
});

test("unknown translation keys keep a visible controlled fallback", async () => {
  const source = await readSources("i18n/index.tsx");
  assert.match(source, /language === "zh" \? TRANSLATIONS\.en\[key\]/);
  assert.match(source, /console\.warn\(/);
  assert.match(source, /reportedTranslationFallbacks\.has/);
});
