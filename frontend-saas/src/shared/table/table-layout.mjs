// Same layout invariants as APP VENTA's tableLayoutPreferences, scoped to the
// independently deployed SaaS frontend (no calls to the local ERP API).
const minimumWidth = 56;
const maximumWidth = 800;
export function columnWidth(value, fallback = minimumWidth, minimum = minimumWidth) {
  const min = Number.isFinite(minimum) ? Math.min(maximumWidth, Math.max(32, Math.round(minimum))) : minimumWidth;
  const candidate = Number.isFinite(value) ? value : Number.isFinite(fallback) ? fallback : min;
  return Math.max(min, Math.min(maximumWidth, Math.round(candidate)));
}
export function normalizeTableLayout(saved, definitions) {
  const known = new Map(definitions.map(column => [column.key, column]));
  const seen = new Set(); const result = [];
  for (const candidate of [...(Array.isArray(saved) ? saved : []), ...definitions]) {
    if (!candidate || typeof candidate.key !== "string" || seen.has(candidate.key) || !known.has(candidate.key)) continue;
    seen.add(candidate.key); const definition = known.get(candidate.key);
    result.push({ key: candidate.key, width: columnWidth(candidate.width, definition.defaultWidth, definition.minWidth),
      visible: typeof candidate.visible === "boolean" ? candidate.visible : definition.defaultVisible !== false });
  }
  if (result.length && !result.some(column => column.visible)) result[0] = { ...result[0], visible: true };
  return result;
}
export function reorderTableColumns(layout, fromKey, toKey) {
  const from = layout.findIndex(column => column.key === fromKey); const to = layout.findIndex(column => column.key === toKey);
  if (from < 0 || to < 0 || from === to) return layout;
  const result = [...layout]; result.splice(to, 0, result.splice(from, 1)[0]); return result;
}
export function moveTableColumn(layout, key, direction) {
  const visible = layout.filter(column => column.visible); const index = visible.findIndex(column => column.key === key);
  const target = visible[index + direction];
  return target ? reorderTableColumns(layout, key, target.key) : layout;
}
export function toggleTableColumn(layout, key) {
  const column = layout.find(candidate => candidate.key === key);
  if (!column || (column.visible && layout.filter(candidate => candidate.visible).length === 1)) return layout;
  return layout.map(candidate => candidate.key === key ? { ...candidate, visible: !candidate.visible } : candidate);
}
export function tableLayoutKey(username, tableKey) {
  return `tpv-saas:user:${encodeURIComponent(username.trim().toLowerCase())}:table:${encodeURIComponent(tableKey)}:layout:v1`;
}
