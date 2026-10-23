import { describe, expect, it } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { isLocalBackendUrl, isSafeBackupDirectory } = require("./backup-folder.cjs");

describe("backup folder IPC input restrictions", () => {
  it("accepts only a local backend URL", () => {
    expect(isLocalBackendUrl("http://127.0.0.1:8080")).toBe(true);
    expect(isLocalBackendUrl("http://[::1]:8080")).toBe(true);
    expect(isLocalBackendUrl("http://example.org:8080")).toBe(false);
    expect(isLocalBackendUrl("http://127.0.0.1@remote.example")).toBe(false);
  });

  it("rejects relative, missing and linked directories", () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "tpv-backup-folder-"));
    const directory = path.join(root, "backups");
    const link = path.join(root, "link");
    try {
      fs.mkdirSync(directory);
      expect(isSafeBackupDirectory(directory)).toBe(true);
      expect(isSafeBackupDirectory("backups")).toBe(false);
      expect(isSafeBackupDirectory(path.join(root, "missing"))).toBe(false);
      fs.symlinkSync(directory, link, "junction");
      expect(isSafeBackupDirectory(link)).toBe(false);
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
  });
});
