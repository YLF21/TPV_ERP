// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { cleanup, createEvent, fireEvent, render, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ManualCardReferenceDialog,
  PaymentAllocationPanel,
  hasLockedIntegratedPayment,
  manualCardDialogState,
} from "./PaymentAllocationPanel";
import type { PaymentSession } from "../sale/paymentOrchestration";

const session: PaymentSession = {
  id: "sale-1",
  totalCents: 1200,
  status: "COLLECTING",
  allocations: [
    {
      kind: "INTEGRATED_CARD",
      amountCents: 500,
      idempotencyKey: "op-1",
      operationId: "op-1",
      provider: "PAYCOMET",
      status: "APPROVED",
      authorization: "****1234",
      reference: "DOC-01",
      comment: "Pago principal",
    },
    {
      kind: "INTEGRATED_CARD",
      amountCents: 700,
      idempotencyKey: "op-2",
      operationId: "op-2",
      provider: "PAYTEF",
      status: "DECLINED",
      message: "Denegada",
    },
  ],
};

afterEach(cleanup);

describe("PaymentAllocationPanel", () => {
  it("uses Aceptar to submit the current entry in immediate-payment mode", () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [] }}
      providers={[]}
      manualCardEnabled
      acceptSubmitsCurrent
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);

    fireEvent.click(within(container).getByRole("button", { name: "ACEPTAR" }));

    expect(onAdd).toHaveBeenCalledWith({
      kind: "CASH",
      amountCents: 1200,
      deliveredCents: 1200,
      changeCents: 0,
    }, { finalizeWhenCovered: true });
  });

  it("accepts a partial amount and leaves finalization to the checkout owner", () => {
    const onAdd = vi.fn();
    const onAccept = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [] }}
      providers={[]}
      manualCardEnabled
      acceptSubmitsCurrent
      onAdd={onAdd}
      onAccept={onAccept}
      onQuery={vi.fn()}
    />);

    fireEvent.change(
      container.querySelector<HTMLInputElement>(".sale-checkout-entry > label input")!,
      { target: { value: "5,00" } },
    );
    fireEvent.click(within(container).getByRole("button", { name: "ACEPTAR" }));

    expect(onAdd).toHaveBeenCalledWith({
      kind: "CASH",
      amountCents: 500,
      deliveredCents: 500,
      changeCents: 0,
    }, { finalizeWhenCovered: true });
    expect(onAccept).not.toHaveBeenCalled();
  });

  it("allows the remaining payment after an approved integrated-card partial", () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [session.allocations[0]] }}
      providers={["PAYTEF"]}
      manualCardEnabled
      acceptSubmitsCurrent
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);

    const accept = within(container).getByRole("button", { name: "ACEPTAR" });
    expect((accept as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(accept);
    expect(onAdd).toHaveBeenCalledWith(expect.objectContaining({
      kind: "CASH",
      amountCents: 700,
    }), { finalizeWhenCovered: true });
    expect(within(container).getAllByRole("button", { name: "CANCELAR" })
      .every((button) => (button as HTMLButtonElement).disabled)).toBe(true);
  });

  it("offers cash, card and a new voucher for refunds without sale-only methods", () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{
        ...session,
        direction: "REFUND",
        allocations: [],
        refundPaymentAvailability: [
          { paymentMethod: "EFECTIVO", kind: "CASH", originalAmountCents: 4100, refundedAmountCents: 0, reservedAmountCents: 0, availableAmountCents: 4100 },
          { paymentMethod: "TARJETA", kind: "MANUAL_CARD", originalAmountCents: 4100, refundedAmountCents: 0, reservedAmountCents: 0, availableAmountCents: 4100 },
        ],
      }}
      providers={[]}
      manualCardEnabled
      vouchers={[]}
      initialMethod="VOUCHER"
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);

    expect(within(container).getByRole("heading", { name: "DEVOLUCIÓN" })).toBeTruthy();
    expect(within(container).getByRole("button", { name: /Efectivo/ })).toBeTruthy();
    expect(within(container).getByRole("button", { name: /Tarjeta/ })).toBeTruthy();
    expect(within(container).getByRole("button", { name: /Vale/ })).toBeTruthy();
    expect(within(container).getByRole("button", { name: /Efectivo.*41,00/ })).toBeTruthy();
    expect(within(container).getByRole("button", { name: /Tarjeta.*41,00/ })).toBeTruthy();
    expect(within(container).getByRole("button", { name: /Vale.*12,00/ })).toBeTruthy();
    expect(within(container).queryByRole("button", { name: /Pendiente/ })).toBeNull();
    expect(within(container).queryByRole("button", { name: /Transferencia/ })).toBeNull();
    expect(container.querySelector("#checkout-voucher-code")).toBeNull();

    const amountInput = container.querySelector<HTMLInputElement>(".sale-checkout-entry > label input")!;
    fireEvent.keyDown(amountInput, { key: "Enter" });
    expect(onAdd).toHaveBeenCalledWith(
      { kind: "VOUCHER", amountCents: 1200 },
      { finalizeWhenCovered: true },
    );
  });

  it("only offers a voucher when the refund comes directly from a gift receipt", async () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, direction: "REFUND", allocations: [] }}
      providers={["PAYCOMET"]}
      manualCardEnabled
      voucherOnlyRefund
      vouchers={[]}
      initialMethod="CASH"
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);

    expect(within(container).queryByRole("button", { name: /Efectivo/ })).toBeNull();
    expect(within(container).queryByRole("button", { name: /Tarjeta/ })).toBeNull();
    expect(within(container).getByRole("button", { name: /Vale/ })).toBeTruthy();
    expect(within(container).getByText("Este ticket regalo solo puede devolverse mediante un vale.")).toBeTruthy();

    const amountInput = container.querySelector<HTMLInputElement>(".sale-checkout-entry > label input")!;
    await waitFor(() => expect(amountInput.value).toBe("12,00"));
    fireEvent.keyDown(amountInput, { key: "Enter" });
    expect(onAdd).toHaveBeenCalledWith(
      { kind: "VOUCHER", amountCents: 1200 },
      { finalizeWhenCovered: true },
    );
  });

  it("shows that only the amount really paid can be returned as money after a gift exchange", () => {
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{
        ...session,
        totalCents: 8200,
        direction: "REFUND",
        allocations: [],
        refundPaymentAvailability: [
          { paymentMethod: "EFECTIVO", kind: "CASH", originalAmountCents: 1000, refundedAmountCents: 0, reservedAmountCents: 0, availableAmountCents: 1000 },
        ],
      }}
      providers={[]}
      manualCardEnabled
      vouchers={[]}
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);

    expect(within(container).getByRole("button", { name: /Efectivo.*10,00/ })).toBeTruthy();
    expect(within(container).getByRole("button", { name: /Vale.*82,00/ })).toBeTruthy();
    expect(within(container).getByText(/La parte que no procede de un pago real solo puede devolverse mediante un vale/)).toBeTruthy();
  });

  it("uses the original manual card route when an integrated provider is configured", () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{
        ...session,
        direction: "REFUND",
        allocations: [],
        refundPaymentAvailability: [
          { paymentMethod: "EFECTIVO", kind: "CASH", originalAmountCents: 4100, refundedAmountCents: 0, reservedAmountCents: 0, availableAmountCents: 4100 },
          { paymentMethod: "TARJETA", kind: "MANUAL_CARD", originalAmountCents: 4100, refundedAmountCents: 0, reservedAmountCents: 0, availableAmountCents: 4100 },
        ],
      }}
      providers={["PAYCOMET"]}
      manualCardEnabled
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);

    const cardButton = within(container).getByRole("button", { name: /Tarjeta.*41,00/ });
    fireEvent.click(cardButton);
    const amountInput = container.querySelector<HTMLInputElement>(".sale-checkout-entry > label input")!;
    fireEvent.keyDown(amountInput, { key: "Enter" });

    expect(onAdd).toHaveBeenCalledWith(
      { kind: "MANUAL_CARD", amountCents: 1200 },
      { finalizeWhenCovered: true },
    );
  });

  it("renders a zero-total checkout ready to accept without payment methods", () => {
    const html = renderToStaticMarkup(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, totalCents: 0, direction: "ZERO", status: "COVERED", allocations: [] }}
      providers={[]}
      manualCardEnabled
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);
    expect(html).toContain("COBRO");
    expect(html).not.toContain("IMPORTE / RECIBIDO");
    expect(html).not.toContain("<kbd>*</kbd>");
    expect(html).toMatch(/class="primary"[^>]*>ACEPTAR/);
  });

  it("selects the payment method before accepting the entered amount", () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [] }}
      providers={[]}
      manualCardEnabled
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);

    fireEvent.click(within(container).getByRole("button", { name: /Transferencia/ }));
    expect(onAdd).not.toHaveBeenCalled();

    const amountInput = container.querySelector<HTMLInputElement>(".sale-checkout-entry input");
    expect(amountInput).not.toBeNull();
    fireEvent.change(amountInput!, { target: { value: "8,00" } });
    fireEvent.keyDown(amountInput!, { key: "Enter" });

    expect(onAdd).toHaveBeenCalledWith({
      kind: "TRANSFER",
      amountCents: 800,
    }, { finalizeWhenCovered: true });
  });

  it("moves from card amount to required document and Enter submits the payment", async () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [] }}
      providers={[]}
      manualCardEnabled
      manualCardRequiresReference
      initialMethod="CARD"
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);
    const [amountInput, referenceInput] = Array.from(
      container.querySelectorAll<HTMLInputElement>(".sale-checkout-entry input"),
    );

    fireEvent.keyDown(amountInput!, { key: "Enter" });

    expect(onAdd).not.toHaveBeenCalled();
    await waitFor(() => expect(document.activeElement).toBe(referenceInput));

    fireEvent.change(referenceInput!, { target: { value: "DOC-42" } });
    fireEvent.keyDown(referenceInput!, { key: "Enter" });

    expect(onAdd).toHaveBeenCalledWith({
      kind: "MANUAL_CARD",
      amountCents: 1200,
      reference: "DOC-42",
    }, { finalizeWhenCovered: true });
    await waitFor(() => expect(document.activeElement).toBe(amountInput));
  });

  it("submits manual card without a document when the method does not require it", () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [] }}
      providers={[]}
      manualCardEnabled
      manualCardRequiresReference={false}
      initialMethod="CARD"
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);
    const amountInput = container.querySelector<HTMLInputElement>(".sale-checkout-entry > label input")!;

    fireEvent.keyDown(amountInput, { key: "Enter" });

    expect(onAdd).toHaveBeenCalledWith({
      kind: "MANUAL_CARD",
      amountCents: 1200,
    }, { finalizeWhenCovered: true });
  });

  it("resolves the voucher and captures its amount without user input", async () => {
    const onAdd = vi.fn();
    const onResolveVoucher = vi.fn().mockResolvedValue({
      code: "V-100",
      balance: "7.00",
      status: "ACTIVE",
    });
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [] }}
      providers={[]}
      manualCardEnabled
      vouchers={[{ code: "V-100", balance: 30 }]}
      initialMethod="VOUCHER"
      onResolveVoucher={onResolveVoucher}
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);
    const amountInput = container.querySelector<HTMLInputElement>(".sale-checkout-entry > label input")!;
    const voucherInput = container.querySelector<HTMLInputElement>("#checkout-voucher-code")!;
    expect(voucherInput.getAttribute("list")).toBeNull();
    expect(container.querySelector("#checkout-voucher-codes")).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(voucherInput));
    fireEvent.keyDown(amountInput, { key: "Enter" });
    await waitFor(() => expect(document.activeElement).toBe(voucherInput));
    fireEvent.change(voucherInput, { target: { value: "V-100" } });
    fireEvent.keyDown(voucherInput, { key: "Enter" });

    await waitFor(() => expect(onResolveVoucher).toHaveBeenCalledWith("V-100"));
    await waitFor(() => expect(onAdd).toHaveBeenCalledWith({
      kind: "VOUCHER",
      amountCents: 700,
      voucherCode: "V-100",
    }, { finalizeWhenCovered: true }));
    expect((amountInput as HTMLInputElement).disabled).toBe(true);
  });

  it("keeps the voucher code selected when the voucher is not active", async () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [] }}
      providers={[]}
      manualCardEnabled
      initialMethod="VOUCHER"
      onResolveVoucher={vi.fn().mockResolvedValue({
        code: "V-USED",
        balance: "0.00",
        status: "CONSUMED",
      })}
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);
    const voucherInput = container.querySelector<HTMLInputElement>("#checkout-voucher-code")!;
    fireEvent.change(voucherInput, { target: { value: "V-USED" } });
    fireEvent.keyDown(voucherInput, { key: "Enter" });

    expect((await within(container).findByRole("alert")).textContent).toContain("Este vale ya fue consumido");
    expect(onAdd).not.toHaveBeenCalled();
    await waitFor(() => expect(document.activeElement).toBe(voucherInput));
  });

  it("moves focus directly to the voucher code when F9 selects voucher", async () => {
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [] }}
      providers={[]}
      manualCardEnabled
      vouchers={[{ code: "V-100", balance: 30 }]}
      initialMethod="CASH"
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);
    const amountInput = container.querySelector<HTMLInputElement>(".sale-checkout-entry > label input")!;
    await waitFor(() => expect(document.activeElement).toBe(amountInput));

    fireEvent.keyDown(amountInput, { key: "F9" });

    const voucherInput = container.querySelector<HTMLInputElement>("#checkout-voucher-code")!;
    await waitFor(() => expect(document.activeElement).toBe(voucherInput));
    expect(voucherInput.getAttribute("list")).toBeNull();
  });

  it("does not offer company-disabled payment methods", () => {
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [] }}
      providers={[]}
      manualCardEnabled
      cashEnabled={false}
      cardEnabled={false}
      voucherEnabled={false}
      transferEnabled={false}
      vouchers={[{ code: "V-100", balance: 30 }]}
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);

    expect(within(container).queryByRole("button", { name: /Efectivo/ })).toBeNull();
    expect(within(container).queryByRole("button", { name: /Tarjeta/ })).toBeNull();
    expect(within(container).queryByRole("button", { name: /Vale/ })).toBeNull();
    expect(within(container).queryByRole("button", { name: /Transferencia/ })).toBeNull();
    expect(within(container).getByRole("button", { name: /Pendiente/ })).toBeTruthy();
  });

  it("consumes a fast scanner burst without changing or submitting the amount", () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [] }}
      providers={[]}
      manualCardEnabled
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);
    const amountInput = container.querySelector<HTMLInputElement>(".sale-checkout-entry input");

    let scanned = "";
    for (const [index, key] of Array.from("8412345678901").entries()) {
      const event = createEvent.keyDown(amountInput!, { key });
      Object.defineProperty(event, "timeStamp", { value: 100 + index * 20 });
      fireEvent(amountInput!, event);
      scanned += key;
      fireEvent.change(amountInput!, { target: { value: scanned } });
    }
    const enter = createEvent.keyDown(amountInput!, { key: "Enter" });
    Object.defineProperty(enter, "timeStamp", { value: 370 });
    fireEvent(amountInput!, enter);

    expect(onAdd).not.toHaveBeenCalled();
    expect(amountInput?.value).toBe("12,00");
    expect(within(container).getByRole("alert").textContent).toContain(
      "Código de barras ignorado durante el cobro",
    );
  });

  it("does not register a pending ticket without a selected customer", () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [] }}
      providers={[]}
      manualCardEnabled
      customerSelected={false}
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);

    fireEvent.click(within(container).getByRole("button", { name: /Pendiente/ }));
    const amountInput = container.querySelector<HTMLInputElement>(".sale-checkout-entry input");
    fireEvent.keyDown(amountInput!, { key: "Enter" });

    expect(onAdd).not.toHaveBeenCalled();
    expect(within(container).getByRole("alert").textContent).toContain("Selecciona un cliente");
  });

  it("renders the approved checkout layout, metadata columns and all methods", () => {
    const html = renderToStaticMarkup(<PaymentAllocationPanel
      locale="es"
      session={session}
      providers={["PAYTEF"]}
      manualCardEnabled
      vouchers={[{ code: "V-1", balance: 20 }]}
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);
    expect(html).toContain("COBRO");
    expect(html).toContain("Nº DOCUMENTO");
    expect(html).toContain("COMENTARIO");
    expect(html).toContain("DOC-01");
    expect(html).toContain("Pago principal");
    expect(html).toContain("TOTAL A COBRAR");
    expect(html).toContain("COBRADO");
    expect(html).toContain("FALTA");
    expect(html).toContain('<kbd aria-hidden="true">*</kbd>');
    expect(html).toContain('<kbd aria-hidden="true">F11</kbd>');
  });

  it("shows query for an uncertain integrated operation", () => {
    const timedOut = {
      ...session,
      allocations: [{ ...session.allocations[0], status: "TIMEOUT" as const }],
    };
    const html = renderToStaticMarkup(<PaymentAllocationPanel
      locale="es" session={timedOut} providers={["PAYTEF"]}
      manualCardEnabled={false} onAdd={vi.fn()} onQuery={vi.fn()}
    />);
    expect(html).toContain("Consultar estado");
  });

  it("shows a synthetic fixed discount row and disables further discount after payment", () => {
    const html = renderToStaticMarkup(<PaymentAllocationPanel
      locale="es" session={session} providers={[]} manualCardEnabled
      checkoutDiscountCents={200} onAdd={vi.fn()} onQuery={vi.fn()}
    />);
    expect(html).toContain("Descuento");
    expect(html).toContain("-2,00 €");
    expect(html).toMatch(/disabled=""><span>Descuento/);
  });

  it("uses contextual F10 to apply all or part of the available member balance", () => {
    const onAdd = vi.fn();
    const onMemberBalance = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled customerSelected memberBalanceAvailableCents={900}
      onMemberBalance={onMemberBalance} onAdd={onAdd} onQuery={vi.fn()}
    />);

    fireEvent.keyDown(window, { key: "F10" });
    const amount = within(container).getByRole("textbox", { name: /IMPORTE/ });
    expect(amount).toHaveValue("9,00");
    fireEvent.change(amount, { target: { value: "4,50" } });
    fireEvent.keyDown(amount, { key: "Enter" });

    expect(onMemberBalance).toHaveBeenCalledWith(450);
    expect(onAdd).not.toHaveBeenCalled();
  });

  it("shows member balance separately from the F11 discount", () => {
    const html = renderToStaticMarkup(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled customerSelected memberBalanceCents={300}
      memberBalanceAvailableCents={900} checkoutDiscountCents={200}
      onMemberBalance={vi.fn()} onAdd={vi.fn()} onQuery={vi.fn()}
    />);

    expect(html).toContain("Saldo socio");
    expect(html).toContain("-3,00 €");
    expect(html).toContain("-2,00 €");
    expect(html).toContain('<kbd aria-hidden="true">F10</kbd>');
  });

  it("renders the numeric keypad only in touch mode and hides shortcut labels", () => {
    const html = renderToStaticMarkup(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled interfaceMode="TOUCH" onAdd={vi.fn()} onQuery={vi.fn()}
    />);
    expect(html).toContain("Teclado numérico");
    expect(html).toContain("Exacto");
    expect(html).toContain("50 €");
    expect(html).not.toContain("<kbd>F11</kbd>");
  });

  it("disables pending when the operator lacks permission", () => {
    const html = renderToStaticMarkup(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled pendingEnabled={false} onAdd={vi.fn()} onQuery={vi.fn()}
    />);
    expect(html).toMatch(/disabled=""><span>Pendiente/);
  });

  it("blocks all method buttons when compensation is required", () => {
    const html = renderToStaticMarkup(<PaymentAllocationPanel
      locale="es" session={{ ...session, status: "COMPENSATION_REQUIRED" }}
      providers={["PAYTEF"]} manualCardEnabled onAdd={vi.fn()} onQuery={vi.fn()}
    />);
    expect(html).toContain("Compensación obligatoria");
    expect((html.match(/disabled=""/g) ?? []).length).toBeGreaterThanOrEqual(7);
  });

  it("recovers a legacy cash compensation with cash selected and cancellation available", () => {
    const legacyCashSession: PaymentSession = {
      ...session,
      status: "COMPENSATION_REQUIRED",
      allocations: [{
        kind: "CASH",
        amountCents: 1200,
        deliveredCents: 1200,
        changeCents: 0,
        idempotencyKey: "cash-legacy",
        status: "APPROVED",
      }],
    };
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={legacyCashSession} providers={["PAYTEF"]}
      manualCardEnabled initialMethod="CARD" allowAdd={false}
      onAdd={vi.fn()} onQuery={vi.fn()} onClear={vi.fn()} onClose={vi.fn()}
    />);

    expect(within(container).getByRole("button", { name: /Efectivo/ }).classList.contains("selected")).toBe(true);
    expect(within(container).getByRole("button", { name: /Tarjeta/ }).classList.contains("selected")).toBe(false);
    expect((within(container).getByRole("button", { name: /Eliminar pagos/ }) as HTMLButtonElement).disabled).toBe(false);
    expect(within(container).getAllByRole("button", { name: "CANCELAR" })
      .every((button) => !(button as HTMLButtonElement).disabled)).toBe(true);
  });

  it("prevents clearing or closing while an integrated card result is locked", () => {
    const lockedSession: PaymentSession = {
      ...session,
      status: "COMPENSATION_REQUIRED",
      allocations: [{ ...session.allocations[0], status: "TIMEOUT" }],
    };
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={lockedSession} providers={["PAYTEF"]}
      manualCardEnabled allowAdd={false}
      onAdd={vi.fn()} onQuery={vi.fn()} onClear={vi.fn()} onClose={vi.fn()}
    />);

    expect(hasLockedIntegratedPayment(lockedSession.allocations)).toBe(true);
    expect((within(container).getByRole("button", { name: /Eliminar pagos/ }) as HTMLButtonElement).disabled).toBe(true);
    expect(within(container).getAllByRole("button", { name: "CANCELAR" })
      .every((button) => (button as HTMLButtonElement).disabled)).toBe(true);
  });

  it("allows only the safe final accept retry after an approved integrated document payment", () => {
    const onAccept = vi.fn();
    const lockedCoveredSession: PaymentSession = {
      ...session,
      totalCents: 500,
      status: "COVERED",
      allocations: [session.allocations[0]],
    };
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={lockedCoveredSession} providers={["PAYTEF"]}
      manualCardEnabled allowAdd={false} acceptSubmitsCurrent
      acceptWithLockedIntegratedPayment
      onAdd={vi.fn()} onQuery={vi.fn()} onClear={vi.fn()} onClose={vi.fn()}
      onAccept={onAccept}
    />);

    expect((within(container).getByRole("button", { name: /Eliminar pagos/ }) as HTMLButtonElement).disabled).toBe(true);
    expect(within(container).getAllByRole("button", { name: "CANCELAR" })
      .every((button) => (button as HTMLButtonElement).disabled)).toBe(true);
    const accept = within(container).getByRole("button", { name: "ACEPTAR" });
    expect((accept as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(accept);
    expect(onAccept).toHaveBeenCalledOnce();
  });

  it("allows an explicit idempotent retry while payment entry remains locked", () => {
    const onAccept = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled allowAdd={false} acceptOpenSession
      acceptLabel="REINTENTAR CONFIRMACIÓN"
      onAdd={vi.fn()} onQuery={vi.fn()} onAccept={onAccept}
    />);

    expect((within(container).getByRole("button", {
      name: "Efectivo",
    }) as HTMLButtonElement).disabled).toBe(true);
    const retry = within(container).getByRole("button", { name: "REINTENTAR CONFIRMACIÓN" });
    expect((retry as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(retry);
    expect(onAccept).toHaveBeenCalledOnce();
  });

  it("can keep cancellation locked while allowing a rejected card attempt to be cleared", () => {
    const rejectedSession: PaymentSession = {
      ...session,
      status: "COLLECTING",
      allocations: [{ ...session.allocations[0], status: "DECLINED" }],
    };
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={rejectedSession} providers={["PAYTEF"]}
      manualCardEnabled allowAdd={false} closeDisabled
      onAdd={vi.fn()} onQuery={vi.fn()} onClear={vi.fn()} onClose={vi.fn()}
    />);

    expect(hasLockedIntegratedPayment(rejectedSession.allocations)).toBe(false);
    expect((within(container).getByRole("button", { name: /Eliminar pagos/ }) as HTMLButtonElement).disabled).toBe(false);
    expect(within(container).getAllByRole("button", { name: "CANCELAR" })
      .every((button) => (button as HTMLButtonElement).disabled)).toBe(true);
  });

  it("keeps the legacy manual-card dialog accessible and ephemeral", () => {
    const html = renderToStaticMarkup(<ManualCardReferenceDialog
      locale="es" reference="" onReferenceChange={vi.fn()}
      onCancel={vi.fn()} onConfirm={vi.fn()}
    />);
    expect(html).toContain('role="dialog"');
    expect(html).toContain('aria-modal="true"');
    expect(html).toContain("Referencia obligatoria");
    expect(manualCardDialogState(
      { open: true, reference: "REF-42" },
      { type: "submit" },
    )).toEqual({ open: false, reference: "" });
  });
});
