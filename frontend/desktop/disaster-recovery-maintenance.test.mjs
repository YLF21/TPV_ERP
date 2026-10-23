import { describe, expect, it } from "vitest";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { ACTIONS, sanitizeStatus, publicStatusPath } = require("./disaster-recovery-maintenance.cjs");

describe("disaster recovery desktop bridge", () => {
  it("accepts only fixed local maintenance actions", () => {
    expect([...ACTIONS]).toEqual(["request", "receive", "activate", "status"]);
    for (const unsafe of ["restore", "delete", "download", "../../tool"]) expect(ACTIONS.has(unsafe)).toBe(false);
  });

  it("returns only allowlisted public status fields", () => {
    expect(sanitizeStatus({ state: "SUPPORT_REVIEW", recoveryId: "id", destinationPublicKey: "public",
      recoverySecret: "private", token: "secret", file: "C:\\secret" })).toEqual({
        state: "SUPPORT_REVIEW", recoveryId: "id", destinationPublicKey: "public"
      });
    expect(sanitizeStatus({ state: "RESTORED" })).toBeNull();
  });

  it("anchors public status under ProgramData", () => {
    expect(publicStatusPath("C:\\ProgramData")).toContain("disaster-recovery");
    expect(publicStatusPath("C:\\elsewhere")).toBeNull();
  });
});
