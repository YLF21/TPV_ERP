import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiConnectionError, ApiError, apiProblemCode, apiRequest, checkBackendConnection, classifyApiFailure
} from "./client";

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
      headers: {
        "Content-Type": "application/json",
        "X-Request-ID": expect.any(String)
      },
      body: JSON.stringify({ userName: "admin" })
    }));
  });

  it("sends FormData unchanged without forcing a multipart content type", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 204
    });
    vi.stubGlobal("fetch", fetchMock);
    const body = new FormData();
    body.append("password", "secret");
    body.append("certificate", new Blob(["pkcs12"]), "certificate.p12");

    await apiRequest("/verifactu/certificates", {
      token: "access-token",
      body
    });

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/verifactu/certificates", {
      method: "POST",
      headers: {
        Authorization: "Bearer access-token",
        "X-Request-ID": expect.any(String)
      },
      body
    });
  });

  it("accepts a successful response with an empty body", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 200 })));

    await expect(apiRequest("/pos/payment-sessions/active")).resolves.toBeUndefined();
  });

  it("forwards an abort signal to fetch", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    const controller = new AbortController();
    vi.stubGlobal("fetch", fetchMock);

    await apiRequest("/tickets/previous-current-terminal/import-preview", {
      signal: controller.signal,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/tickets/previous-current-terminal/import-preview",
      expect.objectContaining({ signal: controller.signal }),
    );
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

  it("classifies structured API failures centrally", () => {
    const disabled = new ApiError("disabled", 403, { code: "TERMINAL_DISABLED" });

    expect(apiProblemCode(disabled)).toBe("TERMINAL_DISABLED");
    expect(classifyApiFailure(disabled)).toBe("terminal-disabled");
    expect(classifyApiFailure(new ApiError("expired", 401))).toBe("authentication");
    expect(classifyApiFailure(new ApiConnectionError())).toBe("offline");
    expect(classifyApiFailure(new ApiError("server", 503))).toBe("server");
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
