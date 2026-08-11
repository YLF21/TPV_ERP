const MAX_PDF_BYTES = 20 * 1024 * 1024;

function renderedPdfBuffer(document) {
  const rendered = document?.renderedPdf;
  if (rendered == null) return null;
  if (rendered.contentType !== "application/pdf"
      || typeof rendered.base64 !== "string"
      || rendered.base64.length === 0
      || rendered.base64.length > Math.ceil(MAX_PDF_BYTES * 4 / 3) + 8
      || !/^[A-Za-z0-9+/]+={0,2}$/.test(rendered.base64)) {
    throw new Error("INVALID_RENDERED_PDF");
  }
  const bytes = Buffer.from(rendered.base64, "base64");
  if (bytes.length === 0 || bytes.length > MAX_PDF_BYTES
      || bytes.subarray(0, 5).toString("ascii") !== "%PDF-") {
    throw new Error("INVALID_RENDERED_PDF");
  }
  return bytes;
}

function renderedPdfDataUrl(document) {
  const bytes = renderedPdfBuffer(document);
  return bytes == null ? null : `data:application/pdf;base64,${bytes.toString("base64")}`;
}

function isMicrosoftPrintToPdf(printerName) {
  return String(printerName ?? "").trim().toLocaleLowerCase("en-US") === "microsoft print to pdf";
}

function renderedPdfDefaultFileName(document) {
  const source = String(document?.documentNumber || document?.title || "documento").trim();
  const safeName = source.replace(/[<>:"/\\|?*\u0000-\u001f]/g, "-").replace(/[. ]+$/g, "");
  return `${safeName || "documento"}.pdf`;
}

module.exports = {
  isMicrosoftPrintToPdf,
  renderedPdfBuffer,
  renderedPdfDataUrl,
  renderedPdfDefaultFileName
};
