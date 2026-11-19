export type DatePeriod = { from: string; to: string };
export const periodPresets: readonly string[];
export function periodPreset(preset: string, now?: Date): DatePeriod | null;
export function matchingPeriod(from: string, to: string, now?: Date): string;
export function adjacentPeriod(from: string, to: string, direction: number): DatePeriod | null;
