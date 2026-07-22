import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiConnectionError, ApiError, apiRequest, checkBackendConnection } from "./client";

describe("apiRequest", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("uses POST by default when a request body is provided", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 204
    });
    vi.stubGlobal("fetch", fetchMock);

    await apiRequest("/auth/login", { body: { userName: "admin" } });

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/auth/login", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ userName: "admin" })
    }));
  });

  it("accepts a successful response with an empty body", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 200 })));

    await expect(apiRequest("/pos/payment-sessions/active")).resolves.toBeUndefined();
  });

  it("keeps structured problem details on API errors", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: "PRODUCT_PRICE_RULE_CONFLICT",
      detail: "Conflicto",
      formIndexes: [0, 2]
    }), {
      status: 409,
      headers: { "Content-Type": "application/problem+json" }
    })));

    await expect(apiRequest("/product-price-rules/1/preview")).rejects.toMatchObject({
      message: "Conflicto",
      status: 409,
      problem: {
        code: "PRODUCT_PRICE_RULE_CONFLICT",
        formIndexes: [0, 2]
      }
    } satisfies Partial<ApiError>);
  });

  it("hides internal server details and includes the trace reference", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: "INTERNAL_ERROR",
      detail: "at org.springframework.service.Internal /api/v1/private",
      traceId: "trace-12345678"
    }), { status: 500, headers: { "Content-Type": "application/problem+json" } })));

    await expect(apiRequest("/pos/payment-sessions")).rejects.toMatchObject({
      message: "No se pudo completar la operación (Ref: trace-12345678)",
      status: 500,
      traceId: "trace-12345678"
    });
  });

  it("turns network failures into backend connection errors", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));

    await expect(apiRequest("/auth/login")).rejects.toBeInstanceOf(ApiConnectionError);
  });

  it("reports backend connection availability from a probe request", async () => {
    vi.stubGlobal("fetch", vi.fn()
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(new Response(null, { status: 405 })));

    await expect(checkBackendConnection()).resolves.toBe(false);
    await expect(checkBackendConnection()).resolves.toBe(false);
    await expect(checkBackendConnection()).resolves.toBe(true);
  });
});
