/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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
  it("saves both proportional rules and remains editable after the response", async () => {
    const fetch = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
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

    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
    const savedBody = JSON.parse(String(fetch.mock.calls[1][1]?.body));
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
