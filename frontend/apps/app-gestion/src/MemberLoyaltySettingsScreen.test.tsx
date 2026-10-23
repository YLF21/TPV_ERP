/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTranslator, type UserSession } from "@tpverp/app-common";
import { MemberLoyaltySettingsScreen } from "./MemberLoyaltySettingsScreen";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  accessToken: "token",
  permissions: ["ADMIN"],
};

const initialSettings = {
  balanceAccrualEnabled: true,
  balanceAccrualBaseAmount: 1,
  balanceAccrualPercent: 10,
  balanceExpirationPolicy: "NO_CADUCA",
  pointsAccrualEnabled: true,
  pointsAccrualBaseAmount: 1,
  pointsPerEuro: 1,
  categoryAutoEnabled: true,
  memberWelcomeEnabled: false,
  memberCardCodeFormat: "QR",
  welcomeSubjectTemplate: null,
  welcomeBodyTemplate: null,
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("MemberLoyaltySettingsScreen", () => {
  it("moves through channel rows without opening the editor", async () => {
    const fetch = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith("/commercial-contact-channels")
      ? response(Array.from({ length: 4 }, (_, index) => ({ id: `channel-${index}`, code: `C${index}`, name: `Canal ${index}`, active: true })))
      : response(initialSettings));
    vi.stubGlobal("fetch", fetch);
    render(<MemberLoyaltySettingsScreen session={session} t={createTranslator("es")} />);
    await screen.findByText("Canal 0");
    const rows = Array.from(document.querySelectorAll<HTMLElement>(".member-settings-channel-table > [role='row'][tabindex]"));
    expect(document.activeElement).toBe(rows[0]);
    vi.useFakeTimers();
    try {
      fireEvent.keyDown(rows[0], { key: "ArrowDown" });
      act(() => { vi.advanceTimersByTime(250); });
      expect(document.activeElement).toBe(rows[3]);
      fireEvent.keyUp(rows[3], { key: "ArrowDown" });
      act(() => { vi.advanceTimersByTime(500); });
      expect(document.activeElement).toBe(rows[3]);
      expect(screen.queryByRole("button", { name: "Actualizar canal" })).not.toBeInTheDocument();
      expect(fetch).toHaveBeenCalledTimes(2);
    } finally { vi.useRealTimers(); }
  });
  it("saves both proportional rules and remains editable after the response", async () => {
    const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith("/commercial-contact-channels")) {
        return response([]);
      }
      if (init?.method === "PUT") {
        return response(JSON.parse(String(init.body)));
      }
      return response(initialSettings);
    });
    vi.stubGlobal("fetch", fetch);

    render(<MemberLoyaltySettingsScreen session={session} t={createTranslator("es")} />);

    const example = await screen.findByRole("spinbutton", { name: "Ejemplo sobre un cobro de" });
    const pointsBase = screen.getByRole("spinbutton", { name: "Importe base para puntos" });
    const pointsReward = screen.getByRole("spinbutton", { name: "Puntos generados" });
    fireEvent.change(example, { target: { value: "15" } });
    fireEvent.change(pointsBase, { target: { value: "10" } });
    fireEvent.change(pointsReward, { target: { value: "3" } });

    expect(screen.getByText("4 puntos")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));

    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));
    const savedBody = JSON.parse(String(fetch.mock.calls[2][1]?.body));
    expect(savedBody).toMatchObject({
      pointsAccrualBaseAmount: 10,
      pointsPerEuro: 3,
      balanceAccrualBaseAmount: 1,
      balanceAccrualPercent: 10,
    });
    expect(await screen.findByText("Configuración guardada correctamente.")).toBeInTheDocument();

    fireEvent.change(pointsReward, { target: { value: "4" } });
    expect(pointsReward).toBeEnabled();
    expect(pointsReward).toHaveValue(4);
    expect(screen.getByRole("button", { name: "Guardar" })).toBeEnabled();
  });
});

function response(body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  }));
}
