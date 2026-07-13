import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  applyStockBulkImageSnapshot,
  cloneStockBulkImageSnapshot,
  createStockBulkImageSnapshot,
  loadStockBulkDraftImages,
  StockBulkImagePanel,
  stockBulkImagePendingAssignments,
  stockBulkImageRows,
  syncStockBulkDraftImages,
  uploadStockBulkImage
} from "./StockBulkImagePanel";

describe("StockBulkImagePanel helpers", () => {
  it("keeps image files and ignores unrelated files", () => {
    const image = new File(["image"], "P-001.png", { type: "image/png" });
    const text = new File(["text"], "notes.txt", { type: "text/plain" });
    expect(stockBulkImageRows([image, text])).toEqual([
      expect.objectContaining({
        selected: false,
        fileName: "P-001.png",
        fileType: "image/png"
      })
    ]);
  });

  it("creates independent snapshots while retaining immutable File values", () => {
    const file = new File(["image"], "P-001.png", { type: "image/png" });
    const source = createStockBulkImageSnapshot(stockBulkImageRows([file]));
    const cloned = cloneStockBulkImageSnapshot(source);

    cloned.rows[0].productId = "product-1";
    cloned.rows[0].selected = true;

    expect(cloned).not.toBe(source);
    expect(cloned.rows[0]).not.toBe(source.rows[0]);
    expect(cloned.rows[0].file).toBe(file);
    expect(source.rows[0].productId).toBe("");
    expect(source.rows[0].selected).toBe(false);
  });

  it("persists new image files and keeps existing staged image ids", async () => {
    const files = [
      new File(["one"], "one.png", { type: "image/png" }),
      new File(["two"], "two.png", { type: "image/png" })
    ];
    const rows = stockBulkImageRows(files);
    rows[0] = { ...rows[0], persistedId: "staged-1", productId: "product-1", status: "matched" };
    rows[1] = { ...rows[1], productId: "product-2", status: "matched" };
    let sentBody: FormData | undefined;
    const request = vi.fn(async (_input: RequestInfo | URL, options?: RequestInit) => {
      sentBody = options?.body as FormData;
      return new Response(JSON.stringify({
        version: 8,
        images: [
          { id: "staged-1", productId: "product-1", position: 0, fileName: "one.png", contentType: "image/png", size: 3, sha256: "a" },
          { id: "staged-2", productId: "product-2", position: 1, fileName: "two.png", contentType: "image/png", size: 3, sha256: "b" }
        ]
      }), { headers: { "Content-Type": "application/json" } });
    });

    const result = await syncStockBulkDraftImages(
      "draft/1",
      7,
      { rows },
      "token",
      request as typeof fetch,
      "/api/v1"
    );

    expect(result.version).toBe(8);
    expect(result.snapshot.rows.map((row) => row.persistedId)).toEqual(["staged-1", "staged-2"]);
    expect(sentBody?.getAll("files")).toHaveLength(1);
    const manifest = JSON.parse(await (sentBody?.get("manifest") as File).text());
    expect(manifest).toEqual({
      version: 7,
      images: [
        { id: "staged-1", productId: "product-1" },
        { productId: "product-2", fileIndex: 0 }
      ]
    });
  });

  it("loads persisted draft images and reconstructs editable files", async () => {
    const request = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify([{
        id: "staged-1",
        productId: "product-1",
        position: 0,
        fileName: "P-001.png",
        contentType: "image/png",
        size: 3,
        sha256: "hash"
      }]), { headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response(new Blob(["img"], { type: "image/png" })));

    const snapshot = await loadStockBulkDraftImages(
      "draft-1",
      "token",
      request as typeof fetch,
      "/api/v1"
    );

    expect(snapshot.rows[0]).toEqual(expect.objectContaining({
      persistedId: "staged-1",
      productId: "product-1",
      fileName: "P-001.png",
      status: "matched"
    }));
    expect(snapshot.rows[0].file).toBeInstanceOf(File);
  });

  it("exposes only assigned images that have not already been applied", () => {
    const files = [
      new File(["one"], "one.png", { type: "image/png" }),
      new File(["two"], "two.png", { type: "image/png" }),
      new File(["three"], "three.png", { type: "image/png" })
    ];
    const rows = stockBulkImageRows(files);
    rows[0] = { ...rows[0], productId: "product-1", status: "matched" };
    rows[1] = { ...rows[1], productId: "product-2", status: "uploaded" };

    expect(stockBulkImagePendingAssignments({ rows })).toEqual([{
      rowId: rows[0].id,
      productId: "product-1",
      file: files[0]
    }]);
  });

  it("uploads one image as multipart and returns the new image id", async () => {
    let capturedOptions: RequestInit | undefined;
    const request = vi.fn(async (_input: RequestInfo | URL, options?: RequestInit) => {
      capturedOptions = options;
      return new Response(JSON.stringify({ imageId: "image-1" }), {
        headers: { "Content-Type": "application/json" }
      });
    });
    const file = new File(["image"], "P-001.png", { type: "image/png" });

    await expect(uploadStockBulkImage("product/1", file, "token", request as typeof fetch))
      .resolves.toBe("image-1");
    expect(capturedOptions?.method).toBe("PUT");
    expect(capturedOptions?.headers).toEqual({ Authorization: "Bearer token" });
    expect(capturedOptions?.body).toBeInstanceOf(FormData);
    expect((capturedOptions?.body as FormData).get("file")).toBe(file);
  });

  it("applies every assigned pending image only when explicitly requested", async () => {
    const files = [
      new File(["one"], "one.png", { type: "image/png" }),
      new File(["two"], "two.png", { type: "image/png" }),
      new File(["three"], "three.png", { type: "image/png" })
    ];
    const rows = stockBulkImageRows(files);
    rows[0] = { ...rows[0], productId: "product-1", status: "matched" };
    rows[1] = { ...rows[1], productId: "product-2", status: "uploaded" };
    const request = vi.fn(async () => new Response(JSON.stringify({ imageId: "new-image" }), {
      headers: { "Content-Type": "application/json" }
    }));

    const result = await applyStockBulkImageSnapshot({ rows }, "token", request as typeof fetch);

    expect(request).toHaveBeenCalledTimes(1);
    expect(result.attempted).toBe(1);
    expect(result.uploaded).toEqual([expect.objectContaining({
      rowId: rows[0].id,
      productId: "product-1",
      imageId: "new-image"
    })]);
    expect(result.failed).toEqual([]);
    expect(result.unassignedRowIds).toEqual([rows[2].id]);
    expect(result.snapshot.rows[0].status).toBe("uploaded");
    expect(result.snapshot.rows[1].status).toBe("uploaded");
    expect(rows[0].status).toBe("matched");
  });

  it("keeps failed assignments pending for retry and continues with later files", async () => {
    const files = [
      new File(["one"], "one.png", { type: "image/png" }),
      new File(["two"], "two.png", { type: "image/png" })
    ];
    const rows = stockBulkImageRows(files).map((row, index) => ({
      ...row,
      productId: `product-${index + 1}`,
      status: "matched" as const
    }));
    const request = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ detail: "invalid image" }), {
        status: 422,
        headers: { "Content-Type": "application/json" }
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ imageId: "image-2" }), {
        headers: { "Content-Type": "application/json" }
      }));

    const result = await applyStockBulkImageSnapshot({ rows }, "token", request as typeof fetch);

    expect(request).toHaveBeenCalledTimes(2);
    expect(result.failed).toEqual([{
      rowId: rows[0].id,
      productId: "product-1",
      error: "invalid image"
    }]);
    expect(result.uploaded).toEqual([expect.objectContaining({ productId: "product-2" })]);
    expect(result.snapshot.rows[0]).toEqual(expect.objectContaining({
      status: "error",
      error: "invalid image"
    }));
    expect(result.snapshot.rows[1].status).toBe("uploaded");
    expect(stockBulkImagePendingAssignments(result.snapshot)).toEqual([expect.objectContaining({
      rowId: rows[0].id,
      productId: "product-1"
    })]);
  });

  it("renders only the three preparation actions and no immediate upload button", () => {
    const file = new File(["image"], "P-001.png", { type: "image/png" });
    const html = renderToStaticMarkup(
      <StockBulkImagePanel
        locale="es"
        products={[]}
        snapshot={createStockBulkImageSnapshot(stockBulkImageRows([file]))}
        onStatus={vi.fn()}
      />
    );

    const toolbarHtml = html.match(/<header class="stock-bulk-image-actions">[\s\S]*?<\/header>/)?.[0] ?? "";
    expect(toolbarHtml.match(/<button/g)).toHaveLength(3);
    expect(html).toContain("Abrir carpeta");
    expect(html).toContain("Comparar por nombre");
    expect(html).toContain("Comparar por código");
    expect(html).toContain("erp-select__trigger");
    expect(html).not.toContain("<select");
    expect(html).toContain("Tipo de archivo");
    expect(html).toContain("Asignación manual");
    expect(html).not.toContain("Subir");
  });
});
