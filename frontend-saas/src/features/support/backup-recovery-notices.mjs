const blockers = Object.freeze({
  INSTALLATION_REVOKED: "blocker.installationRevoked",
  LICENSE_NOT_VALID: "blocker.licenseInvalid",
  CENTRAL_PROJECTION_EVIDENCE_INCOMPLETE: "blocker.centralProjectionIncomplete",
  DESTINATION_NOT_RECEIVED: "blocker.destinationNotReceived",
  DESTINATION_VALIDATION_INCOMPLETE: "blocker.destinationValidationIncomplete",
  PORTABLE_SECRETS_INCOMPLETE: "blocker.portableSecretsIncomplete",
  PAYMENTS_UNRESOLVED: "blocker.paymentsUnresolved",
  CENTRAL_AHEAD_OR_CHANGED: "blocker.centralAhead",
  CENTRAL_EVIDENCE_INCOMPLETE: "blocker.centralEvidenceIncomplete"
});

const warnings = Object.freeze({
  UNSYNCED_POST_BACKUP_DATA_MAY_BE_LOST: "warning.postBackupLoss",
  EXTERNAL_FISCAL_AND_SHARED_DATA_REVIEW_REQUIRED: "warning.externalReview"
});

export function recoveryNoticeKey(code, kind) {
  return (kind === "blocker" ? blockers[code] ?? "blocker.other" : warnings[code] ?? "warning.other");
}
