import type { DemoRequestStatus } from "../../lib/types";
export const demoRequestStatuses: readonly DemoRequestStatus[];
export function demoRequestPagePath(filters?: { page?: number; size?: number; status?: DemoRequestStatus; query?: string }): string;
