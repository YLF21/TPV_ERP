import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

function keys(block) {
  return new Set(Array.from(block.matchAll(/^\s{4}([A-Za-z0-9]+):/gm), (match) => match[1]));
}

test("English dictionary covers every Spanish translation key", async () => {
  const source = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");
  const es = source.slice(source.indexOf("  es: {"), source.indexOf("  en: {"));
  const en = source.slice(source.indexOf("  en: {"), source.indexOf("  zh: {"));
  const missing = [...keys(es)].filter((key) => !keys(en).has(key));
  assert.deepEqual(missing, []);
});

test("Chinese missing keys use a visible controlled English fallback", async () => {
  const source = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");
  assert.match(source, /language === "zh" \? TRANSLATIONS\.en\[key\]/);
  assert.match(source, /console\.warn\(/);
});
