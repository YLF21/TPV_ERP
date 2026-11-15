// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { downloadResponsibleDeclaration } from "./FiscalComplianceView";
import { previewExport } from "../../../packages/app-common/src/export/previewExport";

vi.mock("../../../packages/app-common/src/export/previewExport", () => ({ previewExport: vi.fn() }));

describe("responsible declaration download", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("uses the authenticated same-origin request and opens a named preview", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("declaration", {
      status: 200,
      headers: { "content-length": "11", "content-type": "application/pdf" }
    }));
    vi.stubGlobal("fetch", fetchMock);
    vi.mocked(previewExport).mockResolvedValueOnce({ downloaded: false, printed: false, canceled: true });
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);

    await downloadResponsibleDeclaration("/api/v1/fiscal/responsible-declaration/download?token=one-use", "declaracion.pdf", "access-token");

    expect(fetchMock).toHaveBeenCalledWith(
      "http://localhost:3000/api/v1/fiscal/responsible-declaration/download?token=one-use",
      { headers: { Authorization: "Bearer access-token" } }
    );
    expect(previewExport).toHaveBeenCalledWith(expect.objectContaining({ fileName: "declaracion.pdf", blob: expect.any(Blob) }));
    expect(click).not.toHaveBeenCalled();
  });

  it("refuses an external URL before making a request", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    await expect(downloadResponsibleDeclaration("https://attacker.invalid/declaration", "x.pdf", "token")).rejects.toThrow("external_declaration_url");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
