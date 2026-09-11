// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import { CentralCustomerReuse } from "./CentralCustomerReuse";
import type { UserSession } from "../types";

vi.mock("../api/client", async (original) => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
const session: UserSession = { username: "test", displayName: "Test", accessToken: "token", permissions: ["CUSTOMERS_WRITE"] };
const candidate = { customerId: "central-1", revision: 0, centralCode: "C-001", fiscalName: "Cliente central", documentType: "DNI", documentNumber: "12345678Z", active: true };
function props() { return { documentType: "DNI", documentNumber: "12345678Z", session, locale: "es" as const, disabled: false, onBusyChange: vi.fn(), onAdopted: vi.fn() }; }
beforeEach(() => { vi.mocked(apiRequest).mockReset().mockResolvedValue(candidate); });
afterEach(cleanup);

describe("CentralCustomerReuse", () => {
  it("requires explicit lookup and confirmation, then returns the linked local copy", async () => {
    const input = props(); render(<CentralCustomerReuse {...input} />);
    expect(apiRequest).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Buscar en SaaS" }));
    expect(await screen.findByText("C-001 · Cliente central")).toBeInTheDocument();
    expect(apiRequest).toHaveBeenCalledExactlyOnceWith("/customers/central-lookup", expect.objectContaining({ body: { documentType: "DNI", documentNumber: "12345678Z" } }));
    const customer = { id: "local-2", fiscalName: "Cliente central" };
    vi.mocked(apiRequest).mockResolvedValue({ customer, centralLinkStatus: "PENDING_SYNC" });
    fireEvent.click(screen.getByRole("button", { name: "Usar cliente existente" }));
    await waitFor(() => expect(input.onAdopted).toHaveBeenCalledExactlyOnceWith(customer));
    expect(apiRequest).toHaveBeenLastCalledWith("/customers/adopt-central", expect.objectContaining({ body: { customerId: "central-1", expectedRevision: 0, documentType: "DNI", documentNumber: "12345678Z" } }));
  });
  it("does not duplicate a pending click or accept a response after the identity changes", async () => {
    let finish!: (value: unknown) => void;
    vi.mocked(apiRequest).mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    const input = props(); const view = render(<CentralCustomerReuse {...input} />);
    const search = screen.getByRole("button", { name: "Buscar en SaaS" });
    fireEvent.click(search); fireEvent.click(search);
    expect(apiRequest).toHaveBeenCalledTimes(1);
    const signal = vi.mocked(apiRequest).mock.calls[0][1]!.signal!;
    view.rerender(<CentralCustomerReuse {...input} documentNumber="87654321X" />);
    expect(signal.aborted).toBe(true);
    await act(async () => finish(candidate));
    expect(screen.queryByText("C-001 · Cliente central")).not.toBeInTheDocument();
    expect(input.onAdopted).not.toHaveBeenCalled();
    expect(input.onBusyChange).toHaveBeenLastCalledWith(false);
  });
  it("keeps inactive customers read-only", async () => {
    vi.mocked(apiRequest).mockResolvedValue({ ...candidate, active: false });
    render(<CentralCustomerReuse {...props()} />);
    fireEvent.click(screen.getByRole("button", { name: "Buscar en SaaS" }));
    expect(await screen.findByRole("button", { name: "Usar cliente existente" })).toBeDisabled();
    expect(apiRequest).toHaveBeenCalledTimes(1);
  });
  it("shows not found without creating a customer or exposing server details", async () => {
    vi.mocked(apiRequest).mockRejectedValue(new ApiError("internal", 404, { code: "CUSTOMER_CENTRAL_NOT_FOUND" }));
    render(<CentralCustomerReuse {...props()} />);
    fireEvent.click(screen.getByRole("button", { name: "Buscar en SaaS" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("No se encontró");
    expect(screen.queryByRole("button", { name: "Usar cliente existente" })).not.toBeInTheDocument();
  });
  it("does not offer reuse without customer creation permission", () => {
    render(<CentralCustomerReuse {...props()} session={{ ...session, permissions: ["CUSTOMERS_READ"] }} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(apiRequest).not.toHaveBeenCalled();
  });
});
