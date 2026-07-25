// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CashPolicySettingsCard } from "./CashPolicySettingsCard";

afterEach(cleanup);

describe("CashPolicySettingsCard", () => {
  it("preserves the complete cash configuration when an admin changes the sales policy", async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({
        storeId: "store-1",
        discrepancyTolerance: 1.5,
        requireEntryBreakdown: true,
        requireWithdrawalBreakdown: false,
        requireClosingBreakdown: true,
        cashSessionRequired: false,
      })
      .mockResolvedValueOnce({
        storeId: "store-1",
        discrepancyTolerance: 1.5,
        requireEntryBreakdown: true,
        requireWithdrawalBreakdown: false,
        requireClosingBreakdown: true,
        cashSessionRequired: true,
      });

    render(<CashPolicySettingsCard locale="es" token="token" request={request} />);

    await userEvent.click(await screen.findByRole("radio", { name: /Sí, exigir apertura manual/ }));
    await userEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));

    expect(request).toHaveBeenNthCalledWith(2, "/cash/config", {
      token: "token",
      method: "PUT",
      body: {
        discrepancyTolerance: 1.5,
        requireEntryBreakdown: true,
        requireWithdrawalBreakdown: false,
        requireClosingBreakdown: true,
        cashSessionRequired: true,
      },
    });
    expect(await screen.findByText("Configuración guardada.")).toBeInTheDocument();
  });
});
