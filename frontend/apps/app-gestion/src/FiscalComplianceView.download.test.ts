// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { downloadResponsibleDeclaration } from "./FiscalComplianceView";

describe("responsible declaration download", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("uses the authenticated same-origin request and creates a named download", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("declaration", {
      status: 200,
      headers: { "content-length": "11", "content-type": "application/pdf" }
    }));
    vi.stubGlobal("fetch", fetchMock);
    const createObjectUrl = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:declaration");
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);

    await downloadResponsibleDeclaration("/api/v1/fiscal/responsible-declaration/download?token=one-use", "declaracion.pdf", "access-token");

    expect(fetchMock).toHaveBeenCalledWith(
      "http://localhost:3000/api/v1/fiscal/responsible-declaration/download?token=one-use",
      { headers: { Authorization: "Bearer access-token" } }
    );
    expect(createObjectUrl).toHaveBeenCalledOnce();
    expect(click).toHaveBeenCalledOnce();
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:declaration");
  });

  it("refuses an external URL before making a request", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    await expect(downloadResponsibleDeclaration("https://attacker.invalid/declaration", "x.pdf", "token")).rejects.toThrow("external_declaration_url");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
