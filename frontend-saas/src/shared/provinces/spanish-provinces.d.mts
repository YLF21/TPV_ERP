export type SpanishProvince = Readonly<{ code: string; name: string; ineName: string }>;
export const SPANISH_PROVINCES: readonly SpanishProvince[];
export function findSpanishProvince(value: unknown): SpanishProvince | null;
export function provinceSelection(value: string): { value: string; historicalValue: string | null };
