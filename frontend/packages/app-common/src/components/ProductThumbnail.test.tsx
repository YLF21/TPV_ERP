// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ProductThumbnail } from "./ProductThumbnail";

describe("ProductThumbnail", () => {
  it("uses a stable name fallback without an image", () => {
    render(<ProductThumbnail productId="p1" name="Agua" token="token" />);
    expect(screen.getByText("A")).toBeInTheDocument();
  });

  it("loads authenticated thumbnails and revokes the temporary URL", async () => {
    const createObjectURL = vi.fn(() => "blob:thumbnail");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", { createObjectURL, revokeObjectURL });
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => ({ ok: true, blob: async () => new Blob(["image"]) })),
    );
    const view = render(
      <ProductThumbnail
        productId="p/1"
        imageId="img-1"
        name="Agua"
        token="token"
      />,
    );
    await waitFor(() =>
      expect(view.container.querySelector("img")).toHaveAttribute(
        "src",
        "blob:thumbnail",
      ),
    );
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/products/p%2F1/image?thumbnail=true"),
      { headers: { Authorization: "Bearer token" } },
    );
    view.unmount();
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:thumbnail");
  });
});
