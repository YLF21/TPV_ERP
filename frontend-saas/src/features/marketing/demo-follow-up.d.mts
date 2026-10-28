import type { DemoRequest } from "../../lib/types";
export function toLocalActionDate(value: string | null | undefined): string;
export function actionDateToInstant(value: string): string | null;
export function isDemoActionOverdue(request: Pick<DemoRequest, "status" | "nextAction" | "nextActionAt">, now?: number): boolean;
