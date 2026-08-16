/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTranslator, type UserSession } from "@tpverp/app-common";
import { VoucherSettingsScreen } from "./VoucherSettingsScreen";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  accessToken: "token",
  permissions: ["ADMIN"]
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("VoucherSettingsScreen", () => {
  it("configures future vouchers as non-expiring without changing the saved days", async () => {
    const fetch = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "PUT") {
        return response({ storeId: "store-1", expirationMode: "NEVER", validityDays: 365 });
      }
      return response({ storeId: "store-1", expirationMode: "DAYS", validityDays: 365 });
    });
    vi.stubGlobal("fetch", fetch);

    render(<VoucherSettingsScreen
      session={session}
      storeName="Tienda Centro"
      t={createTranslator("es")}
    />);

    fireEvent.click(await screen.findByRole("radio", { name: /Los vales no caducan/i }));
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));

    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
    expect(fetch).toHaveBeenLastCalledWith(
      expect.stringContaining("/api/v1/vouchers/configuration"),
      expect.objectContaining({
        method: "PUT",
        body: JSON.stringify({ expirationMode: "NEVER", validityDays: 365 })
      })
    );
    expect(await screen.findByText("Configuración de caducidad guardada.")).toBeInTheDocument();
  });
});

function response(body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  }));
}
