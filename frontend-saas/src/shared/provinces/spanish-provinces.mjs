// INE province codes and official names, checked 2026-09-21.
// https://www.ine.es/daco/daco42/codmun/cod_provincia.htm
const displayNames = { "07": "Illes Balears", "15": "A Coruña", "26": "La Rioja", "35": "Las Palmas" };
export const SPANISH_PROVINCES = Object.freeze([
  ["02", "Albacete"], ["03", "Alicante/Alacant"], ["04", "Almería"], ["01", "Araba/Álava"],
  ["33", "Asturias"], ["05", "Ávila"], ["06", "Badajoz"], ["07", "Balears, Illes"],
  ["08", "Barcelona"], ["48", "Bizkaia"], ["09", "Burgos"], ["10", "Cáceres"],
  ["11", "Cádiz"], ["39", "Cantabria"], ["12", "Castellón/Castelló"], ["13", "Ciudad Real"],
  ["14", "Córdoba"], ["15", "Coruña, A"], ["16", "Cuenca"], ["20", "Gipuzkoa"],
  ["17", "Girona"], ["18", "Granada"], ["19", "Guadalajara"], ["21", "Huelva"],
  ["22", "Huesca"], ["23", "Jaén"], ["24", "León"], ["25", "Lleida"],
  ["27", "Lugo"], ["28", "Madrid"], ["29", "Málaga"], ["30", "Murcia"],
  ["31", "Navarra"], ["32", "Ourense"], ["34", "Palencia"], ["35", "Palmas, Las"],
  ["36", "Pontevedra"], ["26", "Rioja, La"], ["37", "Salamanca"], ["38", "Santa Cruz de Tenerife"],
  ["40", "Segovia"], ["41", "Sevilla"], ["42", "Soria"], ["43", "Tarragona"],
  ["44", "Teruel"], ["45", "Toledo"], ["46", "Valencia/València"], ["47", "Valladolid"],
  ["49", "Zamora"], ["50", "Zaragoza"], ["51", "Ceuta"], ["52", "Melilla"],
].map(([code, ineName]) => Object.freeze({ code, name: displayNames[code] ?? ineName, ineName }))
  .sort((a, b) => a.name.localeCompare(b.name, "es-ES")));

const aliases = {
  "01": ["Álava", "Araba", "Álava/Araba"],
  "03": ["Alicante", "Alacant", "Alacant/Alicante"],
  "07": ["Illes Balears", "Islas Baleares", "Baleares"],
  "12": ["Castellón", "Castelló", "Castelló/Castellón"],
  "15": ["A Coruña", "La Coruña", "Coruña"],
  "17": ["Gerona"],
  "20": ["Guipúzcoa"],
  "25": ["Lérida"],
  "26": ["La Rioja", "Rioja"],
  "32": ["Orense"],
  "35": ["Las Palmas"],
  "46": ["Valencia", "València", "València/Valencia"],
  "48": ["Vizcaya"],
};

function lookupKey(value) {
  return value.normalize("NFD").replace(/\p{M}/gu, "").trim().toLocaleLowerCase("es-ES").replace(/\s+/g, " ");
}

const byName = new Map();
for (const province of SPANISH_PROVINCES) {
  for (const name of [province.name, province.ineName, ...(aliases[province.code] ?? [])]) byName.set(lookupKey(name), province);
}

/** Display-only lookup: unknown values are never guessed or rewritten. */
export function findSpanishProvince(value) {
  return typeof value === "string" ? byName.get(lookupKey(value)) ?? null : null;
}

/** The original value stays in the caller's address until an explicit selection. */
export function provinceSelection(value) {
  const province = findSpanishProvince(value);
  return { value: province?.name ?? value, historicalValue: !province && value !== "" ? value : null };
}
