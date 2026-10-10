export function validStoreInternalCode(value: unknown): string | null {
  return typeof value === "string" && /^(0[1-9]|[1-4][0-9]|5[0-2])[0-9]{5}$/.test(value)
    && !value.endsWith("00000") ? value : null;
}
