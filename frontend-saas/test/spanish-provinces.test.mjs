import assert from "node:assert/strict";
import test from "node:test";
import { SPANISH_PROVINCES, findSpanishProvince, provinceSelection } from "../src/shared/provinces/spanish-provinces.mjs";

test("Spanish province catalogue contains every INE code including Segovia, Ceuta and Melilla", () => {
  const expectedCodes = Array.from({ length: 52 }, (_, index) => String(index + 1).padStart(2, "0"));
  assert.deepEqual(SPANISH_PROVINCES.map(province => province.code).sort(), expectedCodes);
  assert.equal(new Set(SPANISH_PROVINCES.map(province => province.name)).size, 52);
  for (const province of SPANISH_PROVINCES) {
    assert.equal(findSpanishProvince(province.ineName)?.code, province.code, `Official INE name must identify ${province.code}`);
  }
  assert.equal(findSpanishProvince("Segovia")?.code, "40");
  assert.equal(findSpanishProvince("Ceuta")?.code, "51");
  assert.equal(findSpanishProvince("Melilla")?.code, "52");
});

test("natural display names preserve their official INE equivalents", () => {
  for (const [official, natural, code] of [
    ["Coruña, A", "A Coruña", "15"], ["Palmas, Las", "Las Palmas", "35"],
    ["Rioja, La", "La Rioja", "26"], ["Balears, Illes", "Illes Balears", "07"],
  ]) {
    assert.deepEqual(findSpanishProvince(official), { code, name: natural, ineName: official });
    assert.equal(provinceSelection(official).value, natural);
    assert.equal(provinceSelection(official).historicalValue, null);
  }
  assert.deepEqual(SPANISH_PROVINCES.map(province => province.name), SPANISH_PROVINCES.map(province => province.name).sort((a, b) => a.localeCompare(b, "es-ES")));
});

test("only unambiguous historical language and spelling variants are recognized", () => {
  for (const [alias, code] of [
    ["Álava", "01"], ["araba", "01"], ["Álava/Araba", "01"], ["  ALAVA  ", "01"],
    ["Alicante", "03"], ["Alacant", "03"], ["Baleares", "07"], ["Islas Baleares", "07"],
    ["Castelló", "12"], ["Castellón", "12"], ["La Coruña", "15"], ["Gerona", "17"],
    ["Guipuzcoa", "20"], ["Lérida", "25"], ["Orense", "32"], ["Vizcaya", "48"],
    ["València", "46"], ["Santa   Cruz de Tenerife", "38"],
  ]) assert.equal(findSpanishProvince(alias)?.code, code, alias);
});

test("display normalization never changes stored addresses or guesses unknown historical values", () => {
  const original = Object.freeze({ provincia: "Palmas, Las", ciudad: "Arucas" });
  assert.equal(provinceSelection(original.provincia).value, "Las Palmas");
  assert.equal(original.provincia, "Palmas, Las");
  for (const value of ["Tenerife", "Las Palmas de Gran Canaria", "Bayern", "Provincia histórica", "  Nombre heredado  "]) {
    assert.equal(findSpanishProvince(value), null);
    assert.deepEqual(provinceSelection(value), { value, historicalValue: value });
  }
  assert.deepEqual(provinceSelection(""), { value: "", historicalValue: null });
  assert.equal(findSpanishProvince(null), null);
  assert.equal(findSpanishProvince(35), null);
});
