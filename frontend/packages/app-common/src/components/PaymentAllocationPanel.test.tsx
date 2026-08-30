// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
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
  it("keeps F10 spendable when the local wallet snapshot is stale but central reservation is valid", () => {
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [] }}
      providers={[]}
      manualCardEnabled
      acceptSubmitsCurrent
      customerSelected
      memberBalanceEligibleTotalCents={1200}
      memberBalanceReservedLoyaltyCents={1200}
      memberBalanceReady
      pricingReady
      memberWallet={{ loyaltyAvailable: 0, returnCreditAvailable: 0, totalAvailable: 0, lots: [] }}
      onMemberWallet={vi.fn()}
      onClose={vi.fn()}
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);

    expect(within(container).getByRole("button", { name: /Saldo socio/ })).toBeEnabled();
  });

  it("uses central typed reservation amounts while the local wallet snapshot is absent", () => {
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [] }}
      providers={[]}
      manualCardEnabled
      acceptSubmitsCurrent
      customerSelected
      memberBalanceEligibleTotalCents={1200}
      memberBalanceReservedLoyaltyCents={800}
      memberBalanceReservedReturnCreditCents={400}
      memberBalanceRetentionHeldCents={100}
      memberBalanceReady
      pricingReady
      onMemberBalance={vi.fn()}
      onClose={vi.fn()}
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);

    expect(within(container).getByRole("button", { name: /Saldo socio/ }))
      .toHaveTextContent("Disponible: 11,00 €");
  });

  it("disables F10 when retention holds the full reserved balance", () => {
    const onMemberBalance = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, totalCents: 500, allocations: [] }}
      providers={[]}
      manualCardEnabled
      customerSelected
      memberBalanceEligibleTotalCents={500}
      memberBalanceReservedLoyaltyCents={500}
      memberBalanceRetentionHeldCents={500}
      memberBalanceReady
      pricingReady
      onMemberBalance={onMemberBalance}
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);

    const memberButton = within(container).getByRole("button", { name: /Saldo socio/ });
    expect(memberButton).toBeDisabled();
    expect(memberButton).toHaveTextContent("Disponible: 0,00 €");
    fireEvent.keyDown(window, { key: "F10" });
    expect(onMemberBalance).not.toHaveBeenCalled();
  });

  it("shows and applies only the net balance after a partial retention hold", () => {
    const onMemberBalance = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, totalCents: 1000, allocations: [] }}
      providers={[]}
      manualCardEnabled
      customerSelected
      memberBalanceEligibleTotalCents={1000}
      memberBalanceReservedLoyaltyCents={800}
      memberBalanceRetentionHeldCents={250}
      memberBalanceReady
      pricingReady
      onMemberBalance={onMemberBalance}
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);

    const memberButton = within(container).getByRole("button", { name: /Saldo socio/ });
    expect(memberButton).toBeEnabled();
    expect(memberButton).toHaveTextContent("Disponible: 5,50 €");
    fireEvent.click(memberButton);
    const amount = within(container).getByRole("textbox", { name: /IMPORTE/ });
    expect(amount).toHaveValue("5,50");
    fireEvent.keyDown(amount, { key: "Enter" });

    expect(onMemberBalance).toHaveBeenCalledWith(550);
  });

  it("disables F10 in a mixed member return-and-sale checkout", () => {
    const onMemberBalance = vi.fn();
    const onDiscount = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, totalCents: 500, direction: "SALE", allocations: [] }}
      providers={[]}
      manualCardEnabled
      customerSelected
      memberBalanceBlockedByReturn
      memberBalanceEligibleTotalCents={500}
      memberBalanceReservedLoyaltyCents={400}
      memberBalanceReservedReturnCreditCents={1000}
      memberBalanceReady
      pricingReady
      discountVisible
      onMemberBalance={onMemberBalance}
      onDiscount={onDiscount}
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);

    const memberButton = within(container).getByRole("button", { name: /Saldo socio/ });
    expect(memberButton).toBeDisabled();
    fireEvent.keyDown(window, { key: "F10" });
    expect(onMemberBalance).not.toHaveBeenCalled();

    const discountButton = within(container).getByRole("button", { name: /Descuento/ });
    expect(discountButton).toBeEnabled();
    fireEvent.click(discountButton);
    fireEvent.keyDown(within(container).getByRole("textbox", { name: /IMPORTE/ }), { key: "Enter" });
    expect(onDiscount).toHaveBeenCalledWith(expect.any(Number));
    expect(onDiscount.mock.calls[0][0]).toBeGreaterThan(0);
  });

  it("allows a pure return to be credited to a zero-balance member wallet", () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{
        ...session,
        totalCents: 500,
        direction: "REFUND",
        allocations: [],
        refundPaymentAvailability: [],
      }}
      providers={[]}
      manualCardEnabled
      cashEnabled={false}
      cardEnabled={false}
      voucherEnabled={false}
      customerSelected
      memberCreditEligible
      memberBalanceBlockedByReturn
      acceptSubmitsCurrent
      onClose={vi.fn()}
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);

    const credit = within(container).getByRole("button", { name: /Saldo a favor/ });
    expect(credit).toBeEnabled();
    fireEvent.click(credit);
    fireEvent.click(within(container).getByRole("button", { name: "ACEPTAR" }));
    expect(onAdd).toHaveBeenCalledWith({
      kind: "MEMBER_CREDIT",
      amountCents: 500,
    }, { finalizeWhenCovered: true });
  });

  it("allows gift receipt return credit for the selected member and explains the option", () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{
        ...session,
        totalCents: 500,
        direction: "REFUND",
        allocations: [],
        refundPaymentAvailability: [],
      }}
      providers={[]}
      manualCardEnabled={false}
      cashEnabled={false}
      cardEnabled={false}
      voucherEnabled
      voucherOnlyRefund
      customerSelected
      memberCreditEligible
      acceptSubmitsCurrent
      onClose={vi.fn()}
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);

    expect(within(container).getByText(/admite un vale o un abono al saldo/)).toBeInTheDocument();
    const credit = within(container).getByRole("button", { name: /Saldo a favor/ });
    expect(credit).toBeEnabled();
    fireEvent.click(credit);
    fireEvent.click(within(container).getByRole("button", { name: "ACEPTAR" }));

    expect(onAdd).toHaveBeenCalledWith({
      kind: "MEMBER_CREDIT",
      amountCents: 500,
    }, { finalizeWhenCovered: true });
  });

  it("consolidates approved sale balance and return credit without changing the allocation payload", () => {
    const mixedSale: PaymentSession = {
      id: "mixed-sale-balance",
      totalCents: 3,
      direction: "SALE",
      status: "COVERED",
      allocations: [{
        kind: "MEMBER_CREDIT",
        amountCents: 3,
        idempotencyKey: "return-credit-approved",
        status: "APPROVED",
      }],
    };
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={mixedSale}
      providers={[]}
      manualCardEnabled
      memberBalanceCents={94}
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);

    const table = container.querySelector(".sale-checkout-table")!;
    expect(table).toHaveTextContent("Saldo socio");
    expect(table).toHaveTextContent("-0,97 €");
    expect(table).not.toHaveTextContent("Saldo a favor");
    const totals = container.querySelector(".sale-checkout-totals")!;
    expect(totals).toHaveTextContent("0,97 €");
    expect(totals).toHaveTextContent("COBRADO0,97 €");
    expect(totals).toHaveTextContent("FALTA0,00 €");
  });

  it.each(["PENDING", "TIMEOUT", "DECLINED", "ERROR", "CANCELLED"] as const)(
    "keeps a non-approved sale return-credit allocation visible (%s)",
    (status) => {
      const { container } = render(<PaymentAllocationPanel
        locale="es"
        session={{
          id: `sale-credit-${status.toLowerCase()}`,
          totalCents: 5,
          direction: "SALE",
          status: "COLLECTING",
          allocations: [{
            kind: "MEMBER_CREDIT",
            amountCents: 3,
            idempotencyKey: `return-credit-${status.toLowerCase()}`,
            operationId: "return-credit-operation",
            status,
          }],
        }}
        providers={[]}
        manualCardEnabled
        memberBalanceCents={94}
        onAdd={vi.fn()}
        onQuery={vi.fn()}
      />);

      const table = container.querySelector(".sale-checkout-table")!;
      expect(table).toHaveTextContent("Saldo socio");
      expect(table).toHaveTextContent("Saldo a favor");
      expect(table).toHaveTextContent("0,03 €");
    },
  );

  it("keeps return-credit presentation separate on refunds", () => {
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{
        id: "refund-credit",
        totalCents: 3,
        direction: "REFUND",
        status: "COVERED",
        allocations: [{
          kind: "MEMBER_CREDIT",
          amountCents: 3,
          idempotencyKey: "refund-credit-approved",
          status: "APPROVED",
        }],
      }}
      providers={[]}
      manualCardEnabled
      memberBalanceCents={94}
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);

    const table = container.querySelector(".sale-checkout-table")!;
    expect(table).toHaveTextContent("Saldo a favor");
    expect(table).not.toHaveTextContent("-0,97 €");
    expect(container.querySelector(".sale-checkout-totals"))
      .toHaveTextContent("0,03 €");
  });

  it("keeps member credit available on voucher-only returns without a wallet snapshot", () => {
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{
        ...session,
        totalCents: 500,
        direction: "REFUND",
        allocations: [],
        refundPaymentAvailability: [],
      }}
      providers={[]}
      manualCardEnabled
      voucherOnlyRefund
      customerSelected
      memberCreditEligible
      initialMethod="MEMBER_CREDIT"
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);

    expect(within(container).getByRole("button", { name: /Saldo a favor/ }))
      .toBeEnabled();
  });

  it("disables member credit when the selected customer is not an active member", () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{
        ...session,
        totalCents: 500,
        direction: "REFUND",
        allocations: [],
        refundPaymentAvailability: [],
      }}
      providers={[]}
      manualCardEnabled
      cashEnabled={false}
      cardEnabled={false}
      voucherEnabled={false}
      customerSelected
      memberCreditEligible={false}
      initialMethod="MEMBER_CREDIT"
      acceptSubmitsCurrent
      onClose={vi.fn()}
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);

    const credit = within(container).getByRole("button", { name: /Saldo a favor/ });
    expect(credit).toBeDisabled();
    fireEvent.click(credit);
    fireEvent.click(within(container).getByRole("button", { name: "ACEPTAR" }));
    expect(onAdd).not.toHaveBeenCalled();
  });

  it("keeps the nested member wallet above checkout and isolates keyboard focus", () => {
    const tpvCss = readFileSync(resolve(process.cwd(), "packages/app-common/src/styles/tpv.css"), "utf8");
    const walletCss = readFileSync(resolve(process.cwd(), "packages/app-common/src/components/MemberWalletDialog.css"), "utf8");
    expect(tpvCss).toContain("--tpv-layer-modal: 1500;");
    expect(tpvCss).toContain("--tpv-layer-nested-modal: 1510;");
    expect(walletCss).toMatch(/\.member-wallet-overlay\s*\{[\s\S]*?z-index:\s*var\(--tpv-layer-nested-modal, 1510\);/);

    const onClose = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled customerSelected memberBalanceEligibleTotalCents={1200}
      memberWallet={{
        loyaltyAvailable: 12,
        returnCreditAvailable: 0,
        totalAvailable: 12,
        lots: [],
      }}
      onMemberWallet={vi.fn()} onClose={onClose} onAdd={vi.fn()} onQuery={vi.fn()}
    />);

    const memberButton = within(container).getByRole("button", { name: /Saldo socio/ });
    memberButton.focus();
    fireEvent.click(memberButton);

    const checkout = container.querySelector<HTMLElement>(".sale-checkout-dialog");
    expect(checkout).not.toBeNull();
    const wallet = within(container).getByRole("dialog", { name: "Consumir saldo de socio" });
    const amount = within(wallet).getByRole("textbox");
    expect(checkout).toHaveAttribute("aria-hidden", "true");
    expect(amount).toHaveFocus();

    const close = within(wallet).getByRole("button", { name: "Cerrar" });
    const apply = within(wallet).getByRole("button", { name: "Aplicar saldo" });
    apply.focus();
    fireEvent.keyDown(apply, { key: "Tab" });
    expect(close).toHaveFocus();

    fireEvent.keyDown(amount, { key: "Escape" });
    expect(within(container).queryByRole("dialog", { name: "Consumir saldo de socio" })).not.toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(container.querySelector(".sale-checkout-dialog")).not.toHaveAttribute("aria-hidden");
    expect(memberButton).toHaveFocus();
  });

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

  it("offers an original transfer refund, keeps its reference, and hides the transfer date", () => {
    const onAdd = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{
        ...session,
        direction: "REFUND",
        allocations: [],
        refundPaymentAvailability: [
          { paymentMethod: "TRANSFERENCIA", kind: "TRANSFER", originalAmountCents: 1200, refundedAmountCents: 0, reservedAmountCents: 0, availableAmountCents: 1200 },
        ],
      }}
      providers={[]}
      manualCardEnabled={false}
      cashEnabled={false}
      cardEnabled={false}
      voucherEnabled={false}
      transferEnabled
      transferRequiresReference
      transferDateEnabled
      initialMethod="TRANSFER"
      onAdd={onAdd}
      onQuery={vi.fn()}
    />);

    const transfer = within(container).getByRole("button", { name: /Transferencia.*12,00/ });
    expect(transfer).toBeEnabled();
    expect(container.querySelector("#checkout-transfer-date")).toBeNull();
    const reference = within(container).getByLabelText("Nº DOCUMENTO");
    fireEvent.change(reference, { target: { value: "TR-REF-1" } });
    const amount = container.querySelector<HTMLInputElement>(".sale-checkout-entry > label input")!;
    fireEvent.keyDown(amount, { key: "Enter" });

    expect(onAdd).toHaveBeenCalledWith(
      { kind: "TRANSFER", amountCents: 1200, reference: "TR-REF-1" },
      { finalizeWhenCovered: true },
    );
  });

  it("does not offer a transfer refund without available original transfer balance", () => {
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, direction: "REFUND", allocations: [], refundPaymentAvailability: [] }}
      providers={[]}
      manualCardEnabled={false}
      cashEnabled={false}
      cardEnabled={false}
      voucherEnabled={false}
      transferEnabled
      initialMethod="TRANSFER"
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);

    expect(within(container).queryByRole("button", { name: /Transferencia/ })).toBeNull();
  });

  it("presents a ZERO session covered by loyalty as a fully paid sale", () => {
    const html = renderToStaticMarkup(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, totalCents: 0, direction: "ZERO", status: "COVERED", allocations: [] }}
      providers={[]}
      manualCardEnabled
      memberBalanceCents={94}
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);

    expect(html).toContain("Saldo socio");
    expect(html).toContain("-0,94 €");
    expect(html).toContain("TOTAL A COBRAR");
    expect(html).toContain("0,94 €");
    expect(html).toContain("COBRADO");
    expect(html).toContain("FALTA");
    expect(html).toMatch(/class="remaining">FALTA<strong>0,00 €/);
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

  it("does not enable member balance before pricing and caps it by the eligible quote total", () => {
    const onMemberBalance = vi.fn();
    const props = {
      locale: "es" as const,
      session: { ...session, allocations: [] },
      providers: [] as string[],
      manualCardEnabled: true,
      customerSelected: true,
      memberBalanceAvailableCents: 2000,
      memberBalanceEligibleTotalCents: 200,
      onMemberBalance,
      onAdd: vi.fn(),
      onQuery: vi.fn(),
    };
    const view = render(<PaymentAllocationPanel {...props} pricingReady={false} />);
    const memberButton = within(view.container).getByRole("button", { name: /Saldo socio/ });
    expect(memberButton).toBeDisabled();

    fireEvent.keyDown(window, { key: "F10" });
    expect(onMemberBalance).not.toHaveBeenCalled();

    view.rerender(<PaymentAllocationPanel {...props} pricingReady />);
    expect(memberButton).toBeEnabled();
    fireEvent.click(memberButton);
    const amount = within(view.container).getByRole("textbox", { name: /IMPORTE/ });
    expect(amount).toHaveValue("2,00");

    fireEvent.change(amount, { target: { value: "2,01" } });
    fireEvent.keyDown(amount, { key: "Enter" });
    expect(onMemberBalance).not.toHaveBeenCalled();

    fireEvent.change(amount, { target: { value: "2,00" } });
    fireEvent.keyDown(amount, { key: "Enter" });
    expect(onMemberBalance).toHaveBeenCalledWith(200);
  });

  it("keeps F10 inert and hides gross wallet data while retention is pending", () => {
    const onMemberWallet = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled customerSelected discountVisible pricingReady memberBalanceReady={false}
      memberBalanceEligibleTotalCents={1200}
      memberWallet={{ loyaltyAvailable: 25, returnCreditAvailable: 4, totalAvailable: 29, lots: [] }}
      onMemberWallet={onMemberWallet} onDiscount={vi.fn()} onAdd={vi.fn()} onQuery={vi.fn()}
    />);

    const memberButton = within(container).getByRole("button", { name: /Saldo socio/ });
    const discountButton = within(container).getByRole("button", { name: /Descuento/ });
    expect(memberButton).toBeDisabled();
    expect(discountButton).toBeEnabled();
    expect(memberButton).toHaveAccessibleName("Saldo socioDisponible: 0,00 €");

    fireEvent.keyDown(window, { key: "F10" });
    expect(onMemberWallet).not.toHaveBeenCalled();
    expect(within(container).queryByRole("dialog", { name: "Consumir saldo de socio" })).not.toBeInTheDocument();
  });

  it("does not reactivate F10 after the reservation becomes unavailable", () => {
    const onMemberWallet = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled customerSelected discountVisible pricingReady memberBalanceReady={false}
      memberBalanceEligibleTotalCents={1200}
      memberWallet={{ loyaltyAvailable: 25, returnCreditAvailable: 4, totalAvailable: 29, lots: [] }}
      onMemberWallet={onMemberWallet} onDiscount={vi.fn()} onAdd={vi.fn()} onQuery={vi.fn()}
    />);

    expect(within(container).getByRole("button", { name: /Saldo socio/ })).toBeDisabled();
    expect(within(container).getByRole("button", { name: /Descuento/ })).toBeEnabled();
    fireEvent.keyDown(window, { key: "F10" });
    expect(onMemberWallet).not.toHaveBeenCalled();
    expect(within(container).queryByRole("dialog", { name: "Consumir saldo de socio" })).not.toBeInTheDocument();
  });

  it("intersects typed wallet buckets before subtracting a partial loyalty hold", () => {
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, totalCents: 3000, allocations: [] }}
      providers={[]}
      manualCardEnabled
      customerSelected
      memberBalanceCents={3000}
      memberBalanceEligibleTotalCents={3000}
      memberBalanceReservedLoyaltyCents={1321}
      memberBalanceReservedReturnCreditCents={442}
      memberBalanceRetentionHeldCents={4}
      memberBalanceRetentionHeldReturnCreditCents={0}
      memberWallet={{ loyaltyAvailable: 12.99, returnCreditAvailable: 4.42, totalAvailable: 17.41, lots: [] }}
      onMemberWallet={vi.fn()}
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);

    const memberButton = within(container).getByRole("button", { name: /Saldo socio/ });
    expect(memberButton).toHaveTextContent("Disponible: 17,59 €");
    fireEvent.click(memberButton);
    expect(within(container).getByRole("dialog", { name: "Consumir saldo de socio" }))
      .toHaveTextContent("Máximo aplicable a esta venta: 17,59");
  });

  it("keeps return credit available when no purchase amount is eligible", () => {
    const onMemberWallet = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled customerSelected memberBalanceEligibleTotalCents={0}
      memberWallet={{
        loyaltyAvailable: 0,
        returnCreditAvailable: 10,
        totalAvailable: 10,
        lots: [{
          id: "credit-1", type: "RETURN_CREDIT", sourceMovementType: "ABONO_CREDITO_DEVOLUCION",
          originalAmount: 10, availableAmount: 10, obtainedAt: "2026-08-28T00:00:00Z",
        }],
      }}
      onMemberWallet={onMemberWallet} onAdd={vi.fn()} onQuery={vi.fn()}
    />);

    fireEvent.keyDown(window, { key: "F10" });
    const dialog = within(container).getByRole("dialog", { name: /Consumir saldo de socio/ });
    const amount = within(dialog).getByRole("textbox");
    expect(amount).toHaveValue("10,00");
    fireEvent.click(within(dialog).getByRole("button", { name: "Aplicar saldo" }));

    expect(onMemberWallet).toHaveBeenCalledWith(expect.objectContaining({
      requestedCents: 1000,
      loyaltyCents: 0,
      returnCreditCents: 1000,
    }));
  });

  it("caps loyalty consumption but preserves return credit in a mixed wallet", () => {
    const onMemberWallet = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled customerSelected memberBalanceEligibleTotalCents={200}
      memberWallet={{
        loyaltyAvailable: 8,
        returnCreditAvailable: 10,
        totalAvailable: 18,
        lots: [],
      }}
      onMemberWallet={onMemberWallet} onAdd={vi.fn()} onQuery={vi.fn()}
    />);

    fireEvent.keyDown(window, { key: "F10" });
    const dialog = within(container).getByRole("dialog", { name: /Consumir saldo de socio/ });
    expect(within(dialog).getByRole("textbox")).toHaveValue("12,00");
    fireEvent.click(within(dialog).getByRole("button", { name: "Aplicar saldo" }));

    expect(onMemberWallet).toHaveBeenCalledWith(expect.objectContaining({
      requestedCents: 1200,
      loyaltyCents: 200,
      returnCreditCents: 1000,
    }));
  });

  it("caps a gross wallet by the net retained loyalty and keeps return credit separate", () => {
    const onMemberWallet = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled customerSelected memberBalanceEligibleTotalCents={1200}
      memberBalanceReservedLoyaltyCents={800}
      memberBalanceReservedReturnCreditCents={1000}
      memberBalanceRetentionHeldCents={600}
      memberWallet={{ loyaltyAvailable: 8, returnCreditAvailable: 10, totalAvailable: 18, lots: [] }}
      onMemberWallet={onMemberWallet} onAdd={vi.fn()} onQuery={vi.fn()}
    />);

    fireEvent.keyDown(window, { key: "F10" });
    const dialog = within(container).getByRole("dialog", { name: /Consumir saldo de socio/ });
    expect(within(dialog).getByRole("textbox")).toHaveValue("12,00");
    expect(within(dialog).getByText(/Total bruto/).textContent).toContain("18,00");
    fireEvent.click(within(dialog).getByRole("button", { name: "Aplicar saldo" }));
    expect(onMemberWallet).toHaveBeenCalledWith(expect.objectContaining({
      requestedCents: 1200,
      loyaltyCents: 200,
      returnCreditCents: 1000,
    }));
  });

  it("highlights the exact held lot in the F10 wallet dialog", () => {
    const { container } = render(<PaymentAllocationPanel
      locale="es"
      session={{ ...session, allocations: [] }}
      providers={[]}
      manualCardEnabled
      customerSelected
      memberBalanceEligibleTotalCents={200}
      memberBalanceReservedLoyaltyCents={200}
      memberBalanceRetentionHeldCents={4}
      memberWallet={{
        loyaltyAvailable: 2,
        returnCreditAvailable: 0,
        totalAvailable: 2,
        lots: [{
          id: "lot-001-260829-00003",
          type: "LOYALTY",
          documentId: "document-001-260829-00003",
          documentNumber: "001-260829-00003",
          sourceMovementType: "ACUMULACION_SALDO",
          originalAmount: 2,
          availableAmount: 2,
          heldAmount: 0.04,
          obtainedAt: "2026-08-29T10:00:00Z",
        }],
      }}
      onMemberWallet={vi.fn()}
      onAdd={vi.fn()}
      onQuery={vi.fn()}
    />);

    fireEvent.keyDown(window, { key: "F10" });
    const dialog = within(container).getByRole("dialog", { name: "Consumir saldo de socio" });
    const row = container.querySelector(".member-wallet-lot-partial-hold");
    expect(row).not.toBeNull();
    expect(row).toHaveTextContent("001-260829-00003");
    expect(row).toHaveTextContent("0,04");
    expect(row).toHaveTextContent("1,96");
    expect(dialog.querySelector(".member-wallet-balance-summary strong:nth-child(2)")).toHaveTextContent("Disponible neto");
    expect(dialog.querySelector(".member-wallet-balance-summary strong:nth-child(2)")).toHaveTextContent("1,96");
  });

  it("does not expose gross loyalty when the retention hold consumes it", () => {
    const onMemberWallet = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled customerSelected memberBalanceEligibleTotalCents={1200}
      memberBalanceReservedLoyaltyCents={800}
      memberBalanceReservedReturnCreditCents={0}
      memberBalanceRetentionHeldCents={600}
      memberWallet={{ loyaltyAvailable: 8, returnCreditAvailable: 0, totalAvailable: 8, lots: [] }}
      onMemberWallet={onMemberWallet} onAdd={vi.fn()} onQuery={vi.fn()}
    />);

    fireEvent.keyDown(window, { key: "F10" });
    const dialog = within(container).getByRole("dialog", { name: /Consumir saldo de socio/ });
    expect(within(dialog).getByRole("textbox")).toHaveValue("2,00");
    expect(within(dialog).getByText(/Bloqueo parcial de saldo/)).toBeInTheDocument();
  });

  it("limits F11 manual discount to the authoritative eligible total", () => {
    const onDiscount = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled discountVisible
      memberBalanceEligibleTotalCents={200} pricingReady
      onDiscount={onDiscount} onAdd={vi.fn()} onQuery={vi.fn()}
    />);

    fireEvent.click(within(container).getByRole("button", { name: /Descuento/ }));
    const amount = within(container).getByRole("textbox", { name: /IMPORTE/ });
    expect(amount).toHaveValue("2,00");
    fireEvent.change(amount, { target: { value: "3,00" } });
    fireEvent.keyDown(amount, { key: "Enter" });
    expect(onDiscount).not.toHaveBeenCalled();

    fireEvent.change(amount, { target: { value: "2,00" } });
    fireEvent.keyDown(amount, { key: "Enter" });
    expect(onDiscount).toHaveBeenCalledWith(200);
  });

  it("keeps the F11 cap stable after existing discount and member balance reductions", () => {
    const onDiscount = vi.fn();
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled discountVisible checkoutDiscountCents={400}
      memberBalanceCents={200} memberBalanceEligibleTotalCents={600} pricingReady
      onDiscount={onDiscount} onAdd={vi.fn()} onQuery={vi.fn()}
    />);

    fireEvent.click(within(container).getByRole("button", { name: /Descuento/ }));
    expect(within(container).getByRole("textbox", { name: /IMPORTE/ })).toHaveValue("8,00");
  });

  it("does not select F11 after a payment has already been recorded", () => {
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [session.allocations[0]] }} providers={[]}
      manualCardEnabled discountVisible pricingReady onDiscount={vi.fn()}
      onAdd={vi.fn()} onQuery={vi.fn()}
    />);

    const discountButton = within(container).getByRole("button", { name: /Descuento/ });
    expect(discountButton).toBeDisabled();
    fireEvent.keyDown(window, { key: "F11" });
    expect(within(container).getByRole("textbox", { name: /IMPORTE/ })).toHaveValue("7,00");
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

  it("places discount in the first row and member balance alone afterwards with its available amount", () => {
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled customerSelected memberBalanceAvailableCents={2590}
      onDiscount={vi.fn()} onMemberBalance={vi.fn()} onAdd={vi.fn()} onQuery={vi.fn()}
    />);

    const methodButtons = Array.from(container.querySelectorAll<HTMLButtonElement>(
      ".sale-checkout-methods button",
    ));
    expect(methodButtons.map((button) => button.textContent)).toEqual([
      "Efectivo*",
      "Tarjeta+",
      "ValeF9",
      "PendienteF8",
      "TransferenciaF7",
      "DescuentoF11",
      "Saldo socioDisponible: 25,90 €F10",
    ]);
    expect(methodButtons.at(-1)).toHaveClass("sale-checkout-member-balance");
  });

  it("does not show member balance when there is no active member action", () => {
    const { container } = render(<PaymentAllocationPanel
      locale="es" session={{ ...session, allocations: [] }} providers={[]}
      manualCardEnabled customerSelected memberBalanceAvailableCents={2590}
      onDiscount={vi.fn()} onAdd={vi.fn()} onQuery={vi.fn()}
    />);

    expect(within(container).queryByRole("button", { name: /Saldo socio/ })).not.toBeInTheDocument();
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
