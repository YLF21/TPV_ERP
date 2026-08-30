// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { submitFiscalExportDownload } from "./fiscalExportDownload";

describe("fiscal export streamed download", () => {
  afterEach(() => document.body.replaceChildren());

  it("posts the one-use capability in a hidden form, never in the URL", () => {
    const submit = vi.spyOn(HTMLFormElement.prototype, "submit").mockImplementation(() => {});
    submitFiscalExportDownload("A".repeat(43));
    const form = submit.mock.instances[0] as HTMLFormElement;
    const input = form.querySelector<HTMLInputElement>("input[name=token]");
    expect(form.method).toBe("post");
    expect(form.action).not.toContain("A".repeat(43));
    expect(input?.value).toBe("A".repeat(43));
    expect(submit).toHaveBeenCalledOnce();
    submit.mockRestore();
  });
});
