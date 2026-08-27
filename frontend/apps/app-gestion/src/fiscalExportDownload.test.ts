import { strFromU8, unzipSync } from "fflate";
import { describe, expect, it } from "vitest";
import { buildFiscalExportArchive } from "./fiscalExportDownload";

describe("buildFiscalExportArchive", () => {
  it("conserva cada XML y añade un manifiesto auditable", () => {
    const files = unzipSync(buildFiscalExportArchive({
      exportId: "exp-1",
      kind: "BILLING",
      exportedAt: "2026-08-26T10:00:00Z",
      recordCount: 2,
      eventId: "event-1",
      contentHash: "ABCDEF0123456789",
      companyId: "company-1",
      storeId: "store-1",
      installationId: "installation-1",
      records: [{ recordId: "record-1", sequence: 1, number: "T-1", generatedAt: "2026-08-26T10:00:00Z", hash: "FULL-HASH-1" }, { recordId: "record-2", sequence: 2, number: "T-2", generatedAt: "2026-08-26T10:01:00Z", hash: "FULL-HASH-2" }],
      xml: ["<RegistroAlta>uno</RegistroAlta>", "<RegistroAlta>dos</RegistroAlta>"]
    }));

    expect(strFromU8(files["registro-facturacion-000001.xml"])).toContain("uno");
    expect(strFromU8(files["registro-facturacion-000002.xml"])).toContain("dos");
    expect(JSON.parse(strFromU8(files["manifest.json"]))).toMatchObject({
      exportId: "exp-1",
      recordCount: 2,
      contentHash: "ABCDEF0123456789",
      files: 2
    });
    const manifest = JSON.parse(strFromU8(files["manifest.json"]));
    expect(manifest.companyId).toBe("company-1");
    expect(manifest.firstRecord.hash).toBe("FULL-HASH-1");
    expect(manifest.lastRecord.hash).toBe("FULL-HASH-2");
  });

  it("prioriza el lote firmado de un requerimiento", () => {
    const files = unzipSync(buildFiscalExportArchive({
      exportId: "exp-2",
      kind: "BILLING",
      exportedAt: "2026-08-26T10:00:00Z",
      recordCount: 3,
      contentHash: "FEDCBA9876543210",
      xml: ["<individual/>"] ,
      batchXml: "<LoteFirmado/>"
    }));

    expect(Object.keys(files).sort()).toEqual(["manifest.json", "registro-facturacion-000001.xml"]);
    expect(strFromU8(files["registro-facturacion-000001.xml"])).toBe("<LoteFirmado/>");
  });
});
