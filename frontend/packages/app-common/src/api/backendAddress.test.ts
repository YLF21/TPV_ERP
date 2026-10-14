import { afterEach, describe, expect, it, vi } from "vitest";
import { loadBackendAddress } from "./backendAddress";

describe("loadBackendAddress", () => {
  afterEach(() => vi.restoreAllMocks());

  it.each(["LOCAL", "192.0.2.25", "2001:db8::25"])("shows the resolved backend address %s", async backendLabel => {
    const request = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ backendLabel })));
    expect(await loadBackendAddress()).toBe(backendLabel);
    expect(request).toHaveBeenCalledWith("/__tpv/backend-address", expect.objectContaining({ cache: "no-store" }));
  });

  it("keeps an unavailable address unknown without substituting the renderer's address", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 503 }));
    expect(await loadBackendAddress()).toBeNull();
  });

  it("ignores an address response after the caller cancels", async () => {
    const cancellation = new AbortController();
    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      cancellation.abort();
      return new Response(JSON.stringify({ backendLabel: "LOCAL" }));
    });
    expect(await loadBackendAddress(cancellation.signal)).toBeNull();
  });
});
