import { describe, expect, it, vi } from "vitest";
import {
  activateDocumentTemplate,
  createDocumentTemplateDraft,
  loadDocumentTemplateCatalog,
  uploadDocumentTemplateArtifact,
} from "./documentTemplatesApi";

describe("document templates API", () => {
  it("loads the selected document catalog", async () => {
    const request = vi.fn().mockResolvedValue({ effective: {}, storeTemplates: [] });

    await loadDocumentTemplateCatalog("ALBARAN_VENTA", "token", request);

    expect(request).toHaveBeenCalledWith(
      "/document-templates?type=ALBARAN_VENTA",
      { token: "token" },
    );
  });

  it("creates a store draft with its document type", async () => {
    const request = vi.fn().mockResolvedValue({ id: "template-1" });
    const value = { type: "TICKET" as const, code: "TICKET_80", name: "Ticket tienda" };

    await createDocumentTemplateDraft(value, "token", request);

    expect(request).toHaveBeenCalledWith("/document-templates/store-drafts", {
      method: "POST",
      token: "token",
      body: value,
    });
  });

  it("uploads JRXML as multipart and activates the validated version", async () => {
    const request = vi.fn().mockResolvedValue({ id: "template-1" });
    const file = new File(["<jasperReport/>"], "ticket.jrxml", { type: "application/xml" });

    await uploadDocumentTemplateArtifact("template-1", file, "token", request);
    await activateDocumentTemplate("template-1", "token", request);

    const upload = request.mock.calls[0];
    expect(upload[0]).toBe("/document-templates/template-1/artifact");
    expect(upload[1].body).toBeInstanceOf(FormData);
    expect((upload[1].body as FormData).get("file")).toBe(file);
    expect(request).toHaveBeenLastCalledWith("/document-templates/template-1/activate", {
      method: "POST",
      token: "token",
    });
  });
});
