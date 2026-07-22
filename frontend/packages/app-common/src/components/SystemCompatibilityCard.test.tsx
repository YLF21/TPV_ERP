// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SystemCompatibilityCard } from "./SystemCompatibilityCard";

afterEach(() => vi.unstubAllGlobals());

describe("SystemCompatibilityCard", () => {
  it("shows frontend and backend versions after negotiating capabilities", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      backendVersion: "2.4.0", apiVersion: "1", minimumFrontendVersion: "0.0.1",
      capabilities: ["PAYMENT_IDEMPOTENCY", "PAYMENT_RECOVERY", "PAYMENT_STATUS_QUERY", "PAYMENT_VOID",
        "PAYMENT_REFUND", "PAYMENT_RECONCILIATION", "CORRELATION_ID"], paymentStates: {}
    }), { status: 200 })));

    render(<SystemCompatibilityCard locale="es" token="token" />);

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Compatible"));
    expect(screen.getByText("2.4.0")).toBeVisible();
    expect(screen.getByText("0.0.1")).toBeVisible();
  });
});
