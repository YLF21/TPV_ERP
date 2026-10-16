export class RequestDeadlineError extends Error {
  constructor() { super("Request deadline exceeded"); this.name = "RequestDeadlineError"; }
}

// The operation includes consuming the response, not just receiving its headers.
export async function withinRequestDeadline(operation, timeoutMs = 20_000, externalSignal) {
  if (externalSignal?.aborted) throw externalSignal.reason ?? new DOMException("Aborted", "AbortError");
  const controller = new AbortController();
  let timer;
  let abortFromExternal;
  const cancellation = new Promise((_, reject) => {
    abortFromExternal = () => {
      controller.abort(externalSignal?.reason);
      reject(externalSignal?.reason ?? new DOMException("Aborted", "AbortError"));
    };
    if (externalSignal?.aborted) abortFromExternal();
    else externalSignal?.addEventListener("abort", abortFromExternal, { once: true });
    timer = setTimeout(() => {
      reject(new RequestDeadlineError());
      controller.abort();
    }, timeoutMs);
  });
  try {
    return await Promise.race([Promise.resolve().then(() => operation(controller.signal)), cancellation]);
  } finally {
    clearTimeout(timer);
    externalSignal?.removeEventListener("abort", abortFromExternal);
  }
}
