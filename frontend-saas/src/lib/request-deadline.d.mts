export class RequestDeadlineError extends Error {}
export function withinRequestDeadline<T>(operation: (signal: AbortSignal) => Promise<T>, timeoutMs?: number, externalSignal?: AbortSignal | null): Promise<T>;
