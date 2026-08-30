// @vitest-environment jsdom

import { renderToStaticMarkup } from "react-dom/server";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it, vi } from "vitest";
import { MemberWalletDialog, type MemberWalletLocale } from "./MemberWalletDialog";

const baseProps = {
  lots: [],
  maxAmountCents: 570,
  totalAvailableCents: 1000,
  availableAmountCents: 570,
  onCancel: vi.fn(),
  onConfirm: vi.fn(),
};

describe("MemberWalletDialog", () => {
  it.each([
    ["es", "Total bruto", "Máximo aplicable a esta venta", "10,00", "5,70"],
    ["en", "Gross total", "Maximum applicable to this sale", "10.00", "5.70"],
    ["zh", "总额（毛额）", "本次销售可用上限", "10.00", "5.70"],
  ] as const)("shows total and sale maximum labels in %s", (locale, totalLabel, maximumLabel, total, maximum) => {
    const html = renderToStaticMarkup(<MemberWalletDialog
      {...baseProps}
      locale={locale as MemberWalletLocale}
    />);

    expect(html).toContain(totalLabel);
    expect(html).toContain(maximumLabel);
    expect(html).toContain(total);
    expect(html).toContain(maximum);
  });

  it("shows commercial document number and never exposes the UUID", () => {
    const html = renderToStaticMarkup(<MemberWalletDialog
      {...baseProps}
      locale="es"
      lots={[
        {
          id: "lot-numbered",
          type: "LOYALTY",
          documentId: "document-uuid-1",
          documentNumber: "FV-2026-0001",
          sourceMovementType: "ACUMULACION_SALDO",
          originalAmount: 4,
          availableAmount: 4,
          obtainedAt: "2026-07-01T10:00:00Z",
        },
        {
          id: "lot-unresolved",
          type: "LOYALTY",
          documentId: "document-uuid-2",
          documentNumber: "   ",
          sourceMovementType: "ACUMULACION_SALDO",
          originalAmount: 2,
          availableAmount: 2,
          obtainedAt: "2026-07-01T11:00:00Z",
        },
        {
          id: "lot-without-document",
          type: "RETURN_CREDIT",
          sourceMovementType: "ABONO_CREDITO_DEVOLUCION",
          originalAmount: 1,
          availableAmount: 1,
          obtainedAt: "2026-07-01T12:00:00Z",
        },
      ]}
    />);

    expect(html).toContain("Nº documento: FV-2026-0001");
    expect(html).toContain("Nº de documento no disponible");
    expect(html).toContain("Sin documento asociado");
    expect(html).not.toContain("document-uuid-1");
    expect(html).not.toContain("document-uuid-2");
    expect(html).not.toContain('title="document-uuid');
  });

  it.each([
    ["es", "Nº documento", "Nº de documento no disponible", "Sin documento asociado"],
    ["en", "Document No.", "Document number unavailable", "No associated document"],
    ["zh", "单据编号", "单据编号不可用", "无关联单据"],
  ] as const)("localizes document fallback labels in %s", (locale, numberLabel, unavailableLabel, noDocumentLabel) => {
    const html = renderToStaticMarkup(<MemberWalletDialog
      {...baseProps}
      locale={locale as MemberWalletLocale}
      lots={[{
        id: "lot-labels",
        type: "LOYALTY",
        documentId: "uuid-hidden",
        documentNumber: "DOC-001",
        sourceMovementType: "ACUMULACION_SALDO",
        originalAmount: 1,
        availableAmount: 1,
        obtainedAt: "2026-07-01T10:00:00Z",
      }, {
        id: "lot-labels-unavailable",
        type: "LOYALTY",
        documentId: "uuid-unresolved",
        documentNumber: null,
        sourceMovementType: "ACUMULACION_SALDO",
        originalAmount: 1,
        availableAmount: 1,
        obtainedAt: "2026-07-01T10:00:00Z",
      }, {
        id: "lot-labels-none",
        type: "RETURN_CREDIT",
        sourceMovementType: "ABONO_CREDITO_DEVOLUCION",
        originalAmount: 1,
        availableAmount: 1,
        obtainedAt: "2026-07-01T10:00:00Z",
      }]}
    />);

    expect(html).toContain(numberLabel);
    expect(html).toContain(unavailableLabel);
    expect(html).toContain(noDocumentLabel);
    expect(html).not.toContain("uuid-hidden");
    expect(html).not.toContain("uuid-unresolved");
  });

  it("keeps the wallet title white with a selector stronger than sale-payment h2", () => {
    const css = readFileSync(resolve(
      process.cwd(),
      "packages/app-common/src/components/MemberWalletDialog.css",
    ), "utf8");

    expect(css).toMatch(/\.member-wallet-dialog \.member-wallet-header h2\s*\{[\s\S]*?color:\s*#ffffff;/);
  });

  it.each([
    ["es", "Bloqueo parcial de saldo", "Máximo aplicable a esta venta: 2,00"],
    ["en", "Partial balance hold", "Maximum applicable to this sale: €2.00"],
    ["zh", "余额部分锁定", "本次销售可用上限: €2.00"],
  ] as const)("shows the net maximum and known return hold in %s", (locale, heldLabel, maximum) => {
    const html = renderToStaticMarkup(<MemberWalletDialog
      {...baseProps}
      locale={locale as MemberWalletLocale}
      maxAmountCents={200}
      retentionHeldCents={800}
    />);
    expect(html).toContain(heldLabel);
    expect(html).toContain(maximum);
  });

  it("shows zero net total when the whole gross loyalty wallet is held", () => {
    const html = renderToStaticMarkup(<MemberWalletDialog
      {...baseProps}
      locale="es"
      maxAmountCents={0}
      totalAvailableCents={0}
      retentionHeldCents={1295}
    />);
    expect(html).toContain("Total bruto: 0,00");
    expect(html).toContain("Bloqueo parcial de saldo: 12,95");
  });

  it("includes unblocked return credit in the net total", () => {
    const html = renderToStaticMarkup(<MemberWalletDialog
      {...baseProps}
      locale="es"
      maxAmountCents={1000}
      totalAvailableCents={1000}
      retentionHeldCents={1295}
    />);
    expect(html).toContain("Total bruto: 10,00");
    expect(html).toContain("Bloqueo parcial de saldo: 12,95");
  });

  it("shows each lot's net available amount beside its exact hold", () => {
    const html = renderToStaticMarkup(<MemberWalletDialog
      {...baseProps}
      locale="es"
      maxAmountCents={196}
      totalAvailableCents={196}
      lots={[{
        id: "lot-partial",
        type: "LOYALTY",
        documentId: "document-001-260829-00003",
        documentNumber: "001-260829-00003",
        sourceMovementType: "ACUMULACION_SALDO",
        originalAmount: 2,
        availableAmount: 2,
        heldAmount: 0.04,
        obtainedAt: "2026-07-01T10:00:00Z",
      }]}
    />);
    expect(html).toContain("1,96");
    expect(html).toContain("0,04");
    expect(html).toContain("001-260829-00003");
    expect(html).toContain("member-wallet-lot-partial-hold");
  });

  it("shows gross total and net available as separate wallet values", () => {
    const html = renderToStaticMarkup(<MemberWalletDialog
      {...baseProps}
      locale="es"
      totalAvailableCents={1000}
      availableAmountCents={396}
      maxAmountCents={250}
    />);
    expect(html).toContain("Total bruto: 10,00");
    expect(html).toContain("Disponible neto: 3,96");
    expect(html).toContain("Máximo aplicable a esta venta: 2,50");
  });
});
