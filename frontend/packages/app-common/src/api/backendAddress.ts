/** The desktop/dev proxy resolves the backend address, rather than the renderer's URL. */
export async function loadBackendAddress(signal?: AbortSignal): Promise<string | null> {
  const controller = new AbortController();
  const cancel = () => controller.abort();
  signal?.addEventListener("abort", cancel, { once: true });
  if (signal?.aborted) controller.abort();
  const timeout = setTimeout(cancel, 8000);
  try {
    const response = await fetch("/__tpv/backend-address", {
      cache: "no-store",
      signal: controller.signal
    });
    if (!response.ok || controller.signal.aborted) return null;
    const result = await response.json() as { backendLabel?: unknown };
    const label = typeof result.backendLabel === "string" ? result.backendLabel.trim() : "";
    return !controller.signal.aborted && label && label.length <= 128 ? label : null;
  } catch {
    return null;
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener("abort", cancel);
  }
}
