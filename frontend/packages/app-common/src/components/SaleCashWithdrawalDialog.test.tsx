// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { cleanup } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SaleCashWithdrawalDialog } from "./SaleCashWithdrawalDialog";

afterEach(cleanup);

const movement = {
  id: "movement-1",
  terminalId: "terminal-1",
  sessionId: "session-1",
  type: "RETIRADA" as const,
  amount: 20,
  createdAt: "2026-07-30T12:00:00Z",
  userId: "seller-1",
  authorizerUserId: "manager-1",
  comment: "Ingreso en banco",
};

describe("SaleCashWithdrawalDialog", () => {
  it("asks a privileged operator only for their own password", async () => {
    const request = vi.fn().mockResolvedValue(movement);
    const onCompleted = vi.fn();
    render(
      <SaleCashWithdrawalDialog
        locale="es"
        terminalId="terminal-1"
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        token="token"
        currentUserCanAuthorize
        requireDenominationBreakdown={false}
        denominations={[50, 20, 10]}
        request={request}
        printReceipt={vi.fn().mockResolvedValue({ status: "PRINTED" })}
        onCancel={vi.fn()}
        onCompleted={onCompleted}
      />,
    );

    expect(screen.queryByLabelText("Usuario autorizador")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Importe"), { target: { value: "20" } });
    fireEvent.change(screen.getByLabelText("Motivo"), { target: { value: "Ingreso en banco" } });
    fireEvent.change(screen.getByLabelText("Tu contraseña"), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar retirada" }));

    await waitFor(() => expect(onCompleted).toHaveBeenCalledWith(movement));
    expect(request).toHaveBeenCalledWith("/cash/movements/withdrawal", {
      token: "token",
      method: "POST",
      body: {
        terminalId: "terminal-1",
        amount: 20,
        comment: "Ingreso en banco",
        denominations: [],
        withdrawal: true,
        authorizerPassword: "secret",
      },
    });
  });

  it("requires and sends delegated credentials for a seller", async () => {
    const request = vi.fn().mockResolvedValue(movement);
    render(
      <SaleCashWithdrawalDialog
        locale="es"
        terminalId="terminal-1"
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        token="token"
        currentUserCanAuthorize={false}
        requireDenominationBreakdown={false}
        denominations={[]}
        request={request}
        printReceipt={vi.fn().mockResolvedValue({ status: "PRINTED" })}
        onCancel={vi.fn()}
        onCompleted={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText("Importe"), { target: { value: "10,50" } });
    fireEvent.change(screen.getByLabelText("Motivo"), { target: { value: "Motivo manual" } });
    fireEvent.change(screen.getByLabelText("Usuario autorizador"), { target: { value: " manager " } });
    fireEvent.change(screen.getByLabelText("Contraseña del autorizador"), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar retirada" }));

    await waitFor(() => expect(request).toHaveBeenCalled());
    expect(request.mock.calls[0][1].body).toMatchObject({
      amount: 10.5,
      comment: "Motivo manual",
      authorizerUsername: "manager",
      authorizerPassword: "secret",
    });
  });

  it("calculates the amount from the mandatory denomination breakdown", async () => {
    const request = vi.fn().mockResolvedValue({ ...movement, amount: 21 });
    render(
      <SaleCashWithdrawalDialog
        locale="es"
        terminalId="terminal-1"
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        token="token"
        currentUserCanAuthorize
        requireDenominationBreakdown
        denominations={[20, 1]}
        request={request}
        printReceipt={vi.fn().mockResolvedValue({ status: "PRINTED" })}
        onCancel={vi.fn()}
        onCompleted={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText("Cantidad 20.00"), { target: { value: "1" } });
    fireEvent.change(screen.getByLabelText("Cantidad 1.00"), { target: { value: "1" } });
    expect(screen.getByLabelText("Importe")).toHaveValue("21,00");
    fireEvent.change(screen.getByLabelText("Motivo"), { target: { value: "Retirada de seguridad" } });
    fireEvent.change(screen.getByLabelText("Tu contraseña"), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar retirada" }));

    await waitFor(() => expect(request).toHaveBeenCalled());
    expect(request.mock.calls[0][1].body).toMatchObject({
      amount: 21,
      denominations: [
        { denomination: 20, quantity: 1 },
        { denomination: 1, quantity: 1 },
      ],
    });
  });

  it("does not submit without a reason", () => {
    const request = vi.fn();
    render(
      <SaleCashWithdrawalDialog
        locale="es"
        terminalId="terminal-1"
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        token="token"
        currentUserCanAuthorize
        requireDenominationBreakdown={false}
        denominations={[]}
        request={request}
        printReceipt={vi.fn().mockResolvedValue({ status: "PRINTED" })}
        onCancel={vi.fn()}
        onCompleted={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText("Importe"), { target: { value: "20" } });
    fireEvent.change(screen.getByLabelText("Tu contraseña"), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar retirada" }));

    expect(screen.getByRole("alert")).toHaveTextContent("El motivo del movimiento es obligatorio.");
    expect(request).not.toHaveBeenCalled();
  });

  it("registers a cash entry from the same F9 dialog without printing a withdrawal receipt", async () => {
    const entry = { ...movement, id: "entry-1", type: "ENTRADA" as const, comment: "Aporte de cambio" };
    const request = vi.fn().mockResolvedValue(entry);
    const printReceipt = vi.fn();
    const onCompleted = vi.fn();
    render(
      <SaleCashWithdrawalDialog
        locale="es"
        terminalId="terminal-1"
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        token="token"
        authorization={{ mode: "DIRECT", requireUsername: false, requirePassword: false }}
        requireEntryDenominationBreakdown={false}
        entryDenominations={[20, 10]}
        requireDenominationBreakdown={false}
        denominations={[]}
        request={request}
        printReceipt={printReceipt}
        onCancel={vi.fn()}
        onCompleted={onCompleted}
      />,
    );

    fireEvent.click(screen.getByLabelText("Entrada"));
    fireEvent.change(screen.getByLabelText("Importe"), { target: { value: "30" } });
    fireEvent.change(screen.getByLabelText("Motivo"), { target: { value: "Aporte de cambio" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar entrada" }));

    await waitFor(() => expect(onCompleted).toHaveBeenCalledWith(entry));
    expect(request).toHaveBeenCalledWith("/cash/movements/entry", {
      token: "token",
      method: "POST",
      body: {
        terminalId: "terminal-1",
        amount: 30,
        comment: "Aporte de cambio",
        denominations: [],
      },
    });
    expect(printReceipt).not.toHaveBeenCalled();
  });

  it("retries only the receipt print after the movement has been recorded", async () => {
    const request = vi.fn().mockResolvedValue(movement);
    const printReceipt = vi.fn()
      .mockResolvedValueOnce({ status: "FAILED" })
      .mockResolvedValueOnce({ status: "PRINTED" });
    const onCompleted = vi.fn();
    render(
      <SaleCashWithdrawalDialog
        locale="es"
        terminalId="terminal-1"
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        token="token"
        currentUserCanAuthorize
        requireDenominationBreakdown={false}
        denominations={[]}
        request={request}
        printReceipt={printReceipt}
        onCancel={vi.fn()}
        onCompleted={onCompleted}
      />,
    );

    fireEvent.change(screen.getByLabelText("Importe"), { target: { value: "20" } });
    fireEvent.change(screen.getByLabelText("Motivo"), { target: { value: "Ingreso en banco" } });
    fireEvent.change(screen.getByLabelText("Tu contraseña"), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar retirada" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "La retirada se registró, pero no se pudo imprimir el justificante.",
    );
    expect(request).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Reimprimir justificante" }));

    await waitFor(() => expect(onCompleted).toHaveBeenCalledWith(movement));
    expect(request).toHaveBeenCalledTimes(1);
    expect(printReceipt).toHaveBeenCalledTimes(2);
  });
});
