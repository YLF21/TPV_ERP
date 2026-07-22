import { afterEach, describe, expect, it, vi } from "vitest";
import {
  getVerifactuPosQueue,
  getVerifactuPosStatus
} from "./verifactuPos";

describe("VeriFactu POS API", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("uses the authenticated terminal-scoped status endpoint", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      active: true,
      presentationStatus: "OPERATIVO",
      pendingCount: 0,
      sendingCount: 0,
      reviewRequiredCount: 0
    }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await getVerifactuPosStatus("session-token");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/verifactu/pos/status",
      expect.objectContaining({
        method: "GET",
        headers: expect.objectContaining({ Authorization: "Bearer session-token" })
      })
    );
  });

  it("caps the requested queue limit without accepting a scope identifier", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("[]", { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await getVerifactuPosQueue("session-token", 500);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/verifactu/pos/queue?limit=50",
      expect.any(Object)
    );
    expect(fetchMock.mock.calls[0]?.[0]).not.toContain("terminalId");
    expect(fetchMock.mock.calls[0]?.[0]).not.toContain("tiendaId");
  });
});
