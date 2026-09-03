// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SafeRetirementDialog,
  type RetirementResult,
  retirementCommandPath,
  retirementImpactPath,
  retirementReasonMessageKey
} from "./SafeRetirementDialog";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? "OK" : "Error",
    headers: { get: () => null },
    text: async () => JSON.stringify(body)
  };
}

describe("SafeRetirementDialog", () => {
  it("uses the management impact and retirement endpoints", () => {
    expect(retirementImpactPath("products", "a/b")).toBe("/products/management/a%2Fb/retirement-impact");
    expect(retirementCommandPath("customers", "customer-1")).toBe("/customers/management/customer-1/retire");
  });

  it("falls back to a localized generic reason for an unknown backend code", () => {
    expect(retirementReasonMessageKey("FUTURE_REFERENCE")).toBe("safeManagement.retirement.reason.HAS_REFERENCES");
  });

  it("checks impact and confirms with the server version", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        id: "product-1",
        version: 7,
        currentState: "ACTIVE",
        outcomeIfConfirmed: "DEACTIVATED",
        reasonCodes: ["HAS_DOCUMENTS"],
        executable: true
      }))
      .mockResolvedValueOnce(jsonResponse({
        id: "product-1",
        outcome: "DEACTIVATED",
        reasonCodes: ["HAS_DOCUMENTS"]
      }));
    vi.stubGlobal("fetch", fetchMock);
    const onRetired = vi.fn();

    render(<SafeRetirementDialog
      open
      entityPath="products"
      entityId="product-1"
      entityLabel="0001 · Producto"
      locale="es"
      token="token"
      onClose={vi.fn()}
      onRetired={onRetired}
    />);

    expect(await screen.findByText("Se desactivará y conservará")).toBeInTheDocument();
    expect(screen.getByText("Existen documentos asociados.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Confirmar retirada" }));

    await waitFor(() => expect(onRetired).toHaveBeenCalledWith({
      id: "product-1",
      outcome: "DEACTIVATED",
      reasonCodes: ["HAS_DOCUMENTS"]
    }));
    expect(fetchMock).toHaveBeenNthCalledWith(2, expect.stringContaining("/products/management/product-1/retire"), expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ expectedVersion: 7 })
    }));
  });

  it("does not allow confirmation when the backend marks a system record as protected", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({
      id: "system-product",
      version: 1,
      currentState: "ACTIVE",
      outcomeIfConfirmed: "DEACTIVATED",
      reasonCodes: ["PROTECTED_SYSTEM_PRODUCT"],
      executable: false
    })));

    render(<SafeRetirementDialog
      open
      entityPath="products"
      entityId="system-product"
      entityLabel="0 · Producto de sistema"
      locale="es"
      token="token"
      onClose={vi.fn()}
      onRetired={vi.fn()}
    />);

    expect(await screen.findByText("El producto de sistema con código 0 está protegido.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirmar retirada" })).toBeDisabled();
  });

  it("does not repeat the POST when the refresh callback fails and retries only the refresh", async () => {
    const result: RetirementResult = {
      id: "product-1",
      outcome: "DEACTIVATED",
      reasonCodes: ["HAS_DOCUMENTS"]
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        id: "product-1",
        version: 7,
        currentState: "ACTIVE",
        outcomeIfConfirmed: "DEACTIVATED",
        reasonCodes: ["HAS_DOCUMENTS"],
        executable: true
      }))
      .mockResolvedValueOnce(jsonResponse(result));
    vi.stubGlobal("fetch", fetchMock);
    const callbackResults: RetirementResult[] = [];
    let refreshAttempts = 0;

    function Harness() {
      const [open, setOpen] = useState(true);
      async function onRetired(callbackResult: RetirementResult) {
        callbackResults.push(callbackResult);
        refreshAttempts += 1;
        if (refreshAttempts === 1) throw new Error("refresh failed");
        setOpen(false);
      }
      return <SafeRetirementDialog
        open={open}
        entityPath="products"
        entityId="product-1"
        entityLabel="0001 · Producto"
        locale="es"
        token="token"
        onClose={() => setOpen(false)}
        onRetired={onRetired}
      />;
    }

    render(<Harness />);
    fireEvent.click(await screen.findByRole("button", { name: "Confirmar retirada" }));

    expect(await screen.findByRole("status")).toHaveTextContent("La retirada se ha completado correctamente.");
    expect(screen.getByRole("alert")).toHaveTextContent("no se pudo recargar la lista");
    expect(screen.getByRole("button", { name: "Reintentar recarga" })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);

    fireEvent.click(screen.getByRole("button", { name: "Reintentar recarga" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(callbackResults).toHaveLength(2);
    expect(callbackResults[1]).toEqual(callbackResults[0]);
  });
});
