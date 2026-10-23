import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { recoveryNoticeKey } from "../src/features/support/backup-recovery-notices.mjs";

describe("support recovery notices", () => {
  it("maps new central evidence blocker and both recovery warnings without exposing server codes", () => {
    assert.equal(recoveryNoticeKey("CENTRAL_PROJECTION_EVIDENCE_INCOMPLETE", "blocker"),
      "blocker.centralProjectionIncomplete");
    assert.equal(recoveryNoticeKey("UNSYNCED_POST_BACKUP_DATA_MAY_BE_LOST", "warning"),
      "warning.postBackupLoss");
    assert.equal(recoveryNoticeKey("EXTERNAL_FISCAL_AND_SHARED_DATA_REVIEW_REQUIRED", "warning"),
      "warning.externalReview");
    assert.equal(recoveryNoticeKey("NEW_BLOCKER", "blocker"), "blocker.other");
    assert.equal(recoveryNoticeKey("NEW_WARNING", "warning"), "warning.other");
  });
});
