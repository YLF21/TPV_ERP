import { createRequire } from "node:module";
import { describe, expect, it } from "vitest";

const require = createRequire(import.meta.url);
const {
  isMicrosoftPrintToPdf,
  renderedPdfBuffer,
  renderedPdfDataUrl,
  renderedPdfDefaultFileName,
} = require("./pdf-document.cjs");

describe("Jasper rendered PDF validation", () => {
  const pdf = Buffer.from("%PDF-1.7\nbody\n%%EOF", "ascii");
  const document = {
    renderedPdf: {
      contentType: "application/pdf",
      base64: pdf.toString("base64"),
    },
  };

  it("decodes a trusted PDF payload for direct export", () => {
    expect(renderedPdfBuffer(document)).toEqual(pdf);
    expect(renderedPdfDataUrl(document)).toBe(
      `data:application/pdf;base64,${pdf.toString("base64")}`,
    );
  });

  it("keeps the HTML route when Jasper did not render the document", () => {
    expect(renderedPdfBuffer({})).toBeNull();
    expect(renderedPdfDataUrl({})).toBeNull();
  });

  it.each([
    { contentType: "text/html", base64: pdf.toString("base64") },
    { contentType: "application/pdf", base64: Buffer.from("not-pdf").toString("base64") },
    { contentType: "application/pdf", base64: "not base64" },
  ])("rejects malformed or non-PDF renderer payloads", (renderedPdf) => {
    expect(() => renderedPdfBuffer({ renderedPdf })).toThrow("INVALID_RENDERED_PDF");
  });

  it("detects Microsoft Print to PDF without depending on casing or surrounding spaces", () => {
    expect(isMicrosoftPrintToPdf("Microsoft Print to PDF")).toBe(true);
    expect(isMicrosoftPrintToPdf("  MICROSOFT PRINT TO PDF  ")).toBe(true);
    expect(isMicrosoftPrintToPdf("HP LaserJet")).toBe(false);
    expect(isMicrosoftPrintToPdf()).toBe(false);
  });

  it("builds a Windows-safe default name from the commercial document number", () => {
    expect(renderedPdfDefaultFileName({ documentNumber: "FV-001-26-000014" }))
      .toBe("FV-001-26-000014.pdf");
    expect(renderedPdfDefaultFileName({ documentNumber: "FV/2026:14" }))
      .toBe("FV-2026-14.pdf");
    expect(renderedPdfDefaultFileName({ title: "FACTURA" })).toBe("FACTURA.pdf");
  });
});
